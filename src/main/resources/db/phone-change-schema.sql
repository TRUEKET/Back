-- Existing SIGNUP / REPORT purposes remain valid.
BEGIN;

ALTER TABLE public.phone_verifications
    ALTER COLUMN purpose TYPE varchar(40);

ALTER TABLE public.phone_verifications
    DROP CONSTRAINT IF EXISTS phone_verifications_purpose_check;

ALTER TABLE public.phone_verifications
    ADD CONSTRAINT phone_verifications_purpose_check
    CHECK (purpose IN ('SIGNUP', 'REPORT') OR purpose ~ '^PHONE_CHANGE:[0-9]+$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_phone_verifications_phone_purpose
    ON public.phone_verifications (phone_hash, purpose);

COMMIT;
