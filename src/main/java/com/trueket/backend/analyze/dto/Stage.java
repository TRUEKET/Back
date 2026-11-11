package com.trueket.backend.analyze.dto;

public enum Stage {
    SAFE, SUSPECT, DANGER;

    public Stage moreSevere(Stage other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
