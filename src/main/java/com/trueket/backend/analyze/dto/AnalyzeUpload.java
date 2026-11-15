package com.trueket.backend.analyze.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Documentation-only multipart schema; runtime binding remains @RequestParam. */
@Schema(description="대화 캡처 multipart 입력")
public record AnalyzeUpload(
        @ArraySchema(minItems=1, maxItems=10,
                arraySchema=@Schema(description="PNG·JPG 권장, 장당 최대 10MiB", requiredMode=Schema.RequiredMode.REQUIRED),
                schema=@Schema(type="string", format="binary")) List<String> captures,
        @Schema(description="선택 입력. 추가 거래 정보, 최대 500자", maxLength=500,
                example="거래 유형은 양도입니다.") String additionalInfo) {}
