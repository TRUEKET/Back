package com.trueket.backend.dispute.service;

import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.repository.DisputeItemRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class DisputeExpirationService {

    private final DisputeItemRepository itemRepository;

    @Transactional
    public boolean expireOne(Long itemId) {

        // 게시자 결정과 충돌하지 않도록 행 잠금
        DisputeItem item = itemRepository
                .findByIdForUpdate(itemId)
                .orElse(null);

        if (item == null) {
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();

        // 이미 결정됐거나 아직 기한이 남은 경우 무시
        if (item.getStatus() != DisputeItemStatus.RESPONDING
                || now.isBefore(item.getDeadline())) {
            return false;
        }

        // 신고별 비공개 처리
        item.expireWithoutResponse(now);

        return true;
    }
}