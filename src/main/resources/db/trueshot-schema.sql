-- JPA 애플리케이션을 실행하기 전에 기존 Supabase DB에 검토 후 적용한다.
-- 전체 스키마 생성문이 아니라 기존 트루샷 테이블 두 개를 수정하는 SQL이다.
BEGIN;

ALTER TABLE public.trueshot_requests
    ADD COLUMN phase varchar NOT NULL DEFAULT 'WAITING',
    ADD COLUMN purge_at timestamptz,
    ADD COLUMN started_at timestamptz,
    ADD COLUMN first_verified_at timestamptz,
    ADD COLUMN instruction_2_revealed_at timestamptz,
    ADD COLUMN second_received_at timestamptz,
    ADD COLUMN judged_at timestamptz,
    ADD COLUMN photos_shared_at timestamptz;

UPDATE public.trueshot_requests
SET purge_at = created_at + interval '7 days',
    phase = CASE
        WHEN status = 'PASSED' THEN 'COMPLETED'
        WHEN status IN ('FAILED', 'EXPIRED') THEN 'FAILED'
        WHEN status = 'IN_PROGRESS' THEN 'FIRST_CHECKING'
        ELSE 'WAITING'
    END,
    status = CASE WHEN status = 'EXPIRED' THEN 'FAILED' ELSE status END;

ALTER TABLE public.trueshot_requests
    ALTER COLUMN purge_at SET NOT NULL,
    DROP CONSTRAINT IF EXISTS trueshot_requests_status_check,
    ADD CONSTRAINT trueshot_requests_status_check
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'PASSED', 'FAILED')),
    ADD CONSTRAINT trueshot_requests_phase_check
        CHECK (phase IN ('WAITING', 'FIRST_CHECKING', 'FIRST_MASKING',
                        'SECOND_WAITING', 'JUDGING', 'SHARE_WAITING',
                        'COMPLETED', 'FAILED'));

ALTER TABLE public.trueshot_photos
    ALTER COLUMN masked_image DROP NOT NULL,
    ADD COLUMN mask_status varchar NOT NULL DEFAULT 'READY',
    ADD COLUMN original_storage_key text,
    ADD CONSTRAINT trueshot_photos_mask_status_check
        CHECK (mask_status IN ('PENDING', 'READY', 'FAILED')),
    ADD CONSTRAINT trueshot_photos_masked_image_check
        CHECK (mask_status <> 'READY' OR masked_image IS NOT NULL);

-- (request_id, n) 유일 제약은 기존 Supabase 테이블에 이미 있으므로 다시 만들지 않는다.

CREATE INDEX ix_trueshot_requests_user_created
    ON public.trueshot_requests (user_id, created_at DESC);
CREATE INDEX ix_trueshot_requests_purge_at
    ON public.trueshot_requests (purge_at);

COMMIT;
