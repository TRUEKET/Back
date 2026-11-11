package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.global.ai.GeminiClient;
import com.trueket.backend.global.ai.GeminiClient.Image;
import com.trueket.backend.global.ai.GeminiException;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.report.entity.enums.TradeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class AnalyzeService {
    private final CaptureProcessor captures;
    private final AnalysisPrompt prompt;
    private final GeminiClient gemini;
    private final ModelExtractionParser parser;
    private final ChatStageService chat;
    private final FinalStageService stages;
    private final AnalysisReportLookup reports;
    private final AnalysisRedactor redactor;
    private final AnalysisSessionStore sessions;
    private final Duration timeout;

    public AnalyzeService(CaptureProcessor captures, AnalysisPrompt prompt, GeminiClient gemini,
                          ModelExtractionParser parser, ChatStageService chat, FinalStageService stages,
                          AnalysisReportLookup reports, AnalysisRedactor redactor, AnalysisSessionStore sessions,
                          @Value("${analysis.model-timeout:15s}") Duration timeout) {
        this.captures = captures;
        this.prompt = prompt;
        this.gemini = gemini;
        this.parser = parser;
        this.chat = chat;
        this.stages = stages;
        this.reports = reports;
        this.redactor = redactor;
        this.sessions = sessions;
        this.timeout = timeout;
    }

    public AnalyzeResponse analyze(List<MultipartFile> uploads, String additionalInfo, UUID owner) {
        List<Image> images = captures.prepare(uploads, additionalInfo);
        ModelExtraction result = extract(images, additionalInfo);
        if (!result.readable()) {
            return new AnalyzeResponse(null, false, null, null, List.of(), null,
                    "대화를 읽지 못했어요. 글자가 잘 보이게 다시 캡처해 주세요");
        }
        TradeType type = switch (result.detectedType()) {
            case "transfer" -> TradeType.TRANSFER;
            case "aom" -> TradeType.AOM;
            case "proxy" -> TradeType.PROXY;
            case "cancel" -> TradeType.CANCEL;
            default -> TradeType.OTHER;
        };
        var sentences = result.sentences().stream().map(sentence -> new AnalysisInput.Sentence(
                sentence.id(), sentence.speaker(), sentence.text())).toList();
        var signals = result.signals().stream().map(signal -> new AnalysisInput.SignalCandidate(
                signal.id(), signal.sentenceId(), signal.quote(), signal.reason())).toList();
        var assessment = chat.assess(new AnalysisInput(type, result.typeMismatch(), sentences, signals));
        FinalAssessment finalAssessment = stages.combine(redactor.redact(assessment, result), reports.lookup(result.extracted()));
        UUID id = sessions.save(owner, new AnalysisSessionStore.Snapshot(result, images));
        return new AnalyzeResponse(id, true, type, finalAssessment, redactor.identifiers(result),
                redactor.text(result.otherSuspicious(), result), null);
    }

    private ModelExtraction extract(List<Image> images, String additionalInfo) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return parser.parse(gemini.generate(prompt.text(additionalInfo), images, prompt.schema(), timeout), images.size());
            } catch (GeminiException e) {
                if (attempt == 0 && e.retryable() && !Thread.currentThread().isInterrupted()) continue;
                HttpStatus status = switch (e.kind()) {
                    case CONFIGURATION, AUTHENTICATION -> HttpStatus.SERVICE_UNAVAILABLE;
                    case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
                    case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                    default -> HttpStatus.BAD_GATEWAY;
                };
                throw new ApiException(status, "지금은 판독하지 못했어요. 잠시 후 다시 시도해 주세요");
            }
        }
        throw new IllegalStateException("판독 재시도 처리에 실패했습니다.");
    }
}
