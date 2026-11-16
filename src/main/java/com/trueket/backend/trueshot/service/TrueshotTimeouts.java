package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.entity.TrueshotRequest;
import com.trueket.backend.trueshot.entity.enums.TrueshotFailureCode;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;

final class TrueshotTimeouts {
    private TrueshotTimeouts() {}

    static void check(TrueshotRequest request, OffsetDateTime now) {
        var failure = request.failureCode(now);
        if (failure != null && failure.isTimeout()) {
            throw new ApiException(HttpStatus.GONE, failure.name(), failure.message());
        }
        if (request.isExpired(now)) {
            var timeout = TrueshotFailureCode.REQUEST_TIMEOUT;
            throw new ApiException(HttpStatus.GONE, timeout.name(), timeout.message());
        }
    }
}
