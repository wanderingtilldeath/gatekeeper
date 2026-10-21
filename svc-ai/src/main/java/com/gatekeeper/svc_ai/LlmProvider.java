package com.gatekeeper.svc_ai;

public interface LlmProvider {
    String complete(String prompt);
}
