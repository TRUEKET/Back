# 트루샷 AI·CV 및 사진 삭제 연동

AI·CV 담당자와 회원 탈퇴 담당자가 연결할 계약을 정리한 문서다. 요청·응답 전체와 실행 설정은 [트루샷 API](trueshot-api.md)를 참고한다. 구현된 동작과 합의가 필요한 동작을 구분한다.

## 1. 사진 제출

1·2차 모두 `POST /s/{token}/photo`, multipart의 `n=1/2`, `photo=파일`이다. JPEG·PNG 최대 5MB이고 인증을 시작한 판매자 세션과 CSRF 토큰이 필요하다.

```json
{"number":1,"phase":"FIRST_MASKING","accepted":true,"deadline":null,"retryReasons":[]}
```

```json
{"number":2,"phase":"JUDGING","accepted":true,"deadline":null,"retryReasons":[]}
```

첫 사진 실패는 `accepted:false`, `retryReasons`와 갱신된 `deadline`을 반환한다. 두 번째의 접수 성공은 인증 성공을 뜻하지 않는다. 자세한 재촬영·오류 예시는 [사진 제출](trueshot-api.md#3-사진-제출)을 참고한다.

## 2. 임시 미션·CV 연결 조건

첫 미션은 종이에 영문·숫자 4자리 인증코드를 쓰고 예매 화면과 함께 찍는 것이다. 현재 OpenAI는 코드 표시·일치·화질만 확인하며 공연명·날짜·좌석·티켓 진위를 판정하지 않는다.

**두 번째 미션과 통과 조건은 아직 정해지지 않았다. 아래 목록은 흐름 구현·테스트를 위해 임시로 넣은 값이며, PM 확정 후 미션 목록·문구·CV 통과 조건을 함께 수정할 예정이다.** 현재 코드는 첫 사진 가림 확인 후 왼쪽/오른쪽 × 브이/엄지 조합을 생성한다.

| 임시 미션 문장 | 임시 확인 항목, 통과 기준 미확정·CV 미구현 |
| --- | --- |
| 예매 화면 왼쪽에 손가락 브이를 하고 촬영해 주세요. | 화면 왼쪽의 브이 |
| 예매 화면 오른쪽에 손가락 브이를 하고 촬영해 주세요. | 화면 오른쪽의 브이 |
| 예매 화면 왼쪽에 엄지를 들고 촬영해 주세요. | 화면 왼쪽의 엄지 들기 |
| 예매 화면 오른쪽에 엄지를 들고 촬영해 주세요. | 화면 오른쪽의 엄지 들기 |

저장하는 미션은 자연어 `instruction_2`다. 별도 미션 ID·동작 코드·위치 코드 컬럼은 없다. CV 담당자와 아래를 합의해야 한다.

- 미션을 구조화된 코드로 전달·저장할지 여부
- 화면 검출, 손의 위치 기준점, 반전 이미지 처리
- 브이·엄지 분류 기준과 신뢰도 임계값
- 코드·화질·손동작·위치 결과를 합치는 최종 판정 기준

미션 공개 시각은 `instruction_2_revealed_at`, 두 번째 마감은 공개 + 1분인 `deadline_2`다. 전체 요청 30분 기한도 적용된다. 두 번째 사진은 한 번만 접수한다.

## 3. AI 호출·결과 저장 계약

OpenAI를 사용한다. `OPENAI_API_KEY`와 모델 설정을 대화 판독과 공유하지만, 트루샷용 지시와 스키마를 별도로 전달한다.

| 호출 시점 | 현재 처리·저장 |
| --- | --- |
| 첫 사진 업로드 | 코드·화질 검사. 통과 시 첫 사진 행과 `first_verified_at` 저장 |
| 첫 사진 가림 요청 | 개인정보 영역 탐지. 서버가 실제 가림·Storage 저장 |
| 판매자 가림 확인 | AI 호출 없이 추가 가림 저장·두 번째 미션 공개 |
| 두 번째 업로드 | 저장 후 `second_received_at`, `JUDGING` 기록. 최종 AI·CV 호출 미구현 |

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
| 최종 단계 | `SHARE_WAITING`, `COMPLETED`, `FAILED` 정의됨. 성공 판정·공유 연결은 미완료 |
| `trueshot_photos.mask_status` | `PENDING`, `READY`, `FAILED`. 인증 결과와 별개인 가림 상태 |

`judged_at`, `photos_shared_at`은 준비된 컬럼이지만 최종 판정·전송 기능에서 아직 기록하지 않는다.

### 실패 사유

`trueshot_requests.fail_reasons`는 현재 JSONB 문자열 배열이다.

```json
["두 번째 사진이 시간 내에 전송되지 않음"]
```

구현된 사유는 `30분 안에 시작하지 않음`, `30분 안에 인증이 완료되지 않음`, `첫 번째 사진이 시간 내에 전송되지 않음`, `두 번째 사진이 시간 내에 전송되지 않음`이다.

최종 AI·CV의 항목별 체크리스트 저장 형식과 콜백 API는 아직 없다. 별도 결과 필드·테이블·내부 호출 계약을 합의해야 한다. 기존 문자열 배열에 임의의 객체를 섞어 저장하지 않는다. 구매자에게는 구체적인 체크 결과, 판매자에게는 시간 초과 또는 지시 불일치 정도만 노출하는 정책도 연결해야 한다.

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
| 최종 판정 직후 | 원본 즉시 삭제가 정책 목표. 최종 판정 미구현으로 연결되지 않음 |
| 7일 보관 만료 | 원본·가림 사진·요청 전체 정리 미구현 |

CV는 삭제 전에 두 원본을 읽어야 한다. 가린 사진으로 최종 판정 원본을 대체하지 않는다. 최종 결과 저장과 원본 삭제·재시도 경로를 함께 연결해야 하며 삭제 성공 전에 키를 잃으면 안 된다.

회원 탈퇴 담당자는 `masked_image`, `original_storage_key` 두 컬럼을 모두 수집해야 한다. 인증 실패 정리 스케줄러와 회원 탈퇴는 별도 경로이며, 회원 탈퇴용 백그라운드 삭제 큐가 있는 것은 아니다.
