package com.trueket.backend.analyze;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trueket.backend.analyze.service.PublicReportCounts;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.report.entity.*;
import com.trueket.backend.report.entity.enums.*;
import com.trueket.backend.report.repository.*;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:reportcounts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@Transactional
class PublicReportCountsTests {
    @Autowired PublicReportCounts counts;
    @Autowired UserRepository users;
    @Autowired IdentifierRepository identifiers;
    @Autowired ReportRepository reports;
    @Autowired ReportIdentifierRepository links;
    @Autowired JdbcTemplate jdbc;
    final OffsetDateTime now = OffsetDateTime.parse("2026-11-13T12:00:00Z");
    User user;
    Identifier a, b;

    @BeforeEach void setup() {
        user = users.saveAndFlush(User.create(UUID.randomUUID()+"@test.invalid", "hash",
                UUID.randomUUID().toString(), "encrypted", "1234", now));
        a = identifier(); b = identifier();
    }
    Identifier identifier() {
        return identifiers.saveAndFlush(Identifier.create(IdentifierType.ACCOUNT,
                UUID.randomUUID().toString(), "***", "encrypted"));
    }
    void report(Identifier target, String reporter, ReportKind kind, ReportStatus status,
                OffsetDateTime created, boolean hidden) {
        var r = Report.create(user, reporter, kind, TradeType.values()[0],
                new ObjectMapper().createArrayNode(), new ObjectMapper().createArrayNode(), null);
        r.changeStatus(status);
        r = reports.saveAndFlush(r);
        var link = ReportIdentifier.create(r, target);
        if (hidden) link.hide();
        links.saveAndFlush(link);
        jdbc.update("UPDATE reports SET created_at = ? WHERE id = ?", created, r.getId());
    }
    @Test void countsDistinctPeoplePerIdentifierAndKind() {
        report(a,"person1",ReportKind.FRAUD,ReportStatus.PUBLISHED,now,false);
        report(a,"person1",ReportKind.FRAUD,ReportStatus.PUBLISHED,now,false);
        report(a,"person1",ReportKind.SUSPICION,ReportStatus.PUBLISHED,now,false);
        report(a,"person2",ReportKind.SUSPICION,ReportStatus.PUBLISHED,now,false);
        report(b,"person3",ReportKind.SUSPICION,ReportStatus.PUBLISHED,now,false);
        var result = counts.count(List.of(b.getId(),a.getId(),a.getId()),now);
        assertEquals(2,result.size());
        assertEquals(1,result.get(0).damagePeople());
        assertEquals(2,result.get(0).suspectPeople());
        assertEquals(0,result.get(1).damagePeople());
        assertEquals(1,result.get(1).suspectPeople());
    }
    @Test void excludesHiddenNonPublicOldAndFutureRecords() {
        for (var status : ReportStatus.values()) {
            if (status != ReportStatus.PUBLISHED)
                report(a,status.name(),ReportKind.FRAUD,status,now,false);
        }
        report(a,"hidden",ReportKind.FRAUD,ReportStatus.PUBLISHED,now,true);
        report(a,"old",ReportKind.FRAUD,ReportStatus.PUBLISHED,now.minusYears(1).minusSeconds(1),false);
        report(a,"future",ReportKind.FRAUD,ReportStatus.PUBLISHED,now.plusSeconds(1),false);
        report(a,"boundary",ReportKind.SUSPICION,ReportStatus.PUBLISHED,now.minusYears(1),false);
        var result = counts.count(List.of(a.getId()),now).get(0);
        assertEquals(0,result.damagePeople());
        assertEquals(1,result.suspectPeople());
    }
    @Test void returnsZeroForKnownUnreportedIdAndOmitsUnknownId() {
        var result=counts.count(List.of(a.getId(),Long.MAX_VALUE),now);
        assertEquals(1,result.size());
        assertEquals(0,result.get(0).damagePeople());
        assertEquals(0,result.get(0).suspectPeople());
        assertTrue(counts.count(List.of(),now).isEmpty());
        assertThrows(IllegalArgumentException.class,()->counts.count(List.of(-1L),now));
    }
}
