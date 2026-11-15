package com.trueket.backend.dispute.scheduler;

import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.dispute.service.DisputeExpirationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DisputeExpirationScheduler {

    private final DisputeItemRepository itemRepository;
    private final DisputeExpirationService expirationService;

    // 1분마다 만료된 이의제기 확인
    @Scheduled(fixedDelay = 60000)
    public void expireDisputes() {

        OffsetDateTime now = OffsetDateTime.now();

        List<Long> expiredIds = itemRepository
                .findAllByStatusAndDeadlineLessThanEqual(
                        DisputeItemStatus.RESPONDING,
                        now
                )
                .stream()
                .map(DisputeItem::getId)
                .toList();

        int completed = 0;

        for (Long itemId : expiredIds) {
            try {
                if (expirationService.expireOne(itemId)) {
                    completed++;
                }
            } catch (Exception e) {
                log.error(
                        "이의제기 자동 비공개 처리 실패: itemId={}",
                        itemId,
                        e
                );
            }
        }

        if (completed > 0) {
            log.info(
                    "RP-08 무응답 자동 비공개 처리 완료: {}건",
                    completed
            );
        }
    }
}