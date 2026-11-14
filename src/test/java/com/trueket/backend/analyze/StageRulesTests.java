package com.trueket.backend.analyze;

import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.analyze.dto.AnalysisInput.*;
import com.trueket.backend.analyze.dto.FinalAssessment.Source;
import com.trueket.backend.analyze.service.*;
import com.trueket.backend.report.entity.enums.TradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StageRulesTests {
    private ChatStageService chat;
    private final FinalStageService finalStages = new FinalStageService();

    @BeforeEach
    void setup() throws IOException {
        chat = new ChatStageService(new SignalDictionary());
    }

    @ParameterizedTest
    @ValueSource(strings = {"S-A1", "S-A2", "S-B1", "S-B2"})
    void oneStrongSignalMakesTransferDangerous(String signal) {
        assertEquals(Stage.DANGER, assess(TradeType.TRANSFER, signal).stage());
    }

    @ParameterizedTest
    @EnumSource(value = TradeType.class, names = {"AOM", "PROXY", "CANCEL"})
    void accountTradeExcludesA1ButRetainsHighlightAndAlwaysCountsA2(TradeType type) {
        ChatAssessment informational = assess(type, "S-A1");
        assertEquals(Stage.SAFE, informational.stage());
        assertEquals(1, informational.informationalSignals().size());
        assertTrue(informational.countedSignals().isEmpty());
        assertTrue(informational.accountNoticeRequired());
        assertFalse(informational.showTrueshotOnResult());
        assertEquals(Stage.SAFE, informational.criteria().get(Criterion.A));
        assertEquals(Stage.DANGER, assess(type, "S-A1", "S-A2").stage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"S-B3", "S-C1", "S-C2", "S-C3", "S-D1", "S-E1", "S-E2", "S-F1", "S-X"})
    void aSingleCautionIsSuspicious(String signal) {
        assertEquals(Stage.SUSPECT, assess(TradeType.TRANSFER, signal).stage());
    }

    @Test
    void threeCautionsRequireTwoKnownCriteria() {
        ChatAssessment danger = assess(TradeType.TRANSFER, "S-B3", "S-D1", "S-C3");
        assertEquals(Stage.DANGER, danger.stage());
        assertEquals(Stage.SUSPECT, danger.criteria().get(Criterion.B));
        assertEquals(Stage.SUSPECT, assess(TradeType.TRANSFER, "S-C1", "S-C2", "S-C3").stage());
    }

    @Test
    void repeatedSignalsAreCountedAtMostTwiceAcrossSentences() {
        ChatAssessment result = assess(TradeType.TRANSFER, "S-C1", "S-C1", "S-C1", "S-C1");
        assertEquals(Stage.SUSPECT, result.stage());
        assertEquals(2, result.countedSignals().size());
    }

    @Test
    void unknownSignalsCannotCreateDangerWithoutTwoKnownCriteria() {
        assertEquals(Stage.SUSPECT, assess(TradeType.TRANSFER, "S-X", "S-X", "S-X").stage());
        assertEquals(Stage.SUSPECT, assess(TradeType.TRANSFER, "S-C1", "S-X", "S-X").stage());
        assertEquals(Stage.SUSPECT, assess(TradeType.TRANSFER, "S-C1", "S-C2", "S-X").stage());
        assertEquals(Stage.DANGER, assess(TradeType.TRANSFER, "S-B3", "S-D1", "S-X").stage());
        assertTrue(assess(TradeType.TRANSFER, "S-X").criteria().values().stream().allMatch(stage -> stage == Stage.SAFE));
    }

    @Test
    void nonexistentQuotesBuyerWordsAndUnrecognizedSpeakersAreNotEvidence() {
        List<Sentence> sentences = List.of(new Sentence("seller", Speaker.SELLER, "안녕하세요"),
                new Sentence("buyer", Speaker.BUYER, "인증번호 주세요"),
                new Sentence("unknown", Speaker.UNKNOWN, "인증번호 주세요"));
        List<SignalCandidate> signals = List.of(
                new SignalCandidate("S-A2", "seller", "인증번호 주세요", "근거 없음"),
                new SignalCandidate("S-A2", "buyer", "인증번호 주세요", "구매자의 말"),
                new SignalCandidate("S-A2", "unknown", "인증번호 주세요", "화자 불명"),
                new SignalCandidate("S-A2", "missing", "인증번호 주세요", "문장 없음"));
        ChatAssessment result = chat.assess(new AnalysisInput(TradeType.TRANSFER, false, sentences, signals));
        assertEquals(Stage.SAFE, result.stage());
        assertTrue(result.countedSignals().isEmpty());
    }

    @Test
    void aSentenceCountsOnceAndUsesStrengthThenCriterionPriority() {
        Sentence sentence = new Sentence("s1", Speaker.SELLER, "이 문장에 여러 요구가 있어요");
        List<SignalCandidate> signals = List.of(candidate("S-D1", sentence), candidate("S-E1", sentence),
                candidate("S-C1", sentence), candidate("S-B3", sentence), candidate("S-A2", sentence));
        ChatAssessment result = chat.assess(new AnalysisInput(TradeType.TRANSFER, false, List.of(sentence), signals));
        assertEquals(1, result.countedSignals().size());
        assertEquals("S-A2", result.countedSignals().get(0).id());
        ChatAssessment caution = chat.assess(new AnalysisInput(TradeType.TRANSFER, false, List.of(sentence),
                List.of(candidate("S-D1", sentence), candidate("S-E1", sentence))));
        assertEquals("S-E1", caution.countedSignals().get(0).id());
    }

    @Test
    void explicitA1F1MismatchExceptionIsRetained() {
        Sentence sentence = new Sentence("s1", Speaker.SELLER, "양도인데 아이디와 비번을 주세요");
        List<SignalCandidate> signals = List.of(candidate("S-A1", sentence), candidate("S-F1", sentence));
        ChatAssessment mismatch = chat.assess(new AnalysisInput(TradeType.TRANSFER, true, List.of(sentence), signals));
        assertEquals(Stage.DANGER, mismatch.stage());
        assertEquals(2, mismatch.countedSignals().size());
        ChatAssessment ordinary = chat.assess(new AnalysisInput(TradeType.TRANSFER, false, List.of(sentence), signals));
        assertEquals(1, ordinary.countedSignals().size());
    }

    @Test
    void disabledCandidateDeletedAndInventedSignalsAreIgnored() {
        ChatAssessment result = assess(TradeType.TRANSFER, "S-E3", "S-F2", "S-INVENTED");
        assertEquals(Stage.SAFE, result.stage());
        assertTrue(result.countedSignals().isEmpty());
    }

    @Test
    void unknownTradeTypeDefaultsToOtherAndDuplicateSentenceIdsAreRejected() {
        ChatAssessment result = assess(null, "S-A1");
        assertEquals(Stage.DANGER, result.stage());
        assertTrue(result.showTrueshotOnResult());
        Sentence sentence = new Sentence("same", Speaker.SELLER, "문장");
        assertThrows(IllegalArgumentException.class,
                () -> chat.assess(new AnalysisInput(null, false, List.of(sentence, sentence), List.of())));
    }

    @Test
    void reportCountsAreEvaluatedPerIdentifierWithoutAddingThem() {
        ChatAssessment safe = assess(TradeType.TRANSFER);
        ReportLookup lookup = new ReportLookup(true, List.of(new ReportLookup.Target("account", 0, 1),
                new ReportLookup.Target("phone", 0, 1)));
        FinalAssessment result = finalStages.combine(safe, lookup);
        assertEquals(Stage.SAFE, result.finalStage());
        assertEquals(Source.CHAT, result.explanationSource());
        assertFalse(result.showReportButton());
    }

    @Test
    void confirmedDamageOverridesSafeChatWithoutChangingItsCriteria() {
        ChatAssessment safe = assess(TradeType.TRANSFER);
        FinalAssessment result = finalStages.combine(safe, lookup("phone", 1, 0));
        assertEquals(Stage.DANGER, result.finalStage());
        assertTrue(result.raisedByReports());
        assertEquals(Source.REPORTS, result.explanationSource());
        assertEquals(List.of("phone"), result.stageReportIdentifiers());
        assertFalse(result.showSuspicionReport());
        assertTrue(result.showReportButton());
        assertTrue(result.trueshotSecondary());
        assertTrue(result.chat().criteria().values().stream().allMatch(stage -> stage == Stage.SAFE));
    }

    @Test
    void twoSuspicionReportsRaiseStageButOneDoesNot() {
        assertEquals(Stage.SUSPECT, finalStages.combine(assess(TradeType.TRANSFER), lookup("account", 0, 2)).finalStage());
        FinalAssessment one = finalStages.combine(assess(TradeType.TRANSFER, "S-B3"), lookup("account", 0, 1));
        assertEquals(Stage.SUSPECT, one.finalStage());
        assertEquals(Source.CHAT, one.explanationSource());
        assertTrue(one.showSuspicionReport());
    }

    @Test
    void tiesPreferReportsAndLowerReportsDoNotOverrideChat() {
        FinalAssessment tie = finalStages.combine(assess(TradeType.TRANSFER, "S-B3"), lookup("account", 0, 2));
        assertEquals(Source.REPORTS, tie.explanationSource());
        assertFalse(tie.raisedByReports());
        FinalAssessment strongerChat = finalStages.combine(assess(TradeType.TRANSFER, "S-B1"), lookup("account", 0, 2));
        assertEquals(Stage.DANGER, strongerChat.finalStage());
        assertEquals(Source.CHAT, strongerChat.explanationSource());
    }

    @Test
    void lookupFailureUsesChatAndReturnsWarning() {
        FinalAssessment result = finalStages.combine(assess(TradeType.TRANSFER, "S-B3"), new ReportLookup(false, List.of()));
        assertEquals(Stage.SUSPECT, result.finalStage());
        assertNotNull(result.lookupWarning());
        var partial = finalStages.combine(assess(TradeType.TRANSFER),
                new ReportLookup(false, List.of(new ReportLookup.Target("account", 1, 0))));
        assertEquals(Stage.DANGER, partial.finalStage());
        assertNotNull(partial.lookupWarning());
        assertThrows(IllegalArgumentException.class, () -> new ReportLookup.Target("account", -1, 0));
    }

    @Test
    void accountTransactionsKeepTrueshotHiddenEvenWhenReportsAreDangerous() {
        FinalAssessment result = finalStages.combine(assess(TradeType.AOM), lookup("account", 1, 0));
        assertTrue(result.chat().accountNoticeRequired());
        assertFalse(result.chat().showTrueshotOnResult());
        assertFalse(result.trueshotSecondary());
    }

    @Test
    void assessmentIsAnImmutableSnapshot() {
        List<ReportLookup.Target> targets = new ArrayList<>();
        targets.add(new ReportLookup.Target("account", 0, 1));
        ReportLookup lookup = new ReportLookup(true, targets);
        targets.add(new ReportLookup.Target("phone", 1, 0));
        FinalAssessment result = finalStages.combine(assess(TradeType.TRANSFER), lookup);
        assertEquals(Stage.SAFE, result.finalStage());
        assertThrows(UnsupportedOperationException.class, () -> result.chat().criteria().put(Criterion.A, Stage.DANGER));
    }

    private ChatAssessment assess(TradeType type, String... ids) {
        List<Sentence> sentences = new ArrayList<>();
        List<SignalCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < ids.length; index++) {
            Sentence sentence = new Sentence("s" + index, Speaker.SELLER, "원문 " + index);
            sentences.add(sentence);
            candidates.add(candidate(ids[index], sentence));
        }
        return chat.assess(new AnalysisInput(type, false, sentences, candidates));
    }

    private SignalCandidate candidate(String id, Sentence sentence) {
        return new SignalCandidate(id, sentence.id(), sentence.text(), "테스트 근거");
    }

    private ReportLookup lookup(String id, long damage, long suspicion) {
        return new ReportLookup(true, List.of(new ReportLookup.Target(id, damage, suspicion)));
    }
}
