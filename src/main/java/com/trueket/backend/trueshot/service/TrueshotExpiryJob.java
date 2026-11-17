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
import org.springframework.data.domain.PageRequest;

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
    private final TrueshotJudgmentService judgments;
    private final TrueshotRetentionService retention;

    public TrueshotExpiryJob(TrueshotRequestRepository requests, TrueshotRequestService service,
            TrueshotPhotoRepository photos, TrueshotJudgmentService judgments, TrueshotRetentionService retention) {
        this.requests = requests;
        this.service = service;
        this.photos = photos;
        this.judgments = judgments;
        this.retention = retention;
    }

    // 보관 만료는 인증 성공·실패와 관계없이 적용한다. 키 기준으로 나누어 전체 행을 한꺼번에 읽지 않는다.
    @Scheduled(fixedDelay = 60_000)
    public void purgeExpiredRequests() {
        OffsetDateTime cutoff=OffsetDateTime.now(ZoneOffset.UTC);
        long afterId=0;
        while (true) {
            var ids=requests.findExpiredIds(cutoff,afterId,PageRequest.of(0,100));
            if (ids.isEmpty()) return;
            for (Long id:ids) {
                try {
                    retention.purge(id,cutoff);
                } catch (RuntimeException e) {
                    // 실패한 요청을 다음 주기에 다시 조회하되 이번 주기의 나머지 요청은 계속 처리한다.
                    LOG.warn("트루샷 보관 만료 정리 재시도 필요: 요청 {}, 오류 {}",id,e.getClass().getSimpleName());
                }
            }
            afterId=ids.get(ids.size()-1);
        }
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
                .forEach(id -> run(id, service::cleanFinalOriginals));
        photos.findRequestIdsWithOriginalsByStatus(TrueshotStatus.PASSED)
                .forEach(id -> run(id, service::cleanFinalOriginals));
    }

    // 판정 대기 재시도는 원본 정리·보관 만료 주기와 분리해 더 짧은 간격으로 실행한다.
    @Scheduled(fixedDelayString="${trueshot.judgment.retry-delay-ms:10000}")
    public void retryJudgments() {
        requests.findByPhase(TrueshotPhase.JUDGING)
                .forEach(request -> run(request.getId(), judgments::judgeById));
    }

    private void run(Long id, LongConsumer action) {
        try {
            action.accept(id);
        } catch (ApiException e) {
            LOG.warn("트루샷 만료 또는 임시 원본 삭제 재시도 필요: 요청 {}", id);
        }
    }
}
