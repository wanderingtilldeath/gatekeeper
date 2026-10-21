package com.gatekeeper.svc_ai;

import static org.junit.jupiter.api.Assertions.*;

import com.gatekeeper.svc_ai.IncidentService.Finding;
import com.gatekeeper.svc_ai.IncidentService.Rec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IncidentTest {
    AiProps props = new AiProps(0, false, 0, Set.of(), Set.of(), Map.of(), 0.2, 1000);

    static List<Rec> n(int count, String svc, int status, long ms, String err) {
        List<Rec> l = new ArrayList<>();
        for (int i = 0; i < count; i++) l.add(new Rec(0, svc + "-" + status + "-" + i, svc, "/p", status, ms, err));
        return l;
    }

    static List<Rec> all(List<Rec>... parts) {
        List<Rec> l = new ArrayList<>();
        for (var p : parts) l.addAll(p);
        return l;
    }

    List<Finding> kinds(List<Rec> recs, String kind) {
        return IncidentService.diagnose(recs, props).stream().filter(f -> f.kind().equals(kind)).toList();
    }

    @Test
    void healthyTrafficHasNoFindings() {
        assertTrue(IncidentService.diagnose(n(20, "auth", 200, 30, null), props).isEmpty());
        assertTrue(IncidentService.diagnose(List.of(), props).isEmpty());
    }

    @Test
    void missingServiceIsNamedAndNotCountedAsServerErrors() {
        var recs = all(n(6, "svc-java", 503, 5, "ResponseStatusException: Unable to find instance for svc-go"), n(10, "auth", 200, 20, null));
        var f = kinds(recs, "NOT_REGISTERED");
        assertEquals("svc-go", f.get(0).service());
        assertTrue(f.get(0).fix().contains("Eureka"));
        assertTrue(kinds(recs, "ERRORS").isEmpty());
    }

    @Test
    void oneFailingServiceIsBlamedOthersAreNot() {
        var recs = all(n(6, "svc-java", 500, 50, "boom"), n(4, "svc-java", 200, 50, null), n(20, "auth", 200, 20, null));
        var f = kinds(recs, "ERRORS");
        assertEquals(1, f.size());
        assertEquals("svc-java", f.get(0).service());
        assertEquals(3, f.get(0).sampleRequestIds().size());
    }

    @Test
    void slowServiceIsFlagged() {
        assertEquals(1, kinds(n(10, "svc-java", 200, 3000, null), "SLOW").size());
    }

    @Test
    void rateLimitSpikeAndAuthSpikeAreFlagged() {
        assertEquals(1, kinds(all(n(5, "svc-ai", 429, 5, null), n(5, "svc-ai", 200, 5, null)), "RATE_LIMITED").size());
        assertEquals(1, kinds(all(n(5, "svc-ai", 403, 5, null), n(5, "svc-ai", 200, 5, null)), "AUTH").size());
    }

    @Test
    void tooFewRequestsGiveNoRateVerdict() {
        assertTrue(IncidentService.diagnose(n(3, "svc-java", 500, 50, "boom"), props).isEmpty());
    }

    @Test
    void singleRequestExplanation() {
        assertTrue(IncidentService.explainOne(n(1, "svc-java", 503, 5, "X: Unable to find instance for svc-go").get(0)).contains("svc-go is not registered"));
        assertTrue(IncidentService.explainOne(n(1, "auth", 403, 5, null).get(0)).contains("invalid"));
    }
}
