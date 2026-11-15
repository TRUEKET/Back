# 트루샷 AI·DB·Storage 안내

프론트 요청 형식은 [트루샷 API](trueshot-api.md), 손 좌표 입력은 [MediaPipe 연동](hand-analyze-api.md), 판정·공유 화면 처리는 [최종 판정 API](trueshot-finalization-api.md)를 참고한다.

## 실행 설정

| 환경변수 | 기본값·용도 |
| --- | --- |
| OPENAI_API_KEY | OpenAI 호출 키 |
| OPENAI_MODEL | gpt-6-luna |
| OPENAI_REASONING_EFFORT | low |
| OPENAI_MAX_OUTPUT_TOKENS | 8192 |
| SUPABASE_URL | Supabase 프로젝트 URL |
| SUPABASE_SERVICE_ROLE_KEY | 서버 전용 Storage 키 |
| TRUESHOT_ORIGINAL_STORAGE_BUCKET | trueshot-photos, Private 원본 버킷 |
| TRUESHOT_MASKED_STORAGE_BUCKET | trueshot-photos, Private 가림 버킷 |
| TRUESHOT_JUDGMENT_RETRY_DELAY_MS | 10000, 판정 자동 재시도 작업 간격(ms) |
| TRUESHOT_JUDGMENT_MAX_WAIT | 60s, 2차 사진 접수 후 최대 판정 대기 시간 |

서비스 역할 키와 OpenAI 키는 서버에서만 사용한다. 판정 대기 시간 설정은 새로 접수한 사진에 적용하며 진행 중 요청의 저장된 마감은 유지한다. 이전 기록에 판정 마감이 없으면 수신 시각 + 60초를 적용한다. 원본 삭제 재시도와 보관 만료 정리는 1분 주기다.

환경변수를 등록해도 SQL이 자동 실행되지는 않는다. DB 스키마는 수동으로 적용하며 서버는 `ddl-auto=validate`로 확인한다. 아래 SQL은 기존 트루샷 테이블을 변경하는 파일이다. 기존 스키마와 적용 여부를 확인한 후 필요한 SQL만 실행한다.

| SQL 파일 (`src/main/resources/db/`) | 용도 |
| --- | --- |
| trueshot-schema.sql | 기존 요청·사진 테이블의 단계·보관 기한·원본 관리 등 컬럼 변경 |
| trueshot-second-mission-schema.sql | mission_2_type, mission_2_position 컬럼 |
| trueshot-judgment-schema.sql | hand_analysis, judgment_result JSONB 컬럼 |

미션 정보가 없는 과거 진행 중 요청을 새 미션으로 추측해 채우지 않는다. 해당 요청은 새로 생성한다.

## AI 호출과 저장

손 모양·검지 끝 좌표는 React MediaPipe 결과를 사용하고, Spring Boot가 서버 저장 미션과 비교한다. 서버는 클라이언트의 통과 여부를 받지 않는다.

| 시점 | 처리 |
| --- | --- |
| 첫 사진 제출 | OpenAI로 인증코드 표시·일치·화질 확인. 통과 사진과 first_verified_at 저장 |
| 첫 사진 가림 | 개인정보 영역 탐지 후 서버가 실제 가림 사진 생성·Storage 저장 |
| 첫 가림 확인 | 추가 가림 저장·2차 미션 공개. AI 호출 없음 |
| 두 번째 사진 제출 | 원본·MediaPipe 입력 저장 → 예매 화면·화질 탐지 → 미션 비교 → 결과 저장 → 가림 → 최종 상태 확정 |

첫 사진 모델 응답은 `{"codeVisible":true,"codeMatches":true,"clear":true}`다. 통과하지 못한 사진은 저장하지 않고 재촬영 결과를 제출 응답으로 반환한다. 거절된 시도는 최종 실패 이력으로 누적하지 않는다.

개인정보 탐지 응답은 아래 형식이며 좌표는 0~1 비율이다.

```json
{"complete":true,"boxes":[{"x":0.1,"y":0.2,"width":0.2,"height":0.1}]}
```

