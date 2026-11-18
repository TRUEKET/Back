
package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.entity.enums.TradeType;
import com.trueket.backend.user.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class AnalysisReportSourceService {

    private final AnalysisSessionStore sessions;
    private final UserRepository users;
    private final ChatStageService chat;
    private final AnalysisRedactor redactor;

    // 신규 의존성
    private final ConversationDetailsService conversations;
    private final FraudCaseDictionary fraudCases;

    public AnalysisReportSourceService(
            AnalysisSessionStore sessions,
            UserRepository users,
            ChatStageService chat,
            AnalysisRedactor redactor,
            ConversationDetailsService conversations,
            FraudCaseDictionary fraudCases
    ) {
        this.sessions = sessions;
        this.users = users;
        this.chat = chat;
        this.redactor = redactor;
        this.conversations = conversations;
        this.fraudCases = fraudCases;
    }

    /**
     * 로그인된 사용자의 판독 결과를 신고 등록에 전달한다.
     * 대화 원본 사진은 반환하지 않는다.
     */
    public AnalysisReportSource load(
            UUID analysisId,
            HttpSession session
    ) {

        // 1. 로그인 사용자 검증
        long userId = activeUser(session);

        // 2. 판독 세션 소유자 검증
        Object owner = session.getAttribute("analysisOwner");

        if (analysisId == null || !(owner instanceof UUID)) {
            throw expired();
        }

        var snapshot = sessions.get(
                analysisId,
                (UUID) owner
        );

        var extraction = snapshot.extraction();

        if (!extraction.readable()) {
            throw expired();
        }

        // 3. 거래 유형 확인
        TradeType type = switch (extraction.detectedType()) {
            case "transfer" -> TradeType.TRANSFER;
            case "aom" -> TradeType.AOM;
            case "proxy" -> TradeType.PROXY;
            case "cancel" -> TradeType.CANCEL;
            default -> TradeType.OTHER;
        };

        // 4. 기존 판독 입력 생성
        var sentences = extraction.sentences()
                .stream()
                .map(s -> new AnalysisInput.Sentence(
                        s.id(),
                        s.speaker(),
                        s.text()
                ))
                .toList();

        var signals = extraction.signals()
                .stream()
                .map(s -> new AnalysisInput.SignalCandidate(
                        s.id(),
                        s.sentenceId(),
                        s.quote(),
                        s.reason()
                ))
                .toList();

        // 5. 판독 결과 생성 및 개인정보 마스킹
        var assessment = redactor.redact(
                chat.assess(new AnalysisInput(
                        type,
                        extraction.typeMismatch(),
                        sentences,
                        signals
                )),
                extraction
        );

        // 6. 기존 근거 말풍선 데이터
        List<ChatAssessment.Evidence> evidence =
                Stream.concat(
                        assessment.countedSignals().stream(),
                        assessment.informationalSignals().stream()
                ).toList();

        // 7. 사진별 판독 결과 생성
        ConversationDetails conversationDetails =
                conversations.summarize(
                        snapshot.captures().size(),
                        extraction,
                        assessment
                );

        // 8. 주요 대화 상세 데이터 생성
        List<ReportEvidenceDetail> evidenceDetails =
                buildEvidenceDetails(conversationDetails);

        // 9. 식별자 정보 추출
        var extracted = extraction.extracted();

        // 10. 신고 등록에 필요한 데이터 반환
        return new AnalysisReportSource(
                analysisId,
                userId,
                type,
                new AnalysisReportSource.Identifiers(
                        extracted.accounts(),
                        extracted.phones(),
                        extracted.xAccounts()
                ),
                evidence,
                evidenceDetails,
                snapshot.showReportButton(),
                snapshot.showSuspicionReport()
        );
    }

    /**
     * 사진별 판독 결과와 실제 사례를 연결한다.
     */
    private List<ReportEvidenceDetail> buildEvidenceDetails(
            ConversationDetails conversationDetails
    ) {
        List<ReportEvidenceDetail> result =
                new ArrayList<>();

        for (var image : conversationDetails.images()) {

            // 위험도 판단에 포함된 신호
            for (var signal : image.countedSignals()) {
                result.add(toEvidenceDetail(
                        image.imageNumber(),
                        signal,
                        false
                ));
            }

            // 위험도 판단에 포함되지 않은 참고 신호
            for (var signal : image.informationalSignals()) {
                result.add(toEvidenceDetail(
                        image.imageNumber(),
                        signal,
                        true
                ));
            }
        }

        return List.copyOf(result);
    }

    /**
     * 개별 신호를 주요 대화 DTO로 변환한다.
     */
    private ReportEvidenceDetail toEvidenceDetail(
            int imageIndex,
            ChatAssessment.Evidence signal,
            boolean informational
    ) {
        return new ReportEvidenceDetail(
                imageIndex,
                signal.id(),
                signal.quote(),
                signal.strength(),
                resolveAction(signal.id()),
                signal.reason(),
                informational,
                fraudCases.findBySignalId(signal.id())
        );
    }

    /**
     * 신호 ID에 해당하는 사용자 화면용 행동 문구.
     */
    private String resolveAction(String signalId) {
        return switch (signalId) {
            case "S-A1" -> "계정 정보 요구";
            case "S-A2" -> "인증번호 요구";

            case "S-B1" -> "추가 송금 요구";
            case "S-B2" -> "외부 결제 링크 유도";
            case "S-B3" -> "수상한 송금 요구";

            case "S-C1" -> "캡처에만 의존한 인증";
            case "S-C2" -> "외부 인증 사이트 유도";
            case "S-C3" -> "개인 메신저 연락 유도";

            case "S-D1" -> "거래 시간 압박";

            case "S-E1" -> "개인 계좌 선입금 요구";
            case "S-E2" -> "과도한 가격 요구";
            case "S-E3" -> "특정 업체 이용 요구";

            case "S-F1" -> "거래 방식 변경";
            case "S-F2" -> "예매자와 계좌 명의 불일치";

            default -> "기타 수상한 요구";
        };
    }

    private long activeUser(HttpSession session) {

        if (session == null) {
            throw unauthorized();
        }

        Object value = session.getAttribute(
                HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY
        );

        if (!(value instanceof SecurityContext context)) {
            throw unauthorized();
        }

        var authentication = context.getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof
                AnonymousAuthenticationToken) {
            throw unauthorized();
        }

        long id;

        try {
            id = Long.parseLong(authentication.getName());
        } catch (NumberFormatException e) {
            throw unauthorized();
        }

        if (!users.existsByIdAndStatus(id, "ACTIVE")) {
            throw unauthorized();
        }

        return id;
    }

    private ApiException unauthorized() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "로그인이 필요합니다."
        );
    }

    private ApiException expired() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "판독 세션이 끝났어요. 다시 판독해 주세요"
        );
    }
}
