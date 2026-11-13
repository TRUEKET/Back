
package com.trueket.backend.lookup.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.trueket.backend.identifier.entity.Identifier;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.lookup.dto.LookupResponse;
import com.trueket.backend.lookup.util.IdentifierHashService;
import com.trueket.backend.report.entity.Report;
import com.trueket.backend.lookup.dto.LookupCompareResponse;
import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.global.exception.ErrorCode;
import com.trueket.backend.report.entity.ReportIdentifier;
import com.trueket.backend.report.entity.enums.ReportKind;
import com.trueket.backend.report.entity.enums.ReportStatus;
import com.trueket.backend.report.entity.enums.TradeType;
import com.trueket.backend.lookup.dto.LookupDetailResponse;
import com.trueket.backend.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;


import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LookupServiceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LookupService lookupService;

    @Autowired
    private IdentifierHashService identifierHashService;

    private User user;
    private Identifier identifier;

    private static final String PHONE = "01012345678";

    @BeforeEach
    void setUp() {

        // 테스트 사용자 생성
        user = createUser("reporter-main");

        // 실제 HMAC으로 식별자 해시 생성
        String valueHash = identifierHashService.hash(
                IdentifierType.PHONE,
                PHONE
        );

        identifier = Identifier.create(
                IdentifierType.PHONE,
                valueHash,
                "010-****-5678",
                "test-encrypted-phone-value"
        );

        entityManager.persist(identifier);
        entityManager.flush();
    }

    /**
     * 테스트 사용자 생성
     *
     * User에 공개 생성 메서드가 없으므로
     * 테스트에서만 ReflectionTestUtils 사용
     */
    private User createUser(String prefix) {

        User newUser = new UserForTestFactory().create();

        String unique = UUID.randomUUID().toString();

        ReflectionTestUtils.setField(
                newUser,
                "email",
                prefix + "-" + unique + "@test.com"
        );

        ReflectionTestUtils.setField(
                newUser,
                "passwordHash",
                "test-password-hash"
        );

        ReflectionTestUtils.setField(
                newUser,
                "phoneHash",
                "phone-" + unique
        );

        ReflectionTestUtils.setField(
                newUser,
                "phoneEnc",
                "test-encrypted-phone"
        );

        ReflectionTestUtils.setField(
                newUser,
                "phoneLast4",
                "5678"
        );

        ReflectionTestUtils.setField(
                newUser,
                "phoneVerifiedAt",
                OffsetDateTime.now()
        );

        ReflectionTestUtils.setField(
                newUser,
                "status",
                "ACTIVE"
        );

        ReflectionTestUtils.setField(
                newUser,
                "createdAt",
                OffsetDateTime.now()
        );

        ReflectionTestUtils.setField(
                newUser,
                "updatedAt",
                OffsetDateTime.now()
        );

        entityManager.persist(newUser);
        entityManager.flush();

        return newUser;
    }

    /**
     * 테스트 신고 생성
     */
    private Report createReport(
            ReportKind kind,
            ReportStatus status,
            String reporterHash,
            boolean hidden
    ) {

        Report report = Report.create(
                user,
                reporterHash,
                kind,
                TradeType.TRANSFER,
                JsonNodeFactory.instance.arrayNode(),
                JsonNodeFactory.instance.arrayNode(),
                null
        );

        report.changeStatus(status);

        entityManager.persist(report);
        entityManager.flush();

        ReportIdentifier reportIdentifier =
                ReportIdentifier.create(report, identifier);

        if (hidden) {
            reportIdentifier.hide();
        }

        entityManager.persist(reportIdentifier);
        entityManager.flush();

        return report;
    }

    /**
     * 특정 신고의 생성일을 DB에서 직접 변경
     *
     * Report.createdAt은 생성 시 자동 설정되므로
     * 기간 필터 테스트를 위해 SQL 사용
     */
    private void changeCreatedAt(
            Report report,
            OffsetDateTime createdAt
    ) {
        entityManager.flush();

        entityManager.createNativeQuery(
                        """
                        UPDATE reports
                        SET created_at = ?1
                        WHERE id = ?2
                        """
                )
                .setParameter(1, createdAt)
                .setParameter(2, report.getId())
                .executeUpdate();

        entityManager.clear();
    }

    @Test
    @DisplayName("RP-01. PUBLISHED 신고는 조회 결과에 포함된다")
    void publishedReportIsIncluded() {

        createReport(
                ReportKind.FRAUD,
                ReportStatus.PUBLISHED,
                "reporter-A",
                false
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.identifierId())
                .isEqualTo(identifier.getId());

        assertThat(response.type())
                .isEqualTo(IdentifierType.PHONE);

        assertThat(response.fraudCount())
                .isEqualTo(1);

        assertThat(response.riskLevel())
                .isEqualTo("DANGER");
    }

    @Test
    @DisplayName("RP-01. RESPONDING과 REVIEWING 신고도 포함된다")
    void respondingAndReviewingReportsAreIncluded() {

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.RESPONDING,
                "reporter-A",
                false
        );

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.REVIEWING,
                "reporter-B",
                false
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.suspicionCount())
                .isEqualTo(2);

        assertThat(response.riskLevel())
                .isEqualTo("SUSPICION");
    }

    @Test
    @DisplayName("RP-01. PRIVATE 신고는 집계에서 제외된다")
    void privateReportIsExcluded() {

        createReport(
                ReportKind.FRAUD,
                ReportStatus.PRIVATE,
                "reporter-A",
                false
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.fraudCount()).isZero();
        assertThat(response.suspicionCount()).isZero();
        assertThat(response.riskLevel()).isEqualTo("SAFE");
    }

    @Test
    @DisplayName("RP-01. hidden=true인 식별자는 집계에서 제외된다")
    void hiddenIdentifierIsExcluded() {

        createReport(
                ReportKind.FRAUD,
                ReportStatus.PUBLISHED,
                "reporter-A",
                true
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.fraudCount()).isZero();
        assertThat(response.riskLevel()).isEqualTo("SAFE");
    }

    @Test
    @DisplayName("RP-01. 1년이 지난 신고는 집계에서 제외된다")
    void oldReportIsExcluded() {

        Report report = createReport(
                ReportKind.FRAUD,
                ReportStatus.PUBLISHED,
                "reporter-A",
                false
        );

        changeCreatedAt(
                report,
                OffsetDateTime.now().minusYears(1).minusDays(1)
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.fraudCount()).isZero();
        assertThat(response.riskLevel()).isEqualTo("SAFE");
    }

    @Test
    @DisplayName("RP-01. 동일 신고자의 중복 제보는 1명으로 집계한다")
    void duplicateReporterIsCountedOnce() {

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "same-reporter",
                false
        );

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "same-reporter",
                false
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.suspicionCount())
                .isEqualTo(1);

        assertThat(response.riskLevel())
                .isEqualTo("SAFE");
    }

    @Test
    @DisplayName("RP-01. 서로 다른 의심 제보자 2명은 SUSPICION이다")
    void twoDistinctSuspicionReporters() {

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-A",
                false
        );

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-B",
                false
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.suspicionCount())
                .isEqualTo(2);

        assertThat(response.riskLevel())
                .isEqualTo("SUSPICION");
    }

    @Test
    @DisplayName("RP-01. 피해 신고가 있으면 DANGER가 우선이다")
    void fraudTakesPriority() {

        createReport(
                ReportKind.FRAUD,
                ReportStatus.PUBLISHED,
                "reporter-A",
                false
        );

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-B",
                false
        );

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-C",
                false
        );

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.fraudCount()).isEqualTo(1);
        assertThat(response.suspicionCount()).isEqualTo(2);
        assertThat(response.riskLevel()).isEqualTo("DANGER");
    }

    @Test
    @DisplayName("RP-01. 다른 식별자의 신고는 집계되지 않는다")
    void otherIdentifierReportsAreExcluded() {

        String otherPhone = "01099998888";

        Identifier otherIdentifier = Identifier.create(
                IdentifierType.PHONE,
                identifierHashService.hash(
                        IdentifierType.PHONE,
                        otherPhone
                ),
                "010-****-8888",
                "test-encrypted-phone-value"
        );

        entityManager.persist(otherIdentifier);

        Report report = Report.create(
                user,
                "reporter-other",
                ReportKind.FRAUD,
                TradeType.TRANSFER,
                JsonNodeFactory.instance.arrayNode(),
                JsonNodeFactory.instance.arrayNode(),
                null
        );

        entityManager.persist(report);
        entityManager.flush();

        entityManager.persist(
                ReportIdentifier.create(
                        report,
                        otherIdentifier
                )
        );

        entityManager.flush();

        LookupResponse response = lookupService.lookup(PHONE);

        assertThat(response.fraudCount()).isZero();
        assertThat(response.riskLevel()).isEqualTo("SAFE");
    }

    /**
     * User의 protected 기본 생성자를
     * 테스트에서 사용할 수 있도록 하는 팩토리
     */
    private static class UserForTestFactory {

        User create() {
            try {
                var constructor =
                        User.class.getDeclaredConstructor();

                constructor.setAccessible(true);

                return constructor.newInstance();

            } catch (Exception e) {
                throw new IllegalStateException(
                        "테스트 사용자 생성 실패", e
                );
            }
        }
    }

    private Identifier createRelatedIdentifier(String suffix) {
        Identifier related = Identifier.create(
                IdentifierType.SNS,
                "test-related-hash-" + UUID.randomUUID(),
                "related-****-" + suffix,
                "test-encrypted-related-value"
        );

        entityManager.persist(related);
        entityManager.flush();

        return related;
    }

    private void connectIdentifier(
            Report report,
            Identifier related,
            boolean hidden
    ) {
        ReportIdentifier connection =
                ReportIdentifier.create(report, related);

        if (hidden) {
            connection.hide();
        }

        entityManager.persist(connection);
        entityManager.flush();
    }



    @Test
    @DisplayName("RP-02. 공개 상태만 상세 조회에 포함된다")
    void detailIncludesOnlyActiveStatuses() {
        createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-A",
                false
        );

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.RESPONDING,
                "reporter-B",
                false
        );

        createReport(
                ReportKind.SUSPICION,
                ReportStatus.REVIEWING,
                "reporter-C",
                false
        );

        createReport(
                ReportKind.FRAUD,
                ReportStatus.PRIVATE,
                "reporter-D",
                false
        );

        LookupDetailResponse response =
                lookupService.getLookupDetail(identifier.getId());

        assertThat(response.suspicionCount()).isEqualTo(3);
        assertThat(response.fraudCount()).isZero();
        assertThat(response.riskLevel()).isEqualTo("SUSPICION");
        assertThat(response.timeline()).hasSize(3);
    }


    @Test
    @DisplayName("RP-02. 숨김 처리된 신고 식별자는 상세 조회에서 제외된다")
    void detailExcludesHiddenIdentifier() {
        createReport(
                ReportKind.FRAUD,
                ReportStatus.PUBLISHED,
                "reporter-A",
                true
        );

        LookupDetailResponse response =
                lookupService.getLookupDetail(identifier.getId());

        assertThat(response.riskLevel()).isEqualTo("SAFE");
        assertThat(response.fraudCount()).isZero();
        assertThat(response.suspicionCount()).isZero();
        assertThat(response.timeline()).isEmpty();
        assertThat(response.relatedIdentifiers()).isEmpty();
    }


    @Test
    @DisplayName("RP-02. 1년이 지난 신고는 상세 조회에서 제외된다")
    void detailExcludesOldReports() {
        Report oldReport = createReport(
                ReportKind.FRAUD,
                ReportStatus.PUBLISHED,
                "reporter-old",
                false
        );

        Long identifierId = identifier.getId();

        changeCreatedAt(
                oldReport,
                OffsetDateTime.now().minusYears(1).minusDays(1)
        );

        LookupDetailResponse response =
                lookupService.getLookupDetail(identifierId);

        assertThat(response.fraudCount()).isZero();
        assertThat(response.riskLevel()).isEqualTo("SAFE");
        assertThat(response.timeline()).isEmpty();
    }


    @Test
    @DisplayName("RP-02. 서로 다른 신고자 2명이 함께 신고한 식별자는 연관 정보에 포함된다")
    void relatedIdentifierRequiresTwoDistinctReporters() {
        Identifier related = createRelatedIdentifier("1234");

        Report report1 = createReport(
                ReportKind.FRAUD,
                ReportStatus.PUBLISHED,
                "reporter-A",
                false
        );

        Report report2 = createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-B",
                false
        );

        connectIdentifier(report1, related, false);
        connectIdentifier(report2, related, false);

        LookupDetailResponse response =
                lookupService.getLookupDetail(identifier.getId());

        assertThat(response.relatedIdentifiers())
                .hasSize(1);

        assertThat(response.relatedIdentifiers().get(0).identifierId())
                .isEqualTo(related.getId());

        assertThat(response.relatedIdentifiers().get(0).maskedValue())
                .isEqualTo("related-****-1234");
    }


    @Test
    @DisplayName("RP-02. 동일 신고자의 중복 신고만으로는 연관 정보에 포함되지 않는다")
    void duplicateReporterDoesNotQualifyRelatedIdentifier() {
        Identifier related = createRelatedIdentifier("5678");

        Report report1 = createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "same-reporter",
                false
        );

        Report report2 = createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "same-reporter",
                false
        );

        connectIdentifier(report1, related, false);
        connectIdentifier(report2, related, false);

        LookupDetailResponse response =
                lookupService.getLookupDetail(identifier.getId());

        assertThat(response.relatedIdentifiers()).isEmpty();
        assertThat(response.suspicionCount()).isEqualTo(1);
        assertThat(response.timeline()).hasSize(2);
    }


    @Test
    @DisplayName("RP-02. 숨김 처리된 연관 식별자는 신고자 수에 포함되지 않는다")
    void hiddenRelatedIdentifierIsExcluded() {
        Identifier related = createRelatedIdentifier("9999");

        Report report1 = createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-A",
                false
        );

        Report report2 = createReport(
                ReportKind.SUSPICION,
                ReportStatus.PUBLISHED,
                "reporter-B",
                false
        );

        connectIdentifier(report1, related, false);
        connectIdentifier(report2, related, true);

        LookupDetailResponse response =
                lookupService.getLookupDetail(identifier.getId());

        assertThat(response.relatedIdentifiers()).isEmpty();
        assertThat(response.suspicionCount()).isEqualTo(2);
    }


    @Test
    @DisplayName("RP-03. 실제 HMAC 해시가 일치하면 전체 값을 반환한다")
    void compareReturnsFullValueWhenHashMatches() {

        LookupCompareResponse response = lookupService.compare(
                identifier.getId(),
                "010-1234-5678"
        );

        assertThat(response.matched()).isTrue();
        assertThat(response.value()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("RP-03. 실제 HMAC 해시가 다르면 전체 값을 반환하지 않는다")
    void compareDoesNotReturnValueWhenHashDiffers() {

        LookupCompareResponse response = lookupService.compare(
                identifier.getId(),
                "010-9999-8888"
        );

        assertThat(response.matched()).isFalse();
        assertThat(response.value()).isNull();
    }

    @Test
    @DisplayName("RP-03. 식별자 유형이 다르면 불일치 처리한다")
    void compareReturnsFalseWhenTypeDiffers() {

        LookupCompareResponse response = lookupService.compare(
                identifier.getId(),
                "@different_user"
        );

        assertThat(response.matched()).isFalse();
        assertThat(response.value()).isNull();
    }

    @Test
    @DisplayName("RP-03. 존재하지 않는 식별자는 예외를 발생시킨다")
    void compareThrowsWhenIdentifierNotFound() {

        assertThatThrownBy(() ->
                lookupService.compare(
                        -1L,
                        "010-1234-5678"
                )
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException =
                            (BusinessException) exception;

                    assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.IDENTIFIER_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("RP-03. 은행명과 계좌번호를 정규화하여 비교한다")
    void compareNormalizesBankAccount() {

        String account = "KB:123456789012";

        Identifier accountIdentifier = Identifier.create(
                IdentifierType.ACCOUNT,
                identifierHashService.hash(
                        IdentifierType.ACCOUNT,
                        account
                ),
                "KB-****-9012",
                "test-encrypted-account-value"
        );

        entityManager.persist(accountIdentifier);
        entityManager.flush();

        LookupCompareResponse response = lookupService.compare(
                accountIdentifier.getId(),
                "국민 123-456-789012"
        );

        assertThat(response.matched()).isTrue();
        assertThat(response.value()).isEqualTo(account);
    }


}
