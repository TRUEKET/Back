-- 애플리케이션 시작 전에 적용한다. 기존 임시 미션에는 코드를 추정해 채우지 않는다.
BEGIN;
ALTER TABLE public.trueshot_requests
    ADD COLUMN IF NOT EXISTS mission_2_type varchar(255),
    ADD COLUMN IF NOT EXISTS mission_2_position varchar(255);
COMMIT;
