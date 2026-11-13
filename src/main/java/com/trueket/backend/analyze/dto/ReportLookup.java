package com.trueket.backend.analyze.dto;

import java.util.List;
import java.util.Objects;

public record ReportLookup(boolean available, List<Target> targets) {
    public ReportLookup {
        targets = List.copyOf(targets);
        if (!available && !targets.isEmpty()) {
            throw new IllegalArgumentException("조회 실패 시 신고 집계를 사용할 수 없습니다.");
        }
    }

    public record Target(String identifierId, long damagePeople, long suspectPeople) {
        public Target {
            Objects.requireNonNull(identifierId);
            if (identifierId.isBlank() || damagePeople < 0 || suspectPeople < 0) {
                throw new IllegalArgumentException("식별자와 신고 인원을 확인해 주세요.");
            }
        }

        public Stage stage() {
            if (damagePeople >= 1) {
                return Stage.DANGER;
            }
            return suspectPeople >= 2 ? Stage.SUSPECT : Stage.SAFE;
        }
    }
}
