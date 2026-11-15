# 트루샷 AI·CV 및 사진 삭제 연동

AI·CV 담당자와 회원 탈퇴 담당자가 연결할 계약을 정리한 문서다. 요청·응답 전체와 실행 설정은 [트루샷 API](trueshot-api.md)를 참고한다. 구현된 동작과 합의가 필요한 동작을 구분한다.

2026-11-15 추가: React MediaPipe 결과와 사진을 받는 독립 `POST /hand-analyze`를 구현했다. [손 인식 연동 명세](hand-analyze-api.md)에 multipart·좌표·회전·응답·프론트 예제를 정리했다. 확정된 3개 미션 생성·저장·조건 비교를 지원하며, 비교 결과를 최종 인증 상태·공유에 연결했다. [최종 판정·공유 API](trueshot-finalization-api.md)를 참고한다.

## 1. 사진 제출

1·2차 모두 `POST /s/{token}/photo`, multipart의 `n=1/2`, `photo=파일`이다. JPEG·PNG 최대 5MB이고 인증을 시작한 판매자 세션과 CSRF 토큰이 필요하다.

```json
{"number":1,"phase":"FIRST_MASKING","accepted":true,"deadline":null,"retryReasons":[]}
```

```json
{"number":2,"phase":"JUDGING","accepted":true,"deadline":null,"retryReasons":[]}
```

