package com.gatekeeper.svc_ai;

import java.util.regex.Pattern;

/** Blanks private details before text leaves the company. Pattern based, so it misses unusual formats. */
public final class Redactor {
    private static final Pattern[] PATTERNS = {
        Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),                          // email
        Pattern.compile("\\b\\d(?:[ -]?\\d){12,15}\\b"),                              // card number
        Pattern.compile("\\b(?:AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,})\\b"), // API keys
        Pattern.compile("\\+?\\d[\\d -]{8,}\\d"),                                  // phone
    };
    private static final String[] TAGS = {"[EMAIL]", "[CARD]", "[SECRET]", "[PHONE]"};

    private Redactor() {}

    public static String redact(String text) {
        for (int i = 0; i < PATTERNS.length; i++) text = PATTERNS[i].matcher(text).replaceAll(TAGS[i]);
        return text;
    }
}
