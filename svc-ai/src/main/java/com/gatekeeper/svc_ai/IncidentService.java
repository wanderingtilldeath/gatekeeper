package com.gatekeeper.svc_ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Reads the gateway's request log and says in plain words which service is unhealthy, why, and what to try. */
@Service
public class IncidentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    static final String KEY = "incidents:requests"; // written by the gateway's RequestLogFilter
    private static final int MIN_REQUESTS = 5; // too few requests = no verdict on rates
    private static final Pattern NO_INSTANCE = Pattern.compile("Unable to find instance for (\\S+)");

    public record Rec(long ts, String requestId, String service, String path, int status, long latencyMs, String error) {}
    public record Finding(String service, String kind, String problem, String fix, List<String> sampleRequestIds) {}
    public record Health(String service, int requests, double badRate, long p95Ms, String state) {}
    public record Summary(int minutes, int requests, List<Health> services, List<Finding> findings, String explanation) {}

    private final StringRedisTemplate redis;
    private final ChatService chat;
    private final AiProps props;
    private final Events events;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> alerted = new HashSet<>();

    IncidentService(StringRedisTemplate redis, ChatService chat, AiProps props, Events events) {
        this.redis = redis; this.chat = chat; this.props = props; this.events = events;
    }

    public Summary summary(int minutes) {
        List<Rec> recs = recent(minutes);
        List<Finding> findings = diagnose(recs, props);
        return new Summary(minutes, recs.size(), health(recs, findings), findings, explain(findings));
    }

    public Optional<String> explainRequest(String requestId) {
        return load().stream().filter(r -> requestId.equals(r.requestId())).findFirst().map(IncidentService::explainOne);
    }

    /** Rules first (they decide what is wrong); the AI only rewords the result. */
    static List<Finding> diagnose(List<Rec> recs, AiProps props) {
        List<Finding> out = new ArrayList<>();
        Map<String, List<Rec>> byService = new TreeMap<>();
        for (Rec r : recs) byService.computeIfAbsent(r.service(), k -> new ArrayList<>()).add(r);

        Map<String, List<Rec>> notRegistered = new TreeMap<>();
        for (Rec r : recs) {
            Matcher m = r.error() == null ? null : NO_INSTANCE.matcher(r.error());
            if (m != null && m.find()) notRegistered.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(r);
        }
        notRegistered.forEach((svc, rs) -> out.add(new Finding(svc, "NOT_REGISTERED",
                svc + " is not registered in Eureka, so the gateway cannot find it (" + rs.size() + " requests failed)",
                "Check that " + svc + " is running and shows up at the Eureka dashboard (:8761)", ids(rs))));

        byService.forEach((svc, rs) -> {
            if (rs.size() < MIN_REQUESTS) return;
            List<Rec> failing = rs.stream().filter(r -> r.status() >= 500 && !isNoInstance(r)).toList();
            if (share(failing, rs) >= props.incidentRate()) {
                out.add(new Finding(svc, "ERRORS", svc + " is returning server errors on " + pct(failing, rs) + " of requests",
                        "Look at the " + svc + " logs for the sample request ids and check what it depends on", ids(failing)));
            }
            long p95 = p95(rs);
            if (p95 > props.incidentSlowMs()) {
                out.add(new Finding(svc, "SLOW", svc + " is slow: 95% of requests finish within " + p95 + " ms, above the " + props.incidentSlowMs() + " ms limit",
                        "Check what " + svc + " calls (database, other services) and whether it is overloaded",
                        ids(rs.stream().filter(r -> r.latencyMs() > props.incidentSlowMs()).toList())));
            }
            List<Rec> limited = rs.stream().filter(r -> r.status() == 429).toList();
            if (share(limited, rs) >= props.incidentRate()) {
                out.add(new Finding(svc, "RATE_LIMITED", pct(limited, rs) + " of requests to " + svc + " were rejected by the rate limiter",
                        "Find the user or bot sending too many requests; raise the limit only if the traffic is legitimate", ids(limited)));
            }
            List<Rec> denied = rs.stream().filter(r -> r.status() == 401 || r.status() == 403).toList();
            if (share(denied, rs) >= props.incidentRate()) {
                out.add(new Finding(svc, "AUTH", pct(denied, rs) + " of requests to " + svc + " were refused for missing or invalid tokens",
                        "Check that the auth service is up and that clients send valid tokens; many from one client may be an attack", ids(denied)));
            }
        });
        return out;
    }

    static String explainOne(Rec r) {
        Matcher m = r.error() == null ? null : NO_INSTANCE.matcher(r.error());
        if (m != null && m.find()) return "Failed: " + m.group(1) + " is not registered in Eureka. Check that it is running.";
        if (r.status() >= 500) return "Failed with " + r.status() + " in " + r.service() + (r.error() == null ? "" : " (" + r.error() + ")") + ". Check that service's logs for this request id.";
        return switch (r.status()) {
            case 429 -> "Rejected by the rate limiter: this client sent too many requests.";
            case 401 -> "Rejected: no token was sent.";
            case 403 -> "Rejected: the token was invalid, or the auth service could not be reached.";
            default -> "Looks fine: " + r.status() + " in " + r.latencyMs() + " ms" + (r.latencyMs() > 1000 ? " (slow)" : "") + ".";
        };
    }

    /** Check every 30s and alert once per problem, so on-call hears about it before customers do. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void watch() {
        try {
            Set<String> now = new HashSet<>();
            for (Finding f : diagnose(recent(5), props)) {
                String key = f.service() + ":" + f.kind();
                now.add(key);
                if (alerted.add(key)) events.publish("INCIDENT", f.service(), f.problem() + ". " + f.fix());
            }
            alerted.retainAll(now); // problem gone: alert again if it comes back
        } catch (Exception e) {
            log.warn("incident watch failed: {}", e.getMessage());
        }
    }

    private String explain(List<Finding> findings) {
        if (findings.isEmpty()) return "No problems found in this time window.";
        String facts = String.join("\n", findings.stream().map(f -> "- " + f.problem() + ". " + f.fix()).toList());
        try {
            return chat.callProviders("Explain these problems to an on-call engineer in plain English and suggest what to do first:\n" + Redactor.redact(facts));
        } catch (Exception e) {
            return facts; // providers down: the rule text is still a complete answer
        }
    }

    private List<Health> health(List<Rec> recs, List<Finding> findings) {
        Map<String, List<Rec>> by = new TreeMap<>();
        for (Rec r : recs) by.computeIfAbsent(r.service(), k -> new ArrayList<>()).add(r);
        List<Health> out = new ArrayList<>();
        by.forEach((svc, rs) -> out.add(new Health(svc, rs.size(),
                share(rs.stream().filter(r -> r.status() >= 500).toList(), rs), p95(rs),
                findings.stream().anyMatch(f -> f.service().equals(svc)) ? "DEGRADED" : "OK")));
        // a service that only appears as "not registered" has no records of its own
        findings.stream().filter(f -> f.kind().equals("NOT_REGISTERED") && !by.containsKey(f.service()))
                .forEach(f -> out.add(new Health(f.service(), 0, 1.0, 0, "DOWN")));
        return out;
    }

    private List<Rec> recent(int minutes) {
        long since = System.currentTimeMillis() - minutes * 60_000L;
        return load().stream().filter(r -> r.ts() >= since).toList();
    }

    private List<Rec> load() {
        List<String> raw = redis.opsForList().range(KEY, 0, -1);
        List<Rec> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw) {
            try { out.add(mapper.readValue(s, Rec.class)); } catch (Exception e) { /* skip a malformed line */ }
        }
        return out;
    }

    private static boolean isNoInstance(Rec r) { return r.error() != null && NO_INSTANCE.matcher(r.error()).find(); }
    private static double share(List<Rec> part, List<Rec> all) { return all.isEmpty() ? 0 : (double) part.size() / all.size(); }
    private static String pct(List<Rec> part, List<Rec> all) { return Math.round(share(part, all) * 100) + "%"; }
    private static List<String> ids(List<Rec> rs) { return rs.stream().map(Rec::requestId).limit(3).toList(); }

    private static long p95(List<Rec> rs) {
        List<Long> l = rs.stream().map(Rec::latencyMs).sorted().toList();
        return l.isEmpty() ? 0 : l.get((int) Math.min(l.size() - 1, Math.ceil(l.size() * 0.95) - 1));
    }
}
