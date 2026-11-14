package com.trueket.backend.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.analyze.dto.*;
import com.trueket.backend.analyze.dto.ModelExtraction.Extracted;
import com.trueket.backend.analyze.service.*;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.identifier.util.*;
import com.trueket.backend.report.entity.*;
import com.trueket.backend.report.entity.enums.*;
import com.trueket.backend.report.repository.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:analysislookup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@Transactional
class AnalysisReportLookupTests {
    @Autowired AnalysisReportLookup lookup;
    @Autowired IdentifierRepository identifiers;
    @Autowired IdentifierHashService hashes;
    @Autowired ReportRepository reports;
    @Autowired ReportIdentifierRepository links;
    @Autowired UserRepository users;
    @Autowired ChatStageService chat;
    @Autowired AnalysisPrompt prompt;

    Identifier identifier(String input) {
        var n=IdentifierNormalizer.normalize(input);
        return identifiers.saveAndFlush(Identifier.create(n.type(),hashes.hash(n.type(),n.value()),"***","encrypted"));
    }
    void report(Identifier target,String person,ReportKind kind) {
        var u=users.saveAndFlush(User.create(UUID.randomUUID()+"@test.invalid","hash",UUID.randomUUID().toString(),
                "encrypted","1234",OffsetDateTime.now()));
        var json=new ObjectMapper();
        var r=reports.saveAndFlush(Report.create(u,person,kind,TradeType.TRANSFER,
                json.createArrayNode(),json.createArrayNode(),null));
        links.saveAndFlush(ReportIdentifier.create(r,target));
    }
    Extracted extracted(List<String> accounts,List<String> phones,List<String> sns) {
        return new Extracted(accounts,phones,sns,List.of());
    }
    @Test void aliasesFormattingAndCaseMatchExactTypeAndHashWithoutDuplicates() {
        var account=identifier("국민 123456789012");
        var phone=identifier("010-1234-5678");
        var sns=identifier("@Trueket_User");
        report(account,"a",ReportKind.FRAUD);
        report(phone,"b",ReportKind.SUSPICION);
        report(sns,"c",ReportKind.SUSPICION);
        var result=lookup.lookup(extracted(List.of("KB 123-456-789012","국민 123456789012"),
                List.of("010 1234 5678"),List.of("TRUEKET_USER","@trueket_user")));
        assertTrue(result.available());
        assertEquals(3,result.targets().size());
        assertEquals(1,result.targets().stream().filter(t->t.damagePeople()==1).count());
        assertEquals(Set.of(account.getId().toString(),phone.getId().toString(),sns.getId().toString()),
                new HashSet<>(result.targets().stream().map(ReportLookup.Target::identifierId).toList()));
    }
    @Test void noMatchIsSuccessfulZeroButDifferentBankAndSuffixNeverMatch() {
        identifier("국민 123456789012");
        var result=lookup.lookup(extracted(List.of("신한 123456789012","국민 999956789012"),List.of(),List.of()));
        assertTrue(result.available());assertTrue(result.targets().isEmpty());
        assertTrue(lookup.lookup(extracted(List.of(),List.of(),List.of())).available());
    }
    @Test void malformedAndWrongTypeRemainIncompleteButKnownDangerIsPreserved() {
        var phone=identifier("01012345678");report(phone,"person",ReportKind.FRAUD);
        var result=lookup.lookup(extracted(List.of("123456789012","01012345678"),
                List.of("010-1234-5678"),List.of("https://x.com/someone")));
        assertFalse(result.available());assertEquals(1,result.targets().size());
        assertEquals(Stage.DANGER,result.targets().get(0).stage());
    }
    @Test void unknownDatabaseFailureIsNotReportedAsSuccessfulSafeLookup() {
        var repository=mock(IdentifierRepository.class);
        var counts=mock(PublicReportCounts.class);
        when(repository.findByTypeAndValueHash(any(),anyString()))
                .thenThrow(new DataAccessResourceFailureException("synthetic failure"));
        var result=new AnalysisReportLookup(repository,hashes,counts)
                .lookup(extracted(List.of(),List.of("01012345678"),List.of()));
        assertFalse(result.available());assertTrue(result.targets().isEmpty());
        verifyNoInteractions(counts);
    }
    @Test void countsFailureAlsoReturnsIncompleteLookup() {
        var repository=mock(IdentifierRepository.class);
        var counts=mock(PublicReportCounts.class);
        when(repository.findByTypeAndValueHash(any(),anyString())).thenReturn(Optional.empty());
        when(counts.count(any(),any())).thenThrow(new DataAccessResourceFailureException("synthetic failure"));
        assertFalse(new AnalysisReportLookup(repository,hashes,counts)
                .lookup(extracted(List.of(),List.of("01012345678"),List.of())).available());
    }
    @Test void differentIdentifiersDoNotCombineSuspicionPeople() {
        var a=identifier("@first_user");var b=identifier("@second_user");
        report(a,"person1",ReportKind.SUSPICION);report(b,"person2",ReportKind.SUSPICION);
        var result=lookup.lookup(extracted(List.of(),List.of(),List.of("first_user","second_user")));
        assertTrue(result.targets().stream().allMatch(t->t.stage()==Stage.SAFE));
    }
    @Test void analysisPipelineRaisesSafeChatFromStoredReportAndMasksPhone() throws Exception {
        var phone=identifier("01012345678");report(phone,"fraud-reporter",ReportKind.FRAUD);
        var output="""
                {"readable":true,"detected_type":"transfer","type_mismatch":false,
                 "sentences":[{"id":"s1","speaker":"SELLER","text":"공식 앱에서 확인해 주세요 01012345678","image_index":1}],
                 "signals":[],"extracted":{"accounts":[],"phones":["01012345678"],"x_accounts":[],"links":[]},
                 "other_suspicious":null,"sensitive_values":["01012345678"]}
                """;
        var sessions=new AnalysisSessionStore();
        UUID owner=UUID.randomUUID();
        var service=new AnalyzeService(new CaptureProcessor(),prompt,(p,i,s,t)->output,
                new ModelExtractionParser(),chat,new FinalStageService(),lookup,new AnalysisRedactor(),
                sessions,new ConversationDetailsService(),Duration.ofSeconds(2));
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var response=service.analyze(List.of(new MockMultipartFile("captures","synthetic.png","image/png",bytes.toByteArray())),"",owner);
        assertEquals(Stage.SAFE,response.assessment().chat().stage());
        assertEquals(Stage.DANGER,response.assessment().finalStage());
        assertTrue(response.assessment().raisedByReports());
        var snapshot=sessions.get(response.analyzeId(),owner);
        assertTrue(snapshot.showReportButton());
        assertFalse(snapshot.showSuspicionReport());
        assertNull(response.assessment().lookupWarning());
        assertFalse(new ObjectMapper().writeValueAsString(response).contains("01012345678"));
    }
}
