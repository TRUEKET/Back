
package com.trueket.backend.lookup.service;

import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.repository.IdentifierRepository;
import com.trueket.backend.lookup.dto.LookupDetailResponse;
import com.trueket.backend.lookup.dto.LookupResponse;
import com.trueket.backend.lookup.dto.LookupTimelineResponse;
import com.trueket.backend.lookup.util.IdentifierHashService;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.lookup.dto.LookupCompareResponse;
import com.trueket.backend.lookup.util.IdentifierNormalizer;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.repository.ReportIdentifierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LookupServiceTest {

    @Mock
    private IdentifierRepository identifierRepository;

    @Mock
    private ReportIdentifierRepository reportIdentifierRepository;

    @Mock
    private IdentifierHashService identifierHashService;

    @InjectMocks
    private LookupService lookupService;

    private Identifier identifier;

    private static final String PHONE = "01012345678";
    private static final String HASH = "test-hash";

    @BeforeEach
    void setUp() {
        identifier = mock(Identifier.class);
    }

    /**
     * 테스트용 신고 연결 객체 생성
     */
    private ReportIdentifier createRecord(
            ReportKind kind,
            String reporterHash
    ) {
        Report report = mock(Report.class);

        when(report.getKind()).thenReturn(kind);
        when(report.getReporterHash()).thenReturn(reporterHash);

        ReportIdentifier record = mock(ReportIdentifier.class);
        when(record.getReport()).thenReturn(report);

        return record;
    }

    /**
     * 검색 대상 식별자가 존재하도록 설정
     */
    private void givenIdentifierExists() {
        when(identifierRepository.findByTypeAndValueHash(
                IdentifierType.PHONE, HASH
        )).thenReturn(Optional.of(identifier));

        when(identifier.getId()).thenReturn(1L);
        when(identifier.getType()).thenReturn(IdentifierType.PHONE);
    }

    /**
     * 전화번호 해시 생성 설정
     */
    private void givenPhoneHash() {
        when(identifierHashService.hash(
                IdentifierType.PHONE, PHONE
        )).thenReturn(HASH);
    }

    @Test
    @DisplayName("RP-01. 전화번호를 정규화한 뒤 해시로 정확히 조회한다")
    void lookupNormalizesPhoneAndSearchesByHash() {

        givenPhoneHash();
        givenIdentifierExists();

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(1L),
                anyCollection(),
                any(OffsetDateTime.class)
        )).thenReturn(List.of());

        LookupResponse response =
                lookupService.lookup("010-1234-5678");

        assertThat(response.identifierId()).isEqualTo(1L);
        assertThat(response.type()).isEqualTo(IdentifierType.PHONE);
        assertThat(response.value()).isEqualTo(PHONE);
        assertThat(response.riskLevel()).isEqualTo("SAFE");
        assertThat(response.fraudCount()).isZero();
        assertThat(response.suspicionCount()).isZero();

        verify(identifierHashService).hash(
                IdentifierType.PHONE, PHONE
        );

        verify(identifierRepository).findByTypeAndValueHash(
                IdentifierType.PHONE, HASH
        );
    }

    @Test
    @DisplayName("RP-01. 식별자가 존재하지 않으면 SAFE와 0명을 반환한다")
    void lookupReturnsSafeWhenIdentifierNotFound() {

        givenPhoneHash();

        when(identifierRepository.findByTypeAndValueHash(
                IdentifierType.PHONE, HASH
        )).thenReturn(Optional.empty());

        LookupResponse response =
                lookupService.lookup(PHONE);

        assertThat(response.identifierId()).isNull();
        assertThat(response.type()).isEqualTo(IdentifierType.PHONE);
        assertThat(response.value()).isNull();
        assertThat(response.riskLevel()).isEqualTo("SAFE");
        assertThat(response.fraudCount()).isZero();
        assertThat(response.suspicionCount()).isZero();

        verifyNoInteractions(reportIdentifierRepository);
    }

    @Test
    @DisplayName("RP-01. 피해 신고자가 1명 이상이면 DANGER다")
    void lookupReturnsDangerWhenFraudExists() {

        givenPhoneHash();
        givenIdentifierExists();

        ReportIdentifier fraud =
                createRecord(ReportKind.FRAUD, "reporter-A");

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(1L), anyCollection(), any(OffsetDateTime.class)
        )).thenReturn(List.of(fraud));

        LookupResponse response =
                lookupService.lookup(PHONE);

        assertThat(response.riskLevel()).isEqualTo("DANGER");
        assertThat(response.fraudCount()).isEqualTo(1);
        assertThat(response.suspicionCount()).isZero();
    }

    @Test
    @DisplayName("RP-01. 서로 다른 의심 제보자 2명이면 SUSPICION이다")
    void lookupReturnsSuspicionWhenTwoReportersExist() {

        givenPhoneHash();
        givenIdentifierExists();

        ReportIdentifier first =
                createRecord(ReportKind.SUSPICION, "reporter-A");

        ReportIdentifier second =
                createRecord(ReportKind.SUSPICION, "reporter-B");

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(1L), anyCollection(), any(OffsetDateTime.class)
        )).thenReturn(List.of(first, second));

        LookupResponse response =
                lookupService.lookup(PHONE);

        assertThat(response.riskLevel()).isEqualTo("SUSPICION");
        assertThat(response.fraudCount()).isZero();
        assertThat(response.suspicionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("RP-01. 의심 제보자가 1명이면 SAFE다")
    void lookupReturnsSafeWhenOneSuspicionExists() {

        givenPhoneHash();
        givenIdentifierExists();

        ReportIdentifier suspicion =
                createRecord(ReportKind.SUSPICION, "reporter-A");

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(1L), anyCollection(), any(OffsetDateTime.class)
        )).thenReturn(List.of(suspicion));

        LookupResponse response =
                lookupService.lookup(PHONE);

        assertThat(response.riskLevel()).isEqualTo("SAFE");
        assertThat(response.suspicionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("RP-01. 같은 신고자가 여러 번 제보해도 1명으로 집계한다")
    void lookupCountsDistinctReporters() {

        givenPhoneHash();
        givenIdentifierExists();

        ReportIdentifier first =
                createRecord(ReportKind.SUSPICION, "same-reporter");

        ReportIdentifier second =
                createRecord(ReportKind.SUSPICION, "same-reporter");

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(1L), anyCollection(), any(OffsetDateTime.class)
        )).thenReturn(List.of(first, second));

        LookupResponse response =
                lookupService.lookup(PHONE);

        assertThat(response.suspicionCount()).isEqualTo(1);
        assertThat(response.riskLevel()).isEqualTo("SAFE");
    }

    @Test
    @DisplayName("RP-01. 피해 신고와 의심 제보가 함께 있으면 DANGER가 우선이다")
    void lookupPrioritizesDanger() {

        givenPhoneHash();
        givenIdentifierExists();

        ReportIdentifier fraud =
                createRecord(ReportKind.FRAUD, "reporter-A");

        ReportIdentifier suspicion1 =
                createRecord(ReportKind.SUSPICION, "reporter-B");

        ReportIdentifier suspicion2 =
                createRecord(ReportKind.SUSPICION, "reporter-C");

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(1L), anyCollection(), any(OffsetDateTime.class)
        )).thenReturn(List.of(
                fraud, suspicion1, suspicion2
        ));

        LookupResponse response =
                lookupService.lookup(PHONE);

        assertThat(response.fraudCount()).isEqualTo(1);
        assertThat(response.suspicionCount()).isEqualTo(2);
        assertThat(response.riskLevel()).isEqualTo("DANGER");
    }

    @Test
    @DisplayName("RP-01. 유효한 신고 상태와 최근 1년 조건을 Repository에 전달한다")
    void lookupFiltersActiveReportsWithinOneYear() {

        givenPhoneHash();
        givenIdentifierExists();

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(1L), anyCollection(), any(OffsetDateTime.class)
        )).thenReturn(List.of());

        OffsetDateTime before = OffsetDateTime.now().minusYears(1);

        lookupService.lookup(PHONE);

        OffsetDateTime after = OffsetDateTime.now().minusYears(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<ReportStatus>> statusesCaptor =
                ArgumentCaptor.forClass(
                        (Class) java.util.Collection.class
                );

        ArgumentCaptor<OffsetDateTime> sinceCaptor =
                ArgumentCaptor.forClass(OffsetDateTime.class);

        verify(reportIdentifierRepository)
                .findActiveReportsByIdentifier(
                        eq(1L),
                        statusesCaptor.capture(),
                        sinceCaptor.capture()
                );

        assertThat(statusesCaptor.getValue())
                .containsExactlyInAnyOrder(
                        ReportStatus.PUBLISHED,
                        ReportStatus.RESPONDING,
                        ReportStatus.REVIEWING
                )
                .doesNotContain(ReportStatus.PRIVATE);

        assertThat(sinceCaptor.getValue())
                .isBetween(before, after);
    }

    @Test
    @DisplayName("RP-01. X 계정은 SNS 유형으로 해시 조회한다")
    void lookupSns() {

        when(identifierHashService.hash(
                IdentifierType.SNS, "trueket_user"
        )).thenReturn("sns-hash");

        when(identifierRepository.findByTypeAndValueHash(
                IdentifierType.SNS, "sns-hash"
        )).thenReturn(Optional.empty());

        LookupResponse response =
                lookupService.lookup("@Trueket_User");

        assertThat(response.type()).isEqualTo(IdentifierType.SNS);
        assertThat(response.riskLevel()).isEqualTo("SAFE");

        verify(identifierHashService).hash(
                IdentifierType.SNS, "trueket_user"
        );
    }

    @Test
    @DisplayName("RP-01. 은행명이 포함된 계좌번호는 ACCOUNT로 해시 조회한다")
    void lookupAccount() {

        when(identifierHashService.hash(
                IdentifierType.ACCOUNT, "KB:123456789012"
        )).thenReturn("account-hash");

        when(identifierRepository.findByTypeAndValueHash(
                IdentifierType.ACCOUNT, "account-hash"
        )).thenReturn(Optional.empty());

        LookupResponse response =
                lookupService.lookup("KB 123-456-789012");

        assertThat(response.type()).isEqualTo(IdentifierType.ACCOUNT);
        assertThat(response.riskLevel()).isEqualTo("SAFE");

        verify(identifierHashService).hash(
                IdentifierType.ACCOUNT, "KB:123456789012"
        );
    }

    @Test
    @DisplayName("RP-01. 잘못된 입력은 DB 조회 없이 예외를 발생시킨다")
    void lookupRejectsInvalidInput() {

        assertThatThrownBy(() ->
                lookupService.lookup("010-1234")
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(
                identifierRepository,
                reportIdentifierRepository,
                identifierHashService
        );
    }


    @Test
    void 상세조회_타임라인은_신고시각_내림차순으로_정렬된다() {
        Long identifierId = 1L;

        Identifier identifier = mock(Identifier.class);

        when(identifier.getId()).thenReturn(identifierId);
        when(identifier.getType()).thenReturn(IdentifierType.PHONE);
        when(identifier.getValueMasked()).thenReturn("010-****-5678");

        when(identifierRepository.findById(identifierId))
                .thenReturn(Optional.of(identifier));

        Report olderReport = mock(Report.class);
        Report newerReport = mock(Report.class);

        when(olderReport.getId()).thenReturn(10L);
        when(olderReport.getCreatedAt()).thenReturn(
                OffsetDateTime.parse("2026-11-11T09:30:00+09:00")
        );
        when(olderReport.getKind()).thenReturn(ReportKind.FRAUD);
        when(olderReport.getReporterHash()).thenReturn("reporter-1");

        when(newerReport.getId()).thenReturn(11L);
        when(newerReport.getCreatedAt()).thenReturn(
                OffsetDateTime.parse("2026-11-11T18:20:00+09:00")
        );
        when(newerReport.getKind()).thenReturn(ReportKind.SUSPICION);
        when(newerReport.getReporterHash()).thenReturn("reporter-2");

        ReportIdentifier olderRecord = mock(ReportIdentifier.class);
        ReportIdentifier newerRecord = mock(ReportIdentifier.class);

        when(olderRecord.getReport()).thenReturn(olderReport);
        when(newerRecord.getReport()).thenReturn(newerReport);

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(identifierId),
                anyCollection(),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(olderRecord, newerRecord));

        when(reportIdentifierRepository.findVisibleIdentifiersByReportIds(
                anyCollection()
        )).thenReturn(List.of());

        LookupDetailResponse response =
                lookupService.getLookupDetail(identifierId);

        assertThat(response.timeline())
                .extracting(LookupTimelineResponse::kind)
                .containsExactly(
                        ReportKind.SUSPICION,
                        ReportKind.FRAUD
                );
    }


    private Report mockReport(
            Long id,
            String reporterHash,
            ReportKind kind,
            String createdAt
    ) {
        Report report = mock(Report.class);

        when(report.getId()).thenReturn(id);
        when(report.getReporterHash()).thenReturn(reporterHash);
        when(report.getKind()).thenReturn(kind);
        when(report.getCreatedAt()).thenReturn(
                OffsetDateTime.parse(createdAt)
        );

        return report;
    }

    private ReportIdentifier mockRecord(
            Report report,
            Identifier identifier
    ) {
        ReportIdentifier record = mock(ReportIdentifier.class);

        lenient().when(record.getReport()).thenReturn(report);
        lenient().when(record.getIdentifier()).thenReturn(identifier);

        return record;
    }



    @Test
    void 연관식별자는_서로다른_신고자_2명_이상일때만_반환된다() {
        Long targetId = 1L;

        Identifier target = mock(Identifier.class);
        when(target.getId()).thenReturn(targetId);
        when(target.getType()).thenReturn(IdentifierType.PHONE);
        when(target.getValueMasked()).thenReturn("010-****-1234");

        Identifier related = mock(Identifier.class);
        when(related.getId()).thenReturn(2L);
        when(related.getType()).thenReturn(IdentifierType.ACCOUNT);
        when(related.getValueMasked()).thenReturn("****5678");

        Report report1 = mockReport(
                10L, "user-A", ReportKind.FRAUD,
                "2026-11-10T10:00:00+09:00"
        );

        Report report2 = mockReport(
                11L, "user-B", ReportKind.SUSPICION,
                "2026-11-11T10:00:00+09:00"
        );

        // Repository stubbing 전에 Mock 객체 생성
        ReportIdentifier targetRecord1 = mockRecord(report1, target);
        ReportIdentifier targetRecord2 = mockRecord(report2, target);
        ReportIdentifier relatedRecord1 = mockRecord(report1, related);
        ReportIdentifier relatedRecord2 = mockRecord(report2, related);

        when(identifierRepository.findById(targetId))
                .thenReturn(Optional.of(target));

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(targetId),
                anyCollection(),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(
                targetRecord1,
                targetRecord2
        ));

        when(reportIdentifierRepository.findVisibleIdentifiersByReportIds(
                anyCollection()
        )).thenReturn(List.of(
                targetRecord1,
                relatedRecord1,
                targetRecord2,
                relatedRecord2
        ));

        LookupDetailResponse response =
                lookupService.getLookupDetail(targetId);

        assertThat(response.relatedIdentifiers()).hasSize(1);
        assertThat(response.relatedIdentifiers().get(0).identifierId())
                .isEqualTo(2L);
    }


    @Test
    void 연관식별자는_동일신고자의_중복신고를_한명으로_계산한다() {
        Long targetId = 1L;

        Identifier target = mock(Identifier.class);
        when(target.getId()).thenReturn(targetId);

        Identifier related = mock(Identifier.class);
        when(related.getId()).thenReturn(2L);

        Report report1 = mockReport(
                10L, "same-user", ReportKind.FRAUD,
                "2026-11-10T10:00:00+09:00"
        );

        Report report2 = mockReport(
                11L, "same-user", ReportKind.FRAUD,
                "2026-11-11T10:00:00+09:00"
        );

        ReportIdentifier targetRecord1 = mockRecord(report1, target);
        ReportIdentifier targetRecord2 = mockRecord(report2, target);
        ReportIdentifier relatedRecord1 = mockRecord(report1, related);
        ReportIdentifier relatedRecord2 = mockRecord(report2, related);

        when(identifierRepository.findById(targetId))
                .thenReturn(Optional.of(target));

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(targetId),
                anyCollection(),
                any(OffsetDateTime.class)
        )).thenReturn(List.of(
                targetRecord1,
                targetRecord2
        ));

        when(reportIdentifierRepository.findVisibleIdentifiersByReportIds(
                anyCollection()
        )).thenReturn(List.of(
                relatedRecord1,
                relatedRecord2
        ));

        LookupDetailResponse response =
                lookupService.getLookupDetail(targetId);

        assertThat(response.relatedIdentifiers()).isEmpty();
        assertThat(response.fraudCount()).isEqualTo(1);
    }


    @Test
    void 신고기록이_없으면_SAFE와_빈목록을_반환한다() {
        Long targetId = 1L;

        Identifier target = mock(Identifier.class);
        when(target.getId()).thenReturn(targetId);
        when(target.getType()).thenReturn(IdentifierType.PHONE);
        when(target.getValueMasked()).thenReturn("010-****-1234");

        when(identifierRepository.findById(targetId))
                .thenReturn(Optional.of(target));

        when(reportIdentifierRepository.findActiveReportsByIdentifier(
                eq(targetId), anyCollection(), any(OffsetDateTime.class)
        )).thenReturn(List.of());

        LookupDetailResponse response =
                lookupService.getLookupDetail(targetId);

        assertThat(response.riskLevel()).isEqualTo("SAFE");
        assertThat(response.fraudCount()).isZero();
        assertThat(response.suspicionCount()).isZero();
        assertThat(response.relatedIdentifiers()).isEmpty();
        assertThat(response.timeline()).isEmpty();

        verify(reportIdentifierRepository, never())
                .findVisibleIdentifiersByReportIds(anyCollection());
    }


    @Test
    void 존재하지않는_식별자는_404_예외를_발생시킨다() {
        Long identifierId = 999L;

        when(identifierRepository.findById(identifierId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                lookupService.getLookupDetail(identifierId)
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.IDENTIFIER_NOT_FOUND);
                });

        verify(reportIdentifierRepository, never())
                .findActiveReportsByIdentifier(
                        anyLong(),
                        anyCollection(),
                        any(OffsetDateTime.class)
                );
    }


    @Test
    void RP03_전화번호가_일치하면_전체값을_반환한다() {
        Identifier identifier = mock(Identifier.class);

        when(identifierRepository.findById(1L))
                .thenReturn(Optional.of(identifier));

        when(identifier.getType())
                .thenReturn(IdentifierType.PHONE);

        when(identifier.getValueHash())
                .thenReturn("same-hash");

        when(identifierHashService.hash(
                IdentifierType.PHONE,
                "01012345678"
        )).thenReturn("same-hash");

        LookupCompareResponse response = lookupService.compare(
                1L,
                "010-1234-5678"
        );

        assertThat(response.matched()).isTrue();
        assertThat(response.value()).isEqualTo("01012345678");
    }

    @Test
    void RP03_값이_다르면_전체값을_반환하지_않는다() {
        Identifier identifier = mock(Identifier.class);

        when(identifierRepository.findById(1L))
                .thenReturn(Optional.of(identifier));

        when(identifier.getType())
                .thenReturn(IdentifierType.PHONE);

        when(identifier.getValueHash())
                .thenReturn("stored-hash");

        when(identifierHashService.hash(
                IdentifierType.PHONE,
                "01099998888"
        )).thenReturn("different-hash");

        LookupCompareResponse response = lookupService.compare(
                1L,
                "010-9999-8888"
        );

        assertThat(response.matched()).isFalse();
        assertThat(response.value()).isNull();
    }

    @Test
    void RP03_존재하지않는_식별자는_404예외를_발생시킨다() {
        when(identifierRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                lookupService.compare(999L, "01012345678")
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.IDENTIFIER_NOT_FOUND);
                });
    }


}
