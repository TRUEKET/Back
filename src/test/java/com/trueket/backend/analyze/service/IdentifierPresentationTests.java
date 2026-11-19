package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ModelExtraction;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class IdentifierPresentationTests {
    private final AnalysisRedactor redactor=new AnalysisRedactor();
    private ModelExtraction input(List<String> accounts,List<String> phones) {
        return new ModelExtraction(true,"transfer",false,List.of(),List.of(),
                new ModelExtraction.Extracted(accounts,phones,List.of("@seller"),List.of()),null,List.of());
    }

    @Test void accountAndPhoneKeepTypeSpecificFormattingAndInputOrder() {
        var result=input(List.of("신한은행 110-22-123456","국민 123456789012"),List.of("01012345678","010-9876-1234"));
        var values=redactor.identifiers(result);
        assertEquals(List.of("신한 110-22-******","KB 123-456-******","010-****-5678","010-****-1234","***ller"),
                values.stream().map(v->v.value()).toList());
        assertEquals(List.of("ACCOUNT","ACCOUNT","PHONE","PHONE","SNS"),values.stream().map(v->v.type()).toList());
        assertEquals("신한은행 110-22-123456",result.extracted().accounts().get(0));
    }

    @Test void malformedIdentifiersStillHideRawPrefixes() {
        var values=redactor.identifiers(input(List.of("unknown-bank 12345678"),List.of("not-a-phone-1234")));
        assertEquals("***5678",values.get(0).value());assertEquals("***1234",values.get(1).value());
    }

    @Test void explicitFieldsCompleteMissingTypeWithoutDuplicatingAliases() {
        var result=SupplementalIdentifiers.merge(input(List.of(),List.of("01012345678")),
                "판매자 계좌: 신한은행 110-22-123456\n판매자 전화번호: 010-1234-5678");
        assertEquals(List.of("신한은행 110-22-123456"),result.extracted().accounts());
        assertEquals(List.of("01012345678"),result.extracted().phones());
        assertEquals(input(List.of(),List.of()).signals(),result.signals());
        var merged=SupplementalIdentifiers.merge(result,"계좌: 신한 11022123456\n전화번호: 01012345678");
        assertEquals(1,merged.extracted().accounts().size());assertEquals(1,merged.extracted().phones().size());
        String privateText=redactor.text("신한은행 110-22-123456 / 110-22-123456 / 11022123456 / 010-1234-5678 / 01012345678",result);
        assertFalse(privateText.contains("123456"));assertFalse(privateText.contains("5678"));
    }

    @Test void commaSeparatedExplicitFieldsAreAlsoIndependent() {
        var result=SupplementalIdentifiers.merge(input(List.of(),List.of()),
                "판매자 계좌: 신한 110-22-123456, 판매자 전화번호: 010-1234-5678");
        assertEquals(1,result.extracted().accounts().size());assertEquals(1,result.extracted().phones().size());
    }

    @Test void supplementsNeverGuessMaskedBanklessBuyerOrFreeTextValues() {
        var result=SupplementalIdentifiers.merge(input(List.of(),List.of()),
                "구매자 계좌: 신한 110-22-123456\n구매자 전화번호: 010-1234-5678\n계좌: 110-22-123456\n판매자 계좌: 신한 110-22-******\n전화번호: 010-****-5678\n그냥 번호 01012345678");
        assertTrue(result.extracted().accounts().isEmpty());assertTrue(result.extracted().phones().isEmpty());
    }

    @Test void unreadableDoesNotBecomeAResultAndPerTypeLimitRemainsTen() {
        var unreadable=new ModelExtraction(false,"etc",false,List.of(),List.of(),
                new ModelExtraction.Extracted(List.of(),List.of(),List.of(),List.of()),null,List.of());
        assertSame(unreadable,SupplementalIdentifiers.merge(unreadable,"계좌: 신한 11022123456"));
        var accounts=IntStream.range(0,10).mapToObj(i->"신한 1102212345"+i).toList();
        var result=SupplementalIdentifiers.merge(input(accounts,List.of()),"계좌: 국민 123456789012\n전화번호: 01012345678");
        assertEquals(accounts,result.extracted().accounts());assertEquals(List.of("01012345678"),result.extracted().phones());
    }
}
