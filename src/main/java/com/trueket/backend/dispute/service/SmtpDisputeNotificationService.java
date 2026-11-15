
package com.trueket.backend.dispute.service;

import com.trueket.backend.dispute.entity.Dispute;
import com.trueket.backend.dispute.entity.DisputeItem;
import com.trueket.backend.dispute.repository.DisputeItemRepository;
import com.trueket.backend.dispute.repository.DisputeRepository;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.report.repository.ReportRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
@ConditionalOnProperty(
        prefix = "dispute.mail",
        name = "enabled",
        havingValue = "true"
)
@RequiredArgsConstructor
public class SmtpDisputeNotificationService
        implements DisputeNotificationService {

    private final JavaMailSender mailSender;
    private final ReportRepository reportRepository;
    private final DisputeRepository disputeRepository;
    private final DisputeItemRepository itemRepository;

    @Value("${dispute.mail.from}")
    private String from;

    @Value("${dispute.mail.support-email:}")
    private String supportEmail;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM.dd HH:mm")
                    .withZone(ZoneId.of("Asia/Seoul"));

    private void send(
            String to,
            String subject,
            String body
    ) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "메일 발신 주소가 설정되지 않았습니다."
            );
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("[트루켓] " + subject);
        message.setText(body);

        mailSender.send(message);
    }

    @Override
    public void sendReceivedEmail(
            String sellerEmail,
            String receiptNo
    ) {
        send(
                sellerEmail,
                "이의제기가 접수되었습니다",
                """
                안녕하세요. 트루켓입니다.

                이의제기가 접수되었습니다.

                접수번호: %s

                제출하신 증빙을 확인한 뒤 결과를 안내해 드립니다.

                감사합니다.
                트루켓 드림
                """.formatted(receiptNo)
        );
    }

    @Override
    public void sendDeliveredEmail(
            String sellerEmail,
            String receiptNo,
            int reportCount,
            OffsetDateTime deadline
    ) {
        send(
                sellerEmail,
                "이의제기가 게시자에게 전달되었습니다",
                """
                안녕하세요. 트루켓입니다.

                제출하신 증빙 확인이 완료되어
                관련 신고 게시자에게 이의제기를 전달했습니다.

                접수번호: %s
                전달된 기록: %d건
                결정 기한: %s

                게시자별 처리 결과가 확정되면
                다시 이메일로 안내해 드리겠습니다.

                감사합니다.
                트루켓 드림
                """.formatted(
                        receiptNo,
                        reportCount,
                        DATE_FORMAT.format(deadline)
                )
        );
    }

    @Override
    public void sendRejectedEmail(
            String sellerEmail,
            String receiptNo
    ) {
        String contact = supportEmail == null
                || supportEmail.isBlank()
                ? "트루켓 고객지원 채널"
                : supportEmail;

        send(
                sellerEmail,
                "이의제기 증빙 확인 결과 안내",
                """
                안녕하세요. 트루켓입니다.

                제출하신 증빙으로는 확인이 어려워
                이번 이의제기가 반려되었습니다.

                접수번호: %s

                추가 확인이 필요하시면
                %s 로 문의해 주세요.

                감사합니다.
                트루켓 드림
                """.formatted(receiptNo, contact)
        );
    }

    @Override
    public void sendReporterDisputeEmail(
            Long reportId,
            Long disputeId
    ) {
        // 로그인한 신고 작성자의 이메일을 DB에서 조회
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalStateException(
                        "신고를 찾을 수 없습니다."
                ));

        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new IllegalStateException(
                        "이의제기를 찾을 수 없습니다."
                ));

        DisputeItem item = itemRepository
                .findByDispute_IdAndReport_Id(disputeId, reportId)
                .orElseThrow(() -> new IllegalStateException(
                        "이의제기 항목을 찾을 수 없습니다."
                ));

        String reporterEmail = report.getUser().getEmail();

        send(
                reporterEmail,
                "작성하신 신고에 이의제기가 접수되었습니다",
                """
                안녕하세요. 트루켓입니다.

                작성하신 신고에 이의제기가 접수되었습니다.

                신고 번호: %d
                이의 사유: %s
                응답 기한: %s

                트루켓에 로그인하여
                내 신고 상세 화면에서 내용을 확인하고
                신고 유지 또는 삭제를 선택해 주세요.

                기한 내에 응답하지 않으면
                해당 신고는 비공개 처리됩니다.

                감사합니다.
                트루켓 드림
                """.formatted(
                        reportId,
                        reasonLabel(dispute),
                        DATE_FORMAT.format(item.getDeadline())
                )
        );
    }

    @Override
    public void sendResultEmail(
            String sellerEmail,
            String receiptNo,
            int maintainedCount,
            int deletedCount,
            int privateCount
    ) {
        send(
                sellerEmail,
                "이의제기 처리 결과 안내",
                """
                안녕하세요. 트루켓입니다.

                신청하신 이의제기의 처리가 완료되었습니다.

                접수번호: %s

                신고 유지: %d건
                신고 삭제: %d건
                비공개 처리: %d건

                개인정보 보호를 위해
                신고 작성자의 정보와 신고 내용은
                제공하지 않습니다.

                감사합니다.
                트루켓 드림
                """.formatted(
                        receiptNo,
                        maintainedCount,
                        deletedCount,
                        privateCount
                )
        );
    }

    private String reasonLabel(Dispute dispute) {
        return switch (dispute.getReason()) {
            case NORMAL -> "정상 거래";
            case REFUND -> "환불 완료";
            case IMPERSONATION -> "명의 도용";
            case OTHER -> "기타";
        };
    }
}
