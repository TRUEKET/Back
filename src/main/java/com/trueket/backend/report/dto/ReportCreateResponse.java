
package com.trueket.backend.report.dto;

import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "RP-04 신고·제보 등록 결과")
public record ReportCreateResponse(

        @Schema(
                description = "생성된 신고 ID",
                example = "365"
        )
        Long reportId,

        @Schema(
                description = "등록된 신고 종류",
                example = "FRAUD"
        )
        ReportKind kind,

        @Schema(
                description = "신고 상태",
                example = "PUBLISHED"
        )
        ReportStatus status,

        @Schema(
                description = "신고에 연결된 식별자 ID 목록",
                example = "[308, 309]"
        )
        List<Long> identifierIds
) {
}
