package com.trueket.backend.lookup.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LookupRiskCalculatorTest {

    @Test
    void 피해_신고자가_한명_이상이면_위험이다() {
        String result = LookupRiskCalculator.calculate(1, 0);

        assertThat(result).isEqualTo("DANGER");
    }

    @Test
    void 의심_제보자가_두명_이상이면_의심이다() {
        String result = LookupRiskCalculator.calculate(0, 2);

        assertThat(result).isEqualTo("SUSPICION");
    }

    @Test
    void 의심_제보자가_한명이면_안전이다() {
        String result = LookupRiskCalculator.calculate(0, 1);

        assertThat(result).isEqualTo("SAFE");
    }

    @Test
    void 신고자가_없으면_안전이다() {
        String result = LookupRiskCalculator.calculate(0, 0);

        assertThat(result).isEqualTo("SAFE");
    }

    @Test
    void 피해와_의심이_동시에_존재하면_위험이_우선이다() {
        String result = LookupRiskCalculator.calculate(1, 3);

        assertThat(result).isEqualTo("DANGER");
    }
}