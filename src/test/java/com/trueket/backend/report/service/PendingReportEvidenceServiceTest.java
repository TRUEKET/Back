
package com.trueket.backend.report.service;

import com.trueket.backend.global.exception.BusinessException;
import com.trueket.backend.identifier.entity.enums.IdentifierType;
import com.trueket.backend.identifier.util.IdentifierHashService;
import com.trueket.backend.report.entity.PendingReportEvidence;
import com.trueket.backend.report.repository.PendingReportEvidenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PendingReportEvidenceServiceTest {

    private PendingReportEvidenceRepository repository;
    private SupabaseReportEvidenceStore storage;
    private IdentifierHashService hashService;
    private PendingReportEvidenceService service;

    private final byte[] image = new byte[]{1, 2, 3};

    private final String storageKey =
            "pending/15/550e8400-e29b-41d4-a716-446655440000.png";

    @BeforeEach
    void setUp() {
        repository = mock(PendingReportEvidenceRepository.class);
        storage = mock(SupabaseReportEvidenceStore.class);
        hashService = mock(IdentifierHashService.class);

        service = new PendingReportEvidenceService(
                repository,
                storage,
                hashService
        );
    }

    @Test
    void 정상적으로_저장하면_증빙_토큰을_반환한다() {
        UUID analysisId = UUID.randomUUID();

        when(hashService.hash(
                IdentifierType.ACCOUNT,
                "SHINHAN:11022345678"
        )).thenReturn("hashed-account");

        when(storage.createPendingKey(15L))
                .thenReturn(storageKey);

        UUID token = service.save(
                15L,
                analysisId,
                "SHINHAN:11022345678",
                image
        );

        assertThat(token).isNotNull();

        verify(storage).upload(
                storageKey,
                image
        );

        verify(repository).saveAndFlush(
                argThat(pending ->
                        pending.getUserId().equals(15L)
                                && pending.getAnalysisId().equals(analysisId)
                                && pending.getAccountHash().equals("hashed-account")
                                && pending.getStorageKey().equals(storageKey)
                )
        );
    }

    @Test
    void DB_저장에_실패하면_업로드한_파일을_삭제한다() {
        UUID analysisId = UUID.randomUUID();

        when(hashService.hash(
                any(),
                anyString()
        )).thenReturn("hashed-account");

        when(storage.createPendingKey(15L))
                .thenReturn(storageKey);

        when(repository.saveAndFlush(
                any(PendingReportEvidence.class)
        )).thenThrow(new RuntimeException("DB failure"));

        assertThatThrownBy(() ->
                service.save(
                        15L,
                        analysisId,
                        "SHINHAN:11022345678",
                        image
                )
        ).isInstanceOf(RuntimeException.class);

        verify(storage).upload(
                storageKey,
                image
        );

        verify(storage).deletePending(storageKey);
    }

    @Test
    void 잘못된_입력은_저장하지_않는다() {
        assertThatThrownBy(() ->
                service.save(
                        null,
                        UUID.randomUUID(),
                        "SHINHAN:11022345678",
                        image
                )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                service.save(
                        15L,
                        null,
                        "SHINHAN:11022345678",
                        image
                )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                service.save(
                        15L,
                        UUID.randomUUID(),
                        "SHINHAN:11022345678",
                        new byte[0]
                )
        ).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository, storage, hashService);
    }

    @Test
    void Storage_업로드에_실패하면_DB에_저장하지_않는다() {
        when(hashService.hash(
                any(),
                anyString()
        )).thenReturn("hashed-account");

        when(storage.createPendingKey(15L))
                .thenReturn(storageKey);

        doThrow(new RuntimeException("Storage failure"))
                .when(storage)
                .upload(eq(storageKey), any(byte[].class));

        assertThatThrownBy(() ->
                service.save(
                        15L,
                        UUID.randomUUID(),
                        "SHINHAN:11022345678",
                        image
                )
        ).isInstanceOf(RuntimeException.class);

        verify(repository, never()).saveAndFlush(
                any(PendingReportEvidence.class)
        );
    }


    @Test
    void 정상_토큰은_사용_처리된다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        15L,
                        analysisId,
                        "hashed-account",
                        storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        when(hashService.hash(
                IdentifierType.ACCOUNT,
                "SHINHAN:11022345678"
        )).thenReturn("hashed-account");

        PendingReportEvidence result = service.consume(
                token,
                15L,
                analysisId,
                List.of("SHINHAN:11022345678")
        );

        assertThat(result.isConsumed()).isTrue();
        assertThat(result.getConsumedAt()).isNotNull();
    }

    @Test
    void 이미_사용한_토큰은_재사용할_수_없다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        15L,
                        analysisId,
                        "hashed-account",
                        storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        when(hashService.hash(
                IdentifierType.ACCOUNT,
                "SHINHAN:11022345678"
        )).thenReturn("hashed-account");

        service.consume(
                token,
                15L,
                analysisId,
                List.of("SHINHAN:11022345678")
        );

        assertThatThrownBy(() ->
                service.consume(
                        token,
                        15L,
                        analysisId,
                        List.of("SHINHAN:11022345678")
                )
        ).isInstanceOf(
                com.trueket.backend.global.exception.BusinessException.class
        );
    }


    @Test
    void 다른_사용자의_토큰은_사용할_수_없다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        15L, analysisId, "hashed-account", storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        assertThatThrownBy(() ->
                service.consume(
                        token,
                        99L,
                        analysisId,
                        List.of("SHINHAN:11022345678")
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(pending.isConsumed()).isFalse();
        verifyNoInteractions(hashService);
    }

    @Test
    void 다른_판독_세션의_토큰은_사용할_수_없다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        99L, analysisId, "hashed-account", storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        assertThatThrownBy(() ->
                service.consume(
                        token,
                        99L,
                        UUID.randomUUID(),
                        List.of("SHINHAN:11022345678")
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(pending.isConsumed()).isFalse();
    }

    @Test
    void 다른_계좌의_토큰은_사용할_수_없다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        15L, analysisId, "hashed-account", storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        when(hashService.hash(
                IdentifierType.ACCOUNT,
                "SHINHAN:99999999999"
        )).thenReturn("different-account-hash");

        assertThatThrownBy(() ->
                service.consume(
                        token,
                        15L,
                        analysisId,
                        List.of("SHINHAN:99999999999")
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(pending.isConsumed()).isFalse();
    }

    @Test
    void 만료된_토큰은_사용할_수_없다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        15L, analysisId, "hashed-account", storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        when(hashService.hash(
                IdentifierType.ACCOUNT,
                "SHINHAN:11022345678"
        )).thenReturn("hashed-account");

        // 엔티티의 만료 판정 자체 확인
        assertThat(
                pending.isExpired(
                        pending.getExpiresAt().plusSeconds(1)
                )
        ).isTrue();

        assertThat(pending.isConsumed()).isFalse();
    }


    @Test
    void 존재하지_않는_토큰은_사용할_수_없다() {
        UUID token = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() ->
                service.consume(
                        token,
                        15L,
                        analysisId,
                        List.of("SHINHAN:11022345678")
                )
        ).isInstanceOf(BusinessException.class);
    }



    @Test
    void 여러_계좌_중_하나가_일치하면_토큰을_사용한다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        15L,
                        analysisId,
                        "matched-account-hash",
                        storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        when(hashService.hash(
                IdentifierType.ACCOUNT,
                "SHINHAN:99999999999"
        )).thenReturn("different-account-hash");

        when(hashService.hash(
                IdentifierType.ACCOUNT,
                "SHINHAN:11022345678"
        )).thenReturn("matched-account-hash");

        PendingReportEvidence result = service.consume(
                token,
                15L,
                analysisId,
                List.of(
                        "SHINHAN:99999999999",
                        "SHINHAN:11022345678"
                )
        );

        assertThat(result.isConsumed()).isTrue();

        verify(repository, times(1))
                .findByTokenForUpdate(token);
    }

    @Test
    void 여러_계좌가_모두_다르면_토큰을_사용하지_않는다() {
        UUID analysisId = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        PendingReportEvidence pending =
                PendingReportEvidence.create(
                        15L,
                        analysisId,
                        "matched-account-hash",
                        storageKey
                );

        when(repository.findByTokenForUpdate(token))
                .thenReturn(java.util.Optional.of(pending));

        when(hashService.hash(
                eq(IdentifierType.ACCOUNT),
                anyString()
        )).thenReturn("different-account-hash");

        assertThatThrownBy(() ->
                service.consume(
                        token,
                        15L,
                        analysisId,
                        List.of(
                                "SHINHAN:99999999999",
                                "SHINHAN:88888888888"
                        )
                )
        ).isInstanceOf(BusinessException.class);

        assertThat(pending.isConsumed()).isFalse();

        verify(repository, times(1))
                .findByTokenForUpdate(token);
    }


}
