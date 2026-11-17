package com.trueket.backend.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.analyze.dto.AnalysisInput.*;
import com.trueket.backend.analyze.dto.ModelExtraction.*;
import com.trueket.backend.analyze.service.*;
import com.trueket.backend.report.entity.enums.TradeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationDetailsTests {
    private ChatStageService rules;
    private final ConversationDetailsService details = new ConversationDetailsService();
    private final AnalysisRedactor redactor = new AnalysisRedactor();
    @BeforeEach void setup() throws Exception { rules = new ChatStageService(new SignalDictionary()); }

    private ConversationDetails summarize(TradeType type, boolean mismatch,List<SourceSentence> sentences,List<SourceSignal> signals) {
        var model=new ModelExtraction(true,"transfer",mismatch,sentences,signals,
            new Extracted(List.of(),List.of("01012345678"),List.of(),List.of()),null,List.of("가상판매자"));
        var input=new AnalysisInput(type,mismatch,
            sentences.stream().map(s->new Sentence(s.id(),s.speaker(),s.text())).toList(),
            signals.stream().map(s->new SignalCandidate(s.id(),s.sentenceId(),s.quote(),s.reason())).toList());
        var masked=redactor.redact(rules.assess(input),model);
        return details.summarize(3,model,masked);
    }
    private SourceSentence sentence(String id,int image) { return new SourceSentence(id,Speaker.SELLER,"가상판매자 01012345678 문장 "+id,image); }
    private SourceSignal signal(String id,String sentence) { return new SourceSignal(id,sentence,"문장 "+sentence,"가상판매자 01012345678 근거"); }

    @Test void groupsAcceptedSignalsInPhotoOrderAndCountsPhotosOnce() {
        var result=summarize(TradeType.TRANSFER,false,
            List.of(sentence("third",3),sentence("first",1),sentence("also-first",1)),
            List.of(signal("S-A2","third"),signal("S-B3","also-first"),signal("S-C1","first")));
        assertEquals(3,result.imageCount());
        assertEquals(2,result.suspiciousImageCount());
        assertEquals(List.of(1,3),result.images().stream().map(ConversationDetails.ImageEvidence::imageNumber).toList());
        assertEquals(2,result.images().get(0).countedSignals().size());
    }

    @Test void onlyValidatedSignalsAreVisibleAndNoRawReasonLeaks() throws Exception {
        var buyer=new SourceSentence("buyer",Speaker.BUYER,"문장 buyer",2);
        var result=summarize(TradeType.TRANSFER,false,List.of(sentence("seller",1),buyer),
            List.of(signal("S-A2","seller"),signal("S-A2","buyer"),signal("UNKNOWN","seller"),
                new SourceSignal("S-B2","seller","이미지에 없는 말","없는 근거")));
        assertEquals(1,result.suspiciousImageCount());
        assertEquals(1,result.images().get(0).countedSignals().size());
        String json=new ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("01012345678"));
        assertFalse(json.contains("가상판매자"));
        assertFalse(json.contains("이미지에 없는 말"));
        assertTrue(json.contains("***"));
    }

    @Test void accountTradeA1IsHighlightedButDoesNotIncreaseSuspiciousPhotoCount() {
        var result=summarize(TradeType.AOM,false,List.of(sentence("a1",2)),List.of(signal("S-A1","a1")));
        assertEquals(0,result.suspiciousImageCount());
        assertEquals(1,result.images().size());
        assertEquals(2,result.images().get(0).imageNumber());
        assertTrue(result.images().get(0).countedSignals().isEmpty());
        assertEquals("S-A1",result.images().get(0).informationalSignals().get(0).id());
    }

    @Test void typeMismatchExceptionKeepsTwoSignalsButCountsOnePhoto() {
        var result=summarize(TradeType.TRANSFER,true,List.of(sentence("mismatch",1)),
            List.of(signal("S-A1","mismatch"),signal("S-F1","mismatch")));
        assertEquals(1,result.suspiciousImageCount());
        assertEquals(2,result.images().get(0).countedSignals().size());
    }

    @Test void signalCapAndEmptyResultsFollowServerRules() {
        var result=summarize(TradeType.TRANSFER,false,List.of(sentence("one",1),sentence("two",2),sentence("three",3)),
            List.of(signal("S-C1","one"),signal("S-C1","two"),signal("S-C1","three")));
        assertEquals(2,result.suspiciousImageCount());
        var empty=summarize(TradeType.TRANSFER,false,List.of(sentence("ordinary",2)),List.of());
        assertEquals(3,empty.imageCount());
        assertEquals(0,empty.suspiciousImageCount());
        assertTrue(empty.images().isEmpty());
    }
}