첫 사진 실패는 `accepted:false`, `retryReasons`와 갱신된 `deadline`을 반환한다. 두 번째의 접수 성공은 인증 성공을 뜻하지 않는다. 자세한 재촬영·오류 예시는 [사진 제출](trueshot-api.md#3-사진-제출)을 참고한다.

## 2. 확정된 2차 미션

세 유형 중 하나를 같은 확률로 뽑는다. 손 모양은 브이(`Victory`)이며 엄지는 생성하지 않는다. 위치 미션 안에서는 4방향을 같은 확률로 뽑는다.

| type | 지시 | 비교 기준 |
| --- | --- | --- |
| GESTURE | 예매 화면 옆에서 브이를 하고 찍어 주세요. | Victory + confidence ≥ 0.8 |
| POINT_SCREEN | 손가락으로 예매 화면을 가리켜 주세요. | 검지 끝이 화면 내부 또는 화면 짧은 변의 10% 거리 이내 |
| GESTURE_POSITION | 브이를 예매 화면 위쪽/아래쪽/왼쪽/오른쪽에 두고 찍어 주세요. | 같은 손의 Victory + confidence ≥ 0.8 + 지정 방향 |

문구는 `instruction_2`, 종류는 `mission_2_type`, 위치는 `mission_2_position`에 저장한다. `first-mask/confirm` 응답과 구매자 상세에 `mission2:{type,gesture,position}`를 추가했다. 위치는 `LEFT/RIGHT/ABOVE/BELOW`, 위치 없는 미션은 null이다.

실행 전에 `src/main/resources/db/trueshot-second-mission-schema.sql`을 적용해야 한다. 기존 임시 미션을 새 유형으로 추측해 채우지 않으며, 이전 진행 중 요청은 새로 생성해야 한다.

`POST /hand-analyze`에 multipart `token`을 함께 보내면 시작한 판매자 세션과 SECOND_WAITING·만료를 검사하고, 서버 저장 미션과 비교한다. token을 생략하면 관찰값만 반환한다. 화면이 불확실하면 통과시키지 않는다. 다른 손의 동작과 위치를 섞어 통과시키지 않는다.

미션 공개 시각은 `instruction_2_revealed_at`, 두 번째 마감은 공개 + 1분인 `deadline_2`다. 전체 요청 30분 기한도 적용된다. 두 번째 사진은 한 번만 접수한다. 손 분석은 두 번째 사진 접수 전에 호출한다. 분석의 MATCHED는 최종 트루샷 PASSED가 아니다.

## 3. AI 호출·결과 저장 계약

OpenAI를 사용한다. `OPENAI_API_KEY`와 모델 설정을 대화 판독과 공유하지만, 트루샷용 지시와 스키마를 별도로 전달한다.

| 호출 시점 | 현재 처리·저장 |
| --- | --- |
| 첫 사진 업로드 | 코드·화질 검사. 통과 시 첫 사진 행과 `first_verified_at` 저장 |
| 첫 사진 가림 요청 | 개인정보 영역 탐지. 서버가 실제 가림·Storage 저장 |
| 판매자 가림 확인 | AI 호출 없이 추가 가림 저장·두 번째 미션 공개 |
| 두 번째 업로드 | 사진·MediaPipe 결과 저장 → 예매 화면 탐지·미션 비교 → 결과 저장 → 2차 가림 → 최종 상태 확정 |

첫 사진 모델 응답:

```json
{"codeVisible":true,"codeMatches":true,"clear":true}
```

이 세 항목은 별도 DB 필드에 저장하지 않는다. 실패 사유는 재촬영 응답 `retryReasons`에만 넣고 요청의 최종 실패 사유로 누적하지 않는다.

개인정보 탐지 응답:

```json
{"complete":true,"boxes":[{"x":0.1,"y":0.2,"width":0.2,"height":0.1}]}
```

영역은 0~1 비율이다. 불확실·영역 없음·좌표 오류이면 `mask_status=FAILED`, `masked_image=null`이며 사진을 공개하지 않는다. 실제 탐지 정확도는 아직 검증하지 않았다.

### 상태 필드

| 필드 | 값과 의미 |
| --- | --- |
| `trueshot_requests.status` | `PENDING`, `IN_PROGRESS`, `PASSED`, `FAILED` |
| `trueshot_requests.phase` | 현재 흐름은 `WAITING` → `FIRST_CHECKING` → `FIRST_MASKING` → `SECOND_WAITING` → `JUDGING` |
| 최종 단계 | 성공·두 사진 가림 완료 시 SHARE_WAITING. 판매자 동의/거절 또는 기한 종료 시 COMPLETED. 불일치 시 FAILED |
| `trueshot_photos.mask_status` | `PENDING`, `READY`, `FAILED`. 인증 결과와 별개인 가림 상태 |

`judged_at`은 최종 판정 시, `photos_shared_at`은 판매자가 사진 공유에 동의한 경우에만 기록한다. 가림 실패·공유 거절·공유 시간 종료는 사진을 공개하지 않는다.

### 실패 사유

`trueshot_requests.fail_reasons`는 현재 JSONB 문자열 배열이다.

```json
["두 번째 사진이 시간 내에 전송되지 않음"]
```

구현된 사유는 `30분 안에 시작하지 않음`, `30분 안에 인증이 완료되지 않음`, `첫 번째 사진이 시간 내에 전송되지 않음`, `두 번째 사진이 시간 내에 전송되지 않음`이다.

항목별 결과는 judgment_result JSONB, 실패 사유는 기존 fail_reasons 문자열 배열에 저장한다. 사진별 MediaPipe 입력은 hand_analysis JSONB에 보관한다. 구매자에게는 완료된 체크 결과, 판매자에게는 요약 결과만 제공한다. 클라이언트가 보낸 통과 여부는 사용하지 않는다.

## 4. Storage·삭제 계약

원본·가린 사진 모두 Private `trueshot-photos` 버킷을 쓴다. 파일 키는 `trueshots/{requestId}/{uuid}.jpg`이며 서로 다른 UUID를 사용한다. DB에는 내부 키만 저장하고 사진 구분은 `request_id`, `n=1/2`로 한다.

| DB 필드 | 저장 내용 |
| --- | --- |
| `trueshot_photos.original_storage_key` | AI·CV에 사용할 JPEG 정규화 임시 원본의 키 |
| `trueshot_photos.masked_image` | 가린 사진의 키. 대기·실패면 null |
| `trueshot_photos.mask_boxes` | `{"automatic":[…],"seller":[…]}`. 가림 전 기본값은 `[]` |
| `trueshot_photos.original_deleted_at` | 원본 Storage 삭제 성공 시각 |
| `trueshot_requests.purge_at` | 생성 + 7일. 전체 삭제 작업은 아직 미구현 |

| 상황 | 현재 동작 |
| --- | --- |
| 첫 코드·화질 검사 실패 | 파일·사진 행 저장 안 함 |
| 시간 초과 인증 실패 | 주기 작업에서 원본 삭제. 성공 후 키를 null로 바꾸고 삭제 시각 기록 |
| 원본 삭제 실패 | 1분 간격 주기 작업에서 재시도 |
| 회원 탈퇴 | DB 삭제 전 두 키 수집·잠금 → Storage API 삭제 → DB 행 삭제. Storage 실패 시 DB 삭제 중단, 요청 재시도 가능 |
| 최종 판정 직후 | 원본 즉시 삭제 시도. 실패 시 키를 보존하고 주기 작업으로 재시도 |
| 7일 보관 만료 | 원본·가림 사진·요청 전체 정리 미구현 |

CV는 삭제 전에 두 원본을 읽어야 한다. 가린 사진으로 최종 판정 원본을 대체하지 않는다. 최종 결과 저장 후 원본 삭제·재시도 경로를 연결했으며 삭제 성공 전에는 키를 보존한다.

회원 탈퇴 담당자는 `masked_image`, `original_storage_key` 두 컬럼을 모두 수집해야 한다. 인증 실패 정리 스케줄러와 회원 탈퇴는 별도 경로이며, 회원 탈퇴용 백그라운드 삭제 큐가 있는 것은 아니다.
