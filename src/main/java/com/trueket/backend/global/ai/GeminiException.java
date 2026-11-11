package com.trueket.backend.global.ai;

public class GeminiException extends RuntimeException {
    public enum Kind { CONFIGURATION, AUTHENTICATION, RATE_LIMIT, TIMEOUT, UPSTREAM, INVALID_RESPONSE }
    private final Kind kind;

    public GeminiException(Kind kind) {
        super("Gemini request failed: " + kind);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    public boolean retryable() {
        return kind == Kind.TIMEOUT || kind == Kind.UPSTREAM || kind == Kind.INVALID_RESPONSE;
    }
}
