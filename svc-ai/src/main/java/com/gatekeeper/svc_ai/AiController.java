package com.gatekeeper.svc_ai;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Trusts X-User, which the gateway sets; only reachable through the gateway. */
@RestController
@RequestMapping("/ai")
public class AiController {
    public record ChatRequest(String prompt) {}
    public record ActRequest(String agent, String tool, String args) {}

    private final ChatService chat;
    private final ActionService actions;
    private final IncidentService incidents;
    private final AiProps props;

    AiController(ChatService chat, ActionService actions, IncidentService incidents, AiProps props) {
        this.chat = chat; this.actions = actions; this.incidents = incidents; this.props = props;
    }

    @PostMapping("/chat")
    ChatService.Reply chat(@RequestHeader("X-User") String user, @RequestBody ChatRequest req) {
        return chat.chat(user, req.prompt());
    }

    @GetMapping("/usage")
    Map<String, Object> usage(@RequestHeader("X-User") String user) {
        return Map.of("user", user, "tokensUsedToday", chat.usedToday(user), "dailyAllowance", props.dailyTokens());
    }

    @PostMapping("/agent/act")
    ResponseEntity<ActionService.Entry> act(@RequestHeader("X-User") String user, @RequestBody ActRequest req) {
        ActionService.Entry e = actions.act(user, req.agent(), req.tool(), req.args());
        HttpStatus status = switch (e.status()) {
            case "DENIED" -> HttpStatus.FORBIDDEN;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "PENDING" -> HttpStatus.ACCEPTED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(e);
    }

    @GetMapping("/agent/audit")
    List<ActionService.Entry> audit(@RequestParam(required = false) String status) {
        return actions.audit(status);
    }

    @PostMapping("/agent/approvals/{id}/approve")
    ActionService.Entry approve(@RequestHeader("X-User") String user, @PathVariable String id) {
        return actions.decide(user, id, true);
    }

    @PostMapping("/agent/approvals/{id}/reject")
    ActionService.Entry reject(@RequestHeader("X-User") String user, @PathVariable String id) {
        return actions.decide(user, id, false);
    }

    /** Shows every user's traffic, so admins only. */
    @GetMapping("/incident/summary")
    IncidentService.Summary incidentSummary(@RequestHeader("X-User") String user, @RequestParam(defaultValue = "15") int minutes) {
        props.requireAdmin(user);
        return incidents.summary(minutes);
    }

    @GetMapping("/incident/request/{requestId}")
    Map<String, String> incidentRequest(@RequestHeader("X-User") String user, @PathVariable String requestId) {
        props.requireAdmin(user);
        return Map.of("requestId", requestId, "explanation", incidents.explainRequest(requestId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "request not in the log")));
    }
}
