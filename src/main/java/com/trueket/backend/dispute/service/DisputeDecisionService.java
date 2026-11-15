
package com.trueket.backend.dispute.service;

import com.trueket.backend.dispute.dto.DisputeDecisionResponse;
import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.entity.enums.DisputeItemStatus;
import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.service.ReportService;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class DisputeDecisionService {

    private final DisputeItemRepository itemRepository;
    private final ReportService reportService;
    private final EntityManager entityManager;

    @Transactional
    public DisputeDecisionResponse decide(
            Long userId,
            Long disputeId,
            Long reportId,
            DisputeItemStatus decision
    ) {
        // 1. 유지 또는 삭제만 허용
        if (decision != DisputeItemStatus.MAINTAINED
                && decision != DisputeItemStatus.DELETED) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "유지 또는 삭제만 선택할 수 있습니다."
            );
        }

        // 2. 이의제기 처리 항목 조회 + 행 잠금
        DisputeItem item = itemRepository.findForDecision(
                disputeId,
                reportId
        ).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "이의제기 대상 신고를 찾을 수 없습니다."
        ));

        // 3. 본인 신고인지 확인
        if (!item.getReport().getUser().getId().equals(userId)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "이의제기 대상 신고를 찾을 수 없습니다."
            );
        }

        // 4. 응답 가능한 상태인지 확인
        if (item.getStatus() != DisputeItemStatus.RESPONDING) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이미 처리된 이의제기입니다."
            );
        }

        // 5. 3일 기한 확인
        if (!OffsetDateTime.now().isBefore(item.getDeadline())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "이의제기 응답 기한이 지났습니다."
            );
        }

        // 6. 기존 Entity 메서드로 결정
        item.decide(decision);

        if (decision == DisputeItemStatus.MAINTAINED) {
            return new DisputeDecisionResponse(
                    disputeId,
                    reportId,
                    "MAINTAINED",
                    "신고 유지가 완료되었습니다."
            );
        }

        // 8. 기존 RP-07 삭제 로직 재사용
        // 이 과정에서 원본 신고와 연결 데이터가 삭제됨
        reportService.deleteMyReport(userId, reportId);

        return new DisputeDecisionResponse(
                disputeId,
                reportId,
                "DELETED",
                "신고 삭제가 완료되었습니다."
        );
    }
}
