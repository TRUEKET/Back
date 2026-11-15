
package com.trueket.backend.lookup.controller;

import com.trueket.backend.global.response.ApiResponse;
import com.trueket.backend.lookup.dto.LookupDetailResponse;
import com.trueket.backend.lookup.dto.LookupResponse;
import com.trueket.backend.lookup.service.LookupService;
import com.trueket.backend.lookup.dto.LookupCompareRequest;
import com.trueket.backend.lookup.dto.LookupCompareResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/lookup")
@RequiredArgsConstructor
public class LookupController {

    private final LookupService lookupService;

    @GetMapping
    public ResponseEntity<ApiResponse<LookupResponse>> lookup(
            @RequestParam("q") String query
    ) {
        LookupResponse response = lookupService.lookup(query);

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }

    @GetMapping("/{identifierId}")
    public ResponseEntity<ApiResponse<LookupDetailResponse>> getLookupDetail(
            @PathVariable Long identifierId
    ) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        lookupService.getLookupDetail(identifierId)
                )
        );
    }


    /**
     * RP-03. 식별자 전체 값 비교
     */
    @PostMapping("/{identifierId}/compare")
    public ResponseEntity<ApiResponse<LookupCompareResponse>> compare(
            @PathVariable Long identifierId,
            @Valid @RequestBody LookupCompareRequest request
    ) {
        LookupCompareResponse response = lookupService.compare(
                identifierId,
                request.value()
        );

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }

}
