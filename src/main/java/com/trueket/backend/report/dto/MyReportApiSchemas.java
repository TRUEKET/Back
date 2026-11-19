package com.trueket.backend.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

/** OpenAPI-only wrappers to retain generic data schemas when response examples are declared. */
public final class MyReportApiSchemas {
    private MyReportApiSchemas() {}
    @Schema(name="MyReportListApiResponse")
    public record ListResponse(String code, String message, Page<ReportListResponse> data) {}
    @Schema(name="MyReportDetailApiResponse")
    public record DetailResponse(String code, String message, ReportDetailResponse data) {}
}
