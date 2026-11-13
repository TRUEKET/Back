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

import java.util.UUID;
import java.util.stream.Stream;

@Service
public class AnalysisReportSourceService {
    private final AnalysisSessionStore sessions;
    private final UserRepository users;
    private final ChatStageService chat;
    private final AnalysisRedactor redactor;

    public AnalysisReportSourceService(AnalysisSessionStore sessions, UserRepository users,
                                       ChatStageService chat, AnalysisRedactor redactor) {
        this.sessions = sessions;
        this.users = users;
        this.chat = chat;
        this.redactor = redactor;
    }

    /** Call from the authenticated report flow using request.getSession(false). No raw photo is returned. */
    public AnalysisReportSource load(UUID analysisId, HttpSession session) {
        long userId = activeUser(session);
        Object owner = session.getAttribute("analysisOwner");
        if (analysisId == null || !(owner instanceof UUID)) throw expired();
        var extraction = sessions.get(analysisId, (UUID) owner).extraction();
        if (!extraction.readable()) throw expired();
        TradeType type = switch (extraction.detectedType()) {
            case "transfer" -> TradeType.TRANSFER;
            case "aom" -> TradeType.AOM;
            case "proxy" -> TradeType.PROXY;
            case "cancel" -> TradeType.CANCEL;
            default -> TradeType.OTHER;
        };
        var sentences = extraction.sentences().stream().map(s -> new AnalysisInput.Sentence(
                s.id(), s.speaker(), s.text())).toList();
        var signals = extraction.signals().stream().map(s -> new AnalysisInput.SignalCandidate(
                s.id(), s.sentenceId(), s.quote(), s.reason())).toList();
        var assessment = redactor.redact(chat.assess(new AnalysisInput(type,
                extraction.typeMismatch(), sentences, signals)), extraction);
        var extracted = extraction.extracted();
        return new AnalysisReportSource(analysisId, userId, type,
                new AnalysisReportSource.Identifiers(extracted.accounts(), extracted.phones(), extracted.xAccounts()),
                Stream.concat(assessment.countedSignals().stream(), assessment.informationalSignals().stream()).toList());
    }

    private long activeUser(HttpSession session) {
        if (session == null) throw unauthorized();
        Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(value instanceof SecurityContext context)) throw unauthorized();
        var authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) throw unauthorized();
        long id;
        try { id = Long.parseLong(authentication.getName()); }
        catch (NumberFormatException e) { throw unauthorized(); }
        if (!users.existsByIdAndStatus(id, "ACTIVE")) throw unauthorized();
        return id;
    }
    private ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."); }
    private ApiException expired() { return new ApiException(HttpStatus.NOT_FOUND, "판독 세션이 끝났어요. 다시 판독해 주세요"); }
}
