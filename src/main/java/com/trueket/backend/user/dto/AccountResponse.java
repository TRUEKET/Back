package com.trueket.backend.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AccountResponse(Long id, @Schema(example="fan@example.invalid") String email, @Schema(description="본인 계정 설정용 전체 전화번호. 다른 사용자에게 노출하지 않습니다.", example="01012345678") String phone) {}
