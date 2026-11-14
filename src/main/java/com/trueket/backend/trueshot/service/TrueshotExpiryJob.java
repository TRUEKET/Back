package com.trueket.backend.trueshot.service;

import com.trueket.backend.trueshot.entity.enums.TrueshotPhase;
import com.trueket.backend.trueshot.entity.enums.TrueshotStatus;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import com.trueket.backend.trueshot.repository.TrueshotPhotoRepository;
import com.trueket.backend.global.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.LongConsumer;

@Component
public class TrueshotExpiryJob {
    private static final Logger LOG = LoggerFactory.getLogger(TrueshotExpiryJob.class);
    private static final List<TrueshotPhase> OPEN_PHASES = List.of(
            TrueshotPhase.WAITING, TrueshotPhase.FIRST_CHECKING, TrueshotPhase.FIRST_MASKING,
            TrueshotPhase.SECOND_WAITING, TrueshotPhase.JUDGING, TrueshotPhase.SHARE_WAITING);
    private final TrueshotRequestRepository requests;
    private final TrueshotRequestService service;
    private final TrueshotPhotoRepository photos;

    public TrueshotExpiryJob(TrueshotRequestRepository requests, TrueshotRequestService service,
            TrueshotPhotoRepository photos) {
        this.requests = requests;
        this.service = service;
        this.photos = photos;
    }

    // 요청 기한이 지난 건을 서버에서 실패 또는 사진 미공유 완료로 확정한다.
    @Scheduled(fixedDelay = 60_000)
    public void expireRequests() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        requests.findByExpiresAtLessThanEqualAndPhaseIn(now, OPEN_PHASES)
                .forEach(request -> run(request.getId(), service::expire));
        requests.findByPhaseAndDeadline1LessThanEqual(TrueshotPhase.FIRST_CHECKING, now)
                .forEach(request -> run(request.getId(), id -> service.expirePhoto(id, 1)));
        requests.findByPhaseAndDeadline2LessThanEqual(TrueshotPhase.SECOND_WAITING, now)
                .forEach(request -> run(request.getId(), id -> service.expirePhoto(id, 2)));
        // 삭제 API가 일시적으로 실패해도 다음 실행에서 임시 원본을 다시 정리한다.
        photos.findRequestIdsWithOriginalsByStatus(TrueshotStatus.FAILED)
                .forEach(id -> run(id, service::cleanFailedOriginals));
    }

    private void run(Long id, LongConsumer action) {
        try {
            action.accept(id);
        } catch (ApiException e) {
            LOG.warn("트루샷 만료 또는 임시 원본 삭제 재시도 필요: 요청 {}", id);
        }
    }
}
