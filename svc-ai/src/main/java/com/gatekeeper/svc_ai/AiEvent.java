package com.gatekeeper.svc_ai;

public record AiEvent(String kind, String user, String detail) {
    public static final String EXCHANGE = "ai";
    public static final String ROUTING_KEY = "ai.event";
}
