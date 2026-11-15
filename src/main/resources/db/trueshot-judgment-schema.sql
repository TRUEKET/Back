-- 백엔드 실행 전에 적용한다. 기존 임시 요청에는 분석 결과를 추정해 채우지 않는다.
BEGIN;
ALTER TABLE public.trueshot_photos ADD COLUMN IF NOT EXISTS hand_analysis jsonb;
ALTER TABLE public.trueshot_requests ADD COLUMN IF NOT EXISTS judgment_result jsonb;
COMMIT;
