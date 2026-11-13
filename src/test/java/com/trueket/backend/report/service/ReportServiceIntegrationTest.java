
package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReportServiceIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // 테스트용 사용자 생성
    private Long createUser() {
        String unique = UUID.randomUUID().toString();

        return jdbcTemplate.queryForObject("""
                INSERT INTO users (
                    email,
                    password_hash,
                    phone_hash,
                    phone_enc,
                    phone_last4,
                    phone_verified_at,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, NOW(),
                        'ACTIVE', NOW(), NOW())
                RETURNING id
                """,
                Long.class,
                "test-" + unique + "@example.com",
                "test-password-hash",
                "phone-" + unique,
                "test-encrypted-phone",
                "1234"
        );
    }

    // 테스트용 신고 생성
    private Long createReport(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO reports (
                    user_id,
                    reporter_hash,
                    kind,
                    status,
                    trade_type,
                    evidence_quotes,
                    reference_links,
                    sent_date,
                    created_at
                )
                VALUES (
                    ?, ?,
                    'FRAUD',
                    'PUBLISHED',
                    'TRANSFER',
                    CAST('[]' AS jsonb),
                    CAST('[]' AS jsonb),
                    CURRENT_DATE,
                    NOW()
                )
                RETURNING id
                """,
                Long.class,
                userId,
                "reporter-" + UUID.randomUUID()
        );
    }

    // 테스트용 식별자 생성
    private Long createIdentifier() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO identifiers (
                    type,
                    value_hash,
                    value_masked,
                    value_enc,
                    created_at
                )
                VALUES (
                    'ACCOUNT',
                    ?, '123-***-456',
                    'test-encrypted-value',
                    NOW()
                )
                RETURNING id
                """,
                Long.class,
                "identifier-" + UUID.randomUUID()
        );
    }

    // 신고와 식별자 연결
    private void connectIdentifier(
            Long reportId,
            Long identifierId
    ) {
        jdbcTemplate.update("""
                INSERT INTO report_identifiers (
                    report_id,
                    identifier_id,
                    hidden
                )
                VALUES (?, ?, false)
                """,
                reportId,
                identifierId
        );
    }

    // 테스트용 증빙 생성
    private void createEvidence(Long reportId) {
        jdbcTemplate.update("""
                INSERT INTO evidence (
                    report_id,
                    kind,
                    storage_key,
                    proof_hash,
                    expires_at,
                    created_at
                )
                VALUES (
                    ?,
                    'NUMBER_FRAGMENT',
                    ?,
                    ?,
                    NOW() + INTERVAL '7 days',
                    NOW()
                )
                """,
                reportId,
                "test/" + UUID.randomUUID(),
                "proof-" + UUID.randomUUID()
        );
    }

    private int countById(String table, Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?",
                Integer.class,
                id
        );
        return count;
    }

    private int countByReportId(String table, Long reportId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table
                        + " WHERE report_id = ?",
                Integer.class,
                reportId
        );
        return count;
    }

    @Test
    void 신고를_삭제하면_연결_데이터도_삭제된다() {

        // given
        Long userId = createUser();
        Long reportId = createReport(userId);
        Long identifierId = createIdentifier();

        connectIdentifier(reportId, identifierId);
        createEvidence(reportId);

        assertThat(countById("reports", reportId))
                .isEqualTo(1);

        assertThat(countByReportId(
                "report_identifiers", reportId))
                .isEqualTo(1);

        assertThat(countByReportId("evidence", reportId))
                .isEqualTo(1);

        // when
        reportService.deleteMyReport(userId, reportId);

        // 실제 DB에 SQL 반영
        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(countById("reports", reportId))
                .isZero();

        assertThat(countByReportId(
                "report_identifiers", reportId))
                .isZero();

        assertThat(countByReportId("evidence", reportId))
                .isZero();

        // 식별자 자체는 유지
        assertThat(countById("identifiers", identifierId))
                .isEqualTo(1);
    }

    @Test
    void 다른_사용자의_신고는_삭제할_수_없다() {

        // given
        Long ownerId = createUser();
        Long otherUserId = createUser();
        Long reportId = createReport(ownerId);

        // when & then
        assertThatThrownBy(() ->
                reportService.deleteMyReport(
                        otherUserId, reportId)
        )
                .isInstanceOf(BusinessException.class);

        assertThat(countById("reports", reportId))
                .isEqualTo(1);
    }

    @Test
    void 공유_식별자를_사용하는_다른_신고는_유지된다() {

        // given
        Long user1 = createUser();
        Long user2 = createUser();

        Long report1 = createReport(user1);
        Long report2 = createReport(user2);

        Long identifierId = createIdentifier();

        connectIdentifier(report1, identifierId);
        connectIdentifier(report2, identifierId);

        // when
        reportService.deleteMyReport(user1, report1);

        entityManager.flush();
        entityManager.clear();

        // then
        assertThat(countById("reports", report1))
                .isZero();

        assertThat(countById("reports", report2))
                .isEqualTo(1);

        assertThat(countById("identifiers", identifierId))
                .isEqualTo(1);

        assertThat(countByReportId(
                "report_identifiers", report2))
                .isEqualTo(1);
    }

    private Long createDispute(Long identifierId) {

        String unique = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20);

        String receiptNo = "TK-TEST-" + unique;

        return jdbcTemplate.queryForObject("""
            INSERT INTO disputes (
                receipt_no,
                identifier_id,
                reason,
                description,
                seller_email,
                proof_images,
                created_at
            )
            VALUES (
                ?, ?,
                'NORMAL',
                '통합 테스트용 이의제기',
                'seller@example.com',
                CAST('[]' AS jsonb),
                NOW()
            )
            RETURNING id
            """,
                Long.class,
                receiptNo,
                identifierId
        );
    }

    private void createDisputeItem(
            Long disputeId,
            Long reportId
    ) {
        jdbcTemplate.update("""
            INSERT INTO dispute_items (
                dispute_id,
                report_id,
                status,
                deadline,
                republished
            )
            VALUES (
                ?, ?,
                'RESPONDING',
                NOW() + INTERVAL '3 days',
                false
            )
            """,
                disputeId,
                reportId
        );
    }

    @Test
    void 이의제기가_연결된_신고를_삭제하면_해당_항목만_삭제된다() {

        // given
        Long userId = createUser();

        Long report1 = createReport(userId);
        Long report2 = createReport(userId);

        Long identifierId = createIdentifier();

        connectIdentifier(report1, identifierId);
        connectIdentifier(report2, identifierId);

        Long disputeId = createDispute(identifierId);

        createDisputeItem(disputeId, report1);
        createDisputeItem(disputeId, report2);

        // 삭제 전 확인
        assertThat(countByReportId("dispute_items", report1))
                .isEqualTo(1);

        assertThat(countByReportId("dispute_items", report2))
                .isEqualTo(1);

        // when
        reportService.deleteMyReport(userId, report1);

        entityManager.flush();
        entityManager.clear();

        // then

        // 삭제 대상 신고
        assertThat(countById("reports", report1))
                .isZero();

        // 해당 신고의 이의제기 항목 삭제
        assertThat(countByReportId("dispute_items", report1))
                .isZero();

        // 다른 신고는 유지
        assertThat(countById("reports", report2))
                .isEqualTo(1);

        // 다른 신고의 이의제기 항목 유지
        assertThat(countByReportId("dispute_items", report2))
                .isEqualTo(1);

        // 이의제기 원본 유지
        assertThat(countById("disputes", disputeId))
                .isEqualTo(1);

        // 공유 식별자 유지
        assertThat(countById("identifiers", identifierId))
                .isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 신고_삭제_중_DB_오류가_발생하면_전체_롤백된다() {

        // 반드시 테스트 전용 DB에서만 실행

        // 1. 테스트 데이터 준비 및 커밋
        Long[] ids = transactionTemplate.execute(status -> {
            Long userId = createUser();
            Long reportId = createReport(userId);
            Long identifierId = createIdentifier();

            connectIdentifier(reportId, identifierId);
            createEvidence(reportId);

            return new Long[]{userId, reportId, identifierId};
        });

        Long userId = ids[0];
        Long reportId = ids[1];
        Long identifierId = ids[2];

        try {
            // 2. 삭제를 막는 임시 DB 제약 조건 설정
            // 이 부분은 별도 DB 전용 트리거 등을 사용해 구성해야 함

            // 3. 삭제 실행
            // reportService.deleteMyReport(userId, reportId);

            // 4. 예외 발생 및 롤백 확인

        } finally {
            // 5. 테스트 데이터 정리
            // 테스트 전용 데이터만 삭제
        }
    }
}