불확실·영역 없음·좌표 오류이면 가림 상태를 FAILED로 두고 사진을 공개하지 않는다. 가림 사진 대신 원본을 제공하지 않는다.

2차 화면 탐지와 화질 확인은 같은 LLM 호출에서 수행한다. 손이 없으면 해당 호출을 생략하고 미션 실패로 처리한다. 가림 처리 일시 장애 시 계산한 결과를 저장해 다음 재시도에서 화면 분석을 반복하지 않는다.

## 미션 판정

세 유형을 같은 확률로 선택하며 위치 미션의 네 방향도 같은 확률로 선택한다.

| type | 조건 |
| --- | --- |
| GESTURE | Victory, confidence ≥ 0.8 |
| POINT_SCREEN | 검지 끝이 화면 내부 또는 화면 짧은 변의 10% 거리 이내 |
| GESTURE_POSITION | 같은 손의 Victory, confidence ≥ 0.8 및 지정한 LEFT/RIGHT/ABOVE/BELOW 방향 |

화면 경계는 방향 조건을 만족하지 않는다. 화면이 없거나 불확실하면 통과시키지 않는다. 2차 사진이 흐리면 미션이 일치해도 실패다. 가리키기는 검지 끝의 근접 여부를 비교하며 손가락 방향 벡터나 Pointing_Up 분류를 요구하지 않는다.

최종 성공은 촬영 미션을 만족했다는 뜻이다. 서버가 손 모양·좌표를 재인식하거나 두 사진의 티켓 동일성·진위·소유권을 확인하는 기능은 포함하지 않는다.

## 상태와 결과 데이터

| 항목 | 값·의미 |
| --- | --- |
| status | PENDING, IN_PROGRESS, PASSED, FAILED |
| 진행 phase | WAITING → FIRST_CHECKING → FIRST_MASKING → SECOND_WAITING → JUDGING |
| 최종 phase | 성공·두 사진 가림 완료면 SHARE_WAITING. 공유 동의/거절·기한 종료 또는 가림 실패 시 COMPLETED. 인증 실패면 FAILED |
| mask_status | PENDING, READY, FAILED. 인증 결과와 별개 |
| first_verified_at | 첫 사진 코드·화질 확인 완료 시각 |
| judged_at | 최종 판정 확정 시각 |
| photos_shared_at | 사진 공유에 동의한 시각. 거절·가림 실패·공유 시간 종료면 null |
| fail_reasons | 사용자 안내용 실패 사유 문자열 배열(JSONB) |
| judgment_result | 분석 상태·2차 화질·미션 비교·판정 마감·실패 사유 등 판정 결과(JSONB) |
| hand_analysis | 사진과 함께 받은 MediaPipe 입력(JSONB) |

판매자에게는 checks·failReasons와 화면 복구 필드만 제공한다. 원문 OCR·내부 판정 JSON·Storage 키는 노출하지 않는다. 구매자에게는 확정한 판정 결과와 공유에 동의한 사진만 제공한다.

## 사진 저장과 삭제

원본과 가림 사진 모두 Private 버킷을 사용한다. 파일 키는 `trueshots/{requestId}/{uuid}.jpg`이며 각 파일은 다른 UUID를 사용한다. DB에는 버킷을 제외한 상대 키만 저장하며 공개 Storage URL을 제공하지 않는다.

| 사진 필드 | 내용 |
| --- | --- |
| original_storage_key | JPEG로 정규화한 임시 원본 키 |
| masked_image | 가림 사진 키. 대기·실패면 null |
| mask_boxes | {"automatic":[…],"seller":[…]}. 가림 전 기본값은 [] |
| original_deleted_at | 원본 Storage 삭제 성공 시각 |

판정·가림에 필요한 원본은 처리 완료 전까지 보존한다. 최종 판정 후 원본 즉시 삭제, 실패 시 재시도, 회원 탈퇴 및 생성 후 7일 전체 정리는 [보관·삭제 정책](trueshot-retention.md)을 따른다.
