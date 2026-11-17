package com.trueket.backend.trueshot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;

import java.time.OffsetDateTime;
import java.util.List;

public record TrueshotPhotoResponse(
            @Schema(description = "사진 번호: 1 또는 2", example = "1") int number,
            @Schema(description = "현재 인증 진행 단계") TrueshotPhase phase,
            @Schema(description = "사진 접수 여부. HTTP 200이어도 false이면 재촬영 필요") boolean accepted,
            @Schema(description = "다음 처리 또는 판정 기한. 해당 없으면 null") OffsetDateTime deadline,
            @Schema(description = "재촬영이 필요한 이유. accepted=false일 때 확인") List<String> retryReasons,
            @Schema(description = "사진별 품질·인증 코드·촬영 미션 검사 결과") List<TrueshotChecks.Check> checks,
            @Schema(description = "인증 실패 사유 목록") List<String> failReasons
    ) {}
