package com.trueket.backend.global.ai;

public class ModelException extends RuntimeException {
    public enum Kind { CONFIGURATION, AUTHENTICATION, RATE_LIMIT, TIMEOUT, UPSTREAM, INVALID_RESPONSE, REFUSED, INCOMPLETE }
    private final Kind kind;

    public ModelException(Kind kind) {
        super("AI 모델 request failed: " + kind);
        this.kind = kind;
    }

    public Kind kind() { return kind; }

    public boolean retryable() {
        return kind == Kind.TIMEOUT || kind == Kind.UPSTREAM || kind == Kind.INVALID_RESPONSE;
    }
}
