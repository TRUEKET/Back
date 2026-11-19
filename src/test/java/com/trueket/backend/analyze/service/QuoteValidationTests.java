package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.analyze.dto.AnalysisInput.*;
import com.trueket.backend.report.entity.enums.TradeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QuoteValidationTests {
    @ParameterizedTest
    @CsvSource(delimiter='|', textBlock="""
        인증 번호를 알려 주세요! | 인증번호를 알려주세요
        ‘문자로 온 번호’… 알려주세요~ | 문자로온번호 알려주세요
        😀 인증 번호 알려주세요 | 인증번호 알려주세요
        인증　번호를 알려 주세요 | 인증번호를 알려주세요
        """)
    void formattingVariationsUseActualSourceSpan(String source, String candidate) throws Exception {
        String original=QuoteMatcher.findOriginal(source,candidate).orElseThrow();
        assertTrue(source.contains(original));
        var result=assess(source,candidate,Speaker.SELLER);
        assertEquals(Stage.DANGER,result.stage());
        assertEquals(original,result.countedSignals().get(0).quote());
    }

    @Test void tabsAndLineBreaksUseOriginalSpan() throws Exception {
        String source="인증\t번호를\n알려 주세요";
        String quote=QuoteMatcher.findOriginal(source,"인증번호를 알려주세요").orElseThrow();
        assertEquals(source,quote);
        assertEquals(Stage.DANGER,assess(source,"인증번호를 알려주세요",Speaker.SELLER).stage());
    }

    @Test void exactQuotesRemainUnchanged() {
        assertEquals("인증번호 주세요!",QuoteMatcher.findOriginal("문자로 온 인증번호 주세요!","인증번호 주세요!").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings={"인증번호를 알려주세요", "번호 알려주세요", "주시면 됩니다", "...!? ‘ ’"})
    void existingSellerIdCannotRescueUnmatchedOrPunctuationOnlyQuote(String candidate) throws Exception {
        var result=assess("인증번호는 절대 알려주지 마세요",candidate,Speaker.SELLER);
        assertEquals(Stage.SAFE,result.stage());assertTrue(result.countedSignals().isEmpty());
    }

    @Test void ellipsisDoesNotAllowOmittedWordsOrChangedNumbers() {
        assertTrue(QuoteMatcher.findOriginal("먼저 티켓값을 전부 입금해 주세요","먼저…입금해 주세요").isEmpty());
        assertTrue(QuoteMatcher.findOriginal("수수료는 1.5만원","수수료는 15만원").isEmpty());
        assertTrue(QuoteMatcher.findOriginal("수수료는 1 . 5만원","수수료는 15만원").isEmpty());
        assertTrue(QuoteMatcher.findOriginal("계좌 123-456","계좌 123-457").isEmpty());
        assertTrue(QuoteMatcher.findOriginal("절대 보내지 마세요","보내세요").isEmpty());
    }

    @Test void buyerAndUnknownRemainExcludedAfterNormalization() throws Exception {
        for(var speaker:List.of(Speaker.BUYER,Speaker.UNKNOWN)) {
            var result=assess("인증 번호 주세요!","인증번호 주세요",speaker);
            assertEquals(Stage.SAFE,result.stage());assertTrue(result.countedSignals().isEmpty());
        }
    }

    @Test void missingSentenceIsStillRejected() throws Exception {
        var result=new ChatStageService(new SignalDictionary()).assess(new AnalysisInput(TradeType.TRANSFER,false,
                List.of(new Sentence("actual",Speaker.SELLER,"인증 번호 주세요!")),
                List.of(new SignalCandidate("S-A2","invented","인증번호 주세요","요구"))));
        assertTrue(result.countedSignals().isEmpty());
    }

    @Test void accountTradeExemptionStillAppliesToNormalizedQuote() throws Exception {
        var result=new ChatStageService(new SignalDictionary()).assess(new AnalysisInput(TradeType.AOM,false,
                List.of(new Sentence("s",Speaker.SELLER,"아이디, 비번 주세요!")),
                List.of(new SignalCandidate("S-A1","s","아이디 비번주세요","계정 요구"))));
        assertEquals(Stage.SAFE,result.stage());assertEquals(1,result.informationalSignals().size());
    }

    private ChatAssessment assess(String source,String quote,Speaker speaker) throws Exception {
        return new ChatStageService(new SignalDictionary()).assess(new AnalysisInput(TradeType.TRANSFER,false,
                List.of(new Sentence("s",speaker,source)),
                List.of(new SignalCandidate("S-A2","s",quote,"인증번호 요구"))));
    }
}
