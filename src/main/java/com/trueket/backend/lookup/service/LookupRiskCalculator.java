package com.trueket.backend.lookup.service;

public final class LookupRiskCalculator {

    private LookupRiskCalculator() {
    }

    public static String calculate(
            long fraudCount,
            long suspicionCount
    ) {
        if (fraudCount >= 1) {
            return "DANGER";
        }

        if (suspicionCount >= 2) {
            return "SUSPICION";
        }

        return "SAFE";
    }
}