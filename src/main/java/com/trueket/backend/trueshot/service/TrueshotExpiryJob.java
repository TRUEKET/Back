package com.trueket.backend.trueshot.service;

import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class TrueshotExpiryJob {
    private static final List<TrueshotPhase> OPEN_PHASES = List.of(
            TrueshotPhase.WAITING, TrueshotPhase.FIRST_CHECKING, TrueshotPhase.FIRST_MASKING,
            TrueshotPhase.SECOND_WAITING, TrueshotPhase.JUDGING, TrueshotPhase.SHARE_WAITING);
    private final TrueshotRequestRepository requests;
    private final TrueshotRequestService service;

    public TrueshotExpiryJob(TrueshotRequestRepository requests, TrueshotRequestService service) {
        this.requests = requests;
        this.service = service;
    }

    // 요청 기한이 지난 건을 서버에서 실패 또는 사진 미공유 완료로 확정한다.
    @Scheduled(fixedDelay = 60_000)
    public void expireRequests() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        requests.findByExpiresAtLessThanEqualAndPhaseIn(now, OPEN_PHASES)
                .forEach(request -> service.expire(request.getId()));
    }
}
