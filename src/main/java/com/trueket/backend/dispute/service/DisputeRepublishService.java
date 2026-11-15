package com.trueket.backend.dispute.service;

import com.trueket.backend.dispute.dto.DisputeDecisionResponse;
import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.global.exception.ApiException;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DisputeRepublishService {

    private final DisputeItemRepository itemRepository;

    @Transactional
    public DisputeDecisionResponse republish(
            Long userId,
            Long disputeId,
            Long reportId
    ) {
        // 1. 이의제기 항목 조회 + 행 잠금
        DisputeItem item = itemRepository
                .findForDecision(disputeId, reportId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "이의제기 대상 신고를 찾을 수 없습니다."
                ));

        // 2. 신고 작성자 본인인지 검사
        if (!item.getReport().getUser().getId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "이의제기 대상 신고를 찾을 수 없습니다."
            );
        }

        // 3. 무응답으로 비공개된 신고만 허용
        if (item.getStatus() != DisputeItemStatus.PRIVATE) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "비공개 상태의 신고만 재게시할 수 있습니다."
            );
        }

        // 4. 재게시 1회 제한
        if (item.isRepublished()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이미 재게시한 신고입니다."
            );
        }

        // 5. 신고 자체가 PRIVATE인지 확인
        if (item.getReport().getStatus()
                != com.trueket.backend.report.entity.enums.ReportStatus.PRIVATE) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "현재 신고 상태에서는 재게시할 수 없습니다."
            );
        }

        // 6. 기존 Entity 메서드 사용
        item.republish();

        return new DisputeDecisionResponse(
                disputeId,
                reportId,
                "MAINTAINED",
                "신고가 다시 공개되었습니다."
        );
    }
}