package com.gatekeeper.svc_ai;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Agent checkpoint: permission list, speed limit, human approval for risky tools, audit record. */
@Service
public class ActionService {

    public record Entry(String id, Instant at, String user, String agent, String tool, String args, String status, String result) {
        Entry with(String status, String result) { return new Entry(id, at, user, agent, tool, args, status, result); }
    }

    private final StringRedisTemplate redis;
    private final AiProps props;
    private final Events events;
    // shortcut: in-memory audit, lost on restart; move to a table when it must survive
    private final Map<String, Entry> audit = new ConcurrentHashMap<>();

    ActionService(StringRedisTemplate redis, AiProps props, Events events) {
        this.redis = redis; this.props = props; this.events = events;
    }

    public Entry act(String user, String agent, String tool, String rawArgs) {
        String args = Redactor.redact(rawArgs == null ? "" : rawArgs);
        if (!props.agents().getOrDefault(agent, Set.of()).contains(tool)) {
            return record(user, agent, tool, args, "DENIED", agent + " may not use " + tool);
        }
        String key = "ai:agent:" + agent + ":" + (System.currentTimeMillis() / 60_000);
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) redis.expire(key, Duration.ofMinutes(2));
        if (n != null && n > props.agentPerMinute()) {
            return record(user, agent, tool, args, "RATE_LIMITED", "agent over " + props.agentPerMinute() + " actions/min");
        }
        if (props.risky().contains(tool)) {
            Entry e = record(user, agent, tool, args, "PENDING", "waiting for human approval");
            events.publish("ACTION_PENDING", user, e.id() + ": " + agent + " wants " + tool);
            return e;
        }
        return record(user, agent, tool, args, "EXECUTED", execute(tool, args));
    }

    public Entry decide(String user, String id, boolean approve) {
        props.requireAdmin(user);
        Entry e = audit.get(id);
        if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such action");
        if (!"PENDING".equals(e.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "already decided");
        Entry done = approve ? e.with("EXECUTED", execute(e.tool(), e.args())) : e.with("REJECTED", "rejected by " + user);
        // compare-and-swap on the PENDING entry so a double click cannot run it twice
        if (!audit.replace(id, e, done)) throw new ResponseStatusException(HttpStatus.CONFLICT, "already decided");
        return done;
    }

    public List<Entry> audit(String status) {
        return audit.values().stream()
                .filter(e -> status == null || e.status().equals(status))
                .sorted(Comparator.comparing(Entry::at)).toList();
    }

    // Harmless demo executor: real tools would be called here.
    private static String execute(String tool, String args) {
        return "ran " + tool + "(" + args + ")";
    }

    private Entry record(String user, String agent, String tool, String args, String status, String result) {
        Entry e = new Entry(UUID.randomUUID().toString().substring(0, 8), Instant.now(), user, agent, tool, args, status, result);
        audit.put(e.id(), e);
        return e;
    }
}
