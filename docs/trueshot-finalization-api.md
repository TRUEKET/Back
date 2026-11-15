# 트루샷 최종 판정·사진 공유 API (#49)

## 실행 준비

기존 OpenAI 및 Private Supabase Storage 설정을 사용한다. `src/main/resources/db/trueshot-judgment-schema.sql`을 서버 실행 전에 적용한다. 새 컬럼은 `trueshot_photos.hand_analysis`, `trueshot_requests.judgment_result` 두 JSONB이며 기존 데이터는 변경하지 않는다. 자동 마이그레이션은 없고 이 작업에서 실제 DB에는 적용하지 않았다.

## 2차 사진 제출

`POST /s/{token}/photo`, multipart: `n=2`, `photo=사진`, `analysis=application/json`.

```json
{"imageWidth":1000,"imageHeight":800,"hands":[{"x":300,"y":420,"gesture":"Victory","confidence":0.96}]}
```

첫 사진(n=1)에는 analysis가 필요 없다. 2차에서는 필수이며 손 없음은 빈 hands 배열을 보낸다. 사진과 좌표는 회전을 적용한 동일 canvas 기준이다. 검지 끝·신뢰도·EXIF 계약은 [손 분석 API](hand-analyze-api.md)와 같다.

접수 트랜잭션에서 파일과 분석 입력을 저장한 뒤 JUDGING으로 바꾼다. 그 다음 별도 트랜잭션에서 서버 저장 미션과 비교한다. 별도 손 분석 호출은 없으며 클라이언트 통과 값을 받지 않는다. 접수한 원본과 MediaPipe 입력으로 서버에서 분석·판정한다.

```json
{"number":2,"phase":"SHARE_WAITING","accepted":true,"deadline":null,"retryReasons":[]}
```

`accepted`는 사진 접수 여부다. 미션 불일치면 FAILED, 성공하고 두 사진의 가림이 준비되면 SHARE_WAITING, 성공했으나 가림이 불확실하면 COMPLETED로 결과만 제공한다. 모델·Storage 일시 장애(503) 시에는 JUDGING으로 접수 응답을 반환한다. 클라이언트는 같은 사진을 다시 업로드하지 않고 결과만 조회한다. 판정은 서버에서 자동 재시도한다.

중복 2차 제출은 409. 분석 입력 누락·잘못된 크기·좌표 등은 400이며 제출을 소비하지 않는다. 1분 마감은 서버 수신 시각으로 검사한다. 전체 30분 기한은 분석 도중에도 적용한다.

## 최종 판정 기준·저장

- 첫 사진의 기존 코드·화질 확인 완료(first_verified_at)를 요구한다. 두 번째 사진에 인증코드 재노출을 추가 요구하지 않는다.
- 서버가 찾은 확실한 예매 화면과 프론트 MediaPipe 값으로 확정된 미션을 비교한다. 같은 LLM 호출에서 2차 사진의 예매 화면 내용을 알아볼 수 있는 화질인지 clear로 별도 확인하며, clear=false면 미션이 일치해도 실패 처리한다.
- 브이 신뢰도 0.8 이상, 가리키기 근접 거리 화면 짧은 변의 10%, 위치 미션은 같은 손에서 동작과 방향 모두 만족한다.
- 손 없음·화면 없음·화면 불확실·동작/위치 불일치는 실패다. 외부 서비스 장애는 판정 대기 기한 안에서는 JUDGING으로 재시도하고, 기한을 넘기면 ANALYSIS_TIMEOUT으로 실패 처리한다.
- 저장된 judgment_result는 첫 사진 확인 여부, 분석 상태, 2차 화질 관측값(secondPhotoClear), 미션 비교 결과, 실패 사유를 포함한다. OCR 원문과 내부 Storage 키는 포함하지 않는다.
- 가림 처리 장애가 나면 이미 계산한 결과를 저장해 두고 다음 재시도에서 화면 분석을 생략한다. 판정이 완료되기 전에는 구매자 상세에 임시 체크 결과를 노출하지 않는다.

최종 성공은 이 촬영 미션을 만족했다는 의미다. 클라이언트 손 모양·좌표를 서버가 재인식하거나 사진 두 장의 티켓 동일성·진위·소유권을 보장하는 구현은 포함하지 않는다.

## 판매자 결과·자동 재시도

시작한 판매자 세션과 CSRF 토큰을 유지한다(GET은 CSRF 불필요).

- `GET /s/{token}/result`: 현재 상태·공유 가능 여부 조회

```json
{"status":"PASSED","phase":"SHARE_WAITING","judgedAt":"2026-11-15T10:00:00Z","photosSharedAt":null,"canShare":true,"message":"사진 확인을 완료했어요."}
```

판매자 응답에 화면 표시용 checks와 failReasons를 포함한다. 내부 판단 JSON 원문·OCR 텍스트·Storage 키는 노출하지 않는다. JUDGING인 요청은 기본 10초 간격의 전용 작업에서 자동 재시도한다. 접수 시 저장한 판정 마감(기본 60초, 전체 요청 마감이 더 빠르면 그 시각)을 넘기면 분석 시간 초과로 종료한다. 요청 행 잠금으로 중복 실행을 직렬화하고 완료한 판정을 반복하지 않는다. 수동 재시도 API는 제공하지 않으며 프론트는 결과 조회만 사용한다.

## 가림 미리보기·사진 공유

`GET /s/{token}/preview/1`, `GET /s/{token}/preview/2`에서 가린 JPEG만 반환한다. 첫 사진 가림 단계에서는 1번만, SHARE_WAITING에서는 두 사진을 볼 수 있다. 원본 다운로드 경로는 제공하지 않는다.

`POST /s/{token}/share`, JSON:

```json
{"sharePhotos":true,"boxes":[{"x":0.2,"y":0.3,"width":0.1,"height":0.1}]}
```

boxes는 두 번째 사진에 추가할 가림 영역이며 0~1 비율, 최대 300개다. 추가 영역이 없으면 빈 배열이다. 기존 자동 가림을 지울 수 없고 가린 사진 위에만 덧칠한다. 두 사진이 모두 READY일 때만 공유할 수 있다.

공유 동의 후 photos_shared_at을 기록하고 COMPLETED로 전환한다. `sharePhotos:false, boxes:[]`면 공유 없이 COMPLETED로 전환한다. 완료 후 재전송은 기존 결정을 유지하며 파일·공유 시각을 변경하지 않는다. 추가 가림 저장 실패 시 동의를 기록하지 않으므로 재시도할 수 있다.

공유 기한은 기존 요청의 expires_at(생성+30분)이다. 기한이 지나면 PASSED 결과는 유지하고 사진 공유는 차단한다. 최초 늦은 공유 시도는 410, 이후 완료된 요청 재전송은 기존 결과를 반환한다.

## 구매자 조회

로그인한 요청 소유자만 접근한다.

- `GET /trueshot/{id}`: judgedAt, judgmentResult, photosSharedAt, photos를 기존 응답에 추가한다.
- `GET /trueshot/{id}/photos/1`, `/photos/2`: 공유에 동의한 가림 JPEG를 반환한다.

photos 예시:

```json
[{"number":1,"url":"/trueshot/123/photos/1"},{"number":2,"url":"/trueshot/123/photos/2"}]
```

공유 전·공유 거절·실패·가림 미완료·보관 기한 만료 상태에서는 사진 목록이 비고 다운로드는 404다. 다른 구매자도 404, 비로그인은 401이다. 모든 사진 및 결과 응답은 Cache-Control: no-store다. URL은 인증이 필요한 서버 경로이며 공개 Storage URL이 아니다.

## 원본 정리·남은 검증

최종 성공/실패 결과를 확정한 뒤 원본 두 장의 삭제를 즉시 시도한다. 삭제 오류가 나도 결과는 유지한다. 파일 키와 삭제 시각은 삭제 성공 때만 갱신하고, 기존 주기 작업에서 PASSED/FAILED 원본을 재시도한다. 가림/판정에 필요한 원본을 처리 도중 지우지 않는다.

자동 테스트는 H2와 대체 모델/Storage로 상태·권한·동의·만료·일시 장애·삭제 재시도·동시 판정을 검증한다. 실제 React·모바일 회전·LLM 화면 좌표·가림 정확도 및 실제 Storage 통합은 실연동에서 검증해야 한다. 7일 보관 만료 전체 삭제는 #51에서 연결했다. [보관 만료 정리](trueshot-retention.md)를 참고한다.

## 판매자 화면 체크 결과

사진 제출 응답에 기존 필드를 유지하고 `checks`, `failReasons`를 추가한다. checks는 제출한 사진에 해당하는 항목만 포함한다. GET result, POST share 응답에는 두 사진의 체크 항목을 함께 제공한다.

첫 사진 코드 불일치 응답 예시:

```json
{
  "number":1,
  "phase":"FIRST_CHECKING",
  "accepted":false,
  "deadline":"2026-11-15T12:03:00Z",
  "retryReasons":["인증코드가 잘 보이게 다시 찍어 주세요"],
  "checks":[
    {"photoNumber":1,"code":"PHOTO_QUALITY","status":"PASSED","message":"내용을 알아볼 수 있는 화질이에요."},
    {"photoNumber":1,"code":"AUTH_CODE","status":"FAILED","message":"인증코드가 일치하지 않아요."}
  ],
  "failReasons":["인증코드가 일치하지 않아요."]
}
```

최종 결과의 체크 목록은 첫 사진 PHOTO_QUALITY·AUTH_CODE, 두 번째 사진 PHOTO_QUALITY·MISSION이다. 배열 위치에 의존하지 말고 photoNumber와 code로 항목을 식별한다. 두 번째 사진에 코드 재노출을 요구하지 않으므로 AUTH_CODE 항목을 추가하지 않는다.

| status | 의미 | 화면 표시 |
| --- | --- | --- |
| PASSED | 실제 확인한 조건을 만족 | 성공 표시 |
| FAILED | 실제 확인한 조건을 만족하지 않음 | 실패 표시 |
| PENDING | 2차 사진 접수 후 판정·가림 처리 중 | 대기 표시 |
| NOT_CHECKED | 검사하지 않았거나 확인 가능한 기록이 없음 | 미확인 표시 |

첫 사진은 통과했을 때만 사진 행과 first_verified_at을 저장하므로, 재촬영 거절 결과는 제출 응답에서 확인한다. 첫 사진 재촬영 중 페이지를 새로고침해 결과 조회를 하면 이전 실패 항목을 복원하지 않고 NOT_CHECKED로 반환한다. 최종 첫 사진 성공 체크는 검증된 first_verified_at에 근거한다.

손이 없어 LLM 호출을 생략한 경우 2차 화질은 NOT_CHECKED, 미션은 FAILED다. 화면 불확실로 미션 비교를 못 하면 미션은 NOT_CHECKED이며 failReasons에서 확인 불가 사유를 제공한다. 이전 판정 기록에 화질 관측값이 없으면 통과로 추정하지 않고 NOT_CHECKED로 표시한다.

AI·Storage 일시 장애 시 PENDING을 반환하고 failReasons는 비워 둔다. 가림 재시도를 위해 내부에 저장한 중간 미션 결과도 최종 체크 통과로 노출하지 않는다. 시간 초과는 결과 조회 시 주기 작업 실행 전에도 FAILED와 해당 실패 사유를 반환하되, 하지 않은 사진 검사를 FAILED로 만들지 않는다.

retryReasons는 첫 사진 재촬영 안내, failReasons는 해당 응답의 실패 사유 목록이다. 첫 사진의 failReasons는 재촬영 실패 안내이며 요청 전체가 최종 FAILED라는 의미는 아니다. 최종 판정 상태는 최상위 status/phase로 판단한다.

이 보완은 기존 judgment_result JSON 안에 관측값을 저장하므로 새 DB 컬럼·SQL 적용은 필요 없다. 화질 관측의 실제 사진 정확도는 프론트 연동에서 검증한다.

## 판정 대기 시간·프론트 로딩 종료

- 접수 직후 최초 판정을 실행한다. 일시 장애 시 기본 10초 간격으로 대기 요청을 다시 확인한다. 각 주기는 이전 작업 완료 후 시작하므로 처리량과 외부 서비스 응답에 따라 실제 간격이 길어질 수 있다.
- 판정 마감은 두 번째 사진의 서버 수신 시각 + 기본 60초와 전체 요청 expires_at 중 빠른 값이다. 재시도해도 연장하지 않는다. 생성 시 정한 마감은 기존 judgment_result의 deadline에 저장한다.
- 결과 조회에 judgmentDeadline, pollAfterMillis, failureCode를 추가한다. JUDGING일 때 pollAfterMillis는 2000이며 그 외에는 null이다. 사진 제출 응답도 JUDGING이면 기존 deadline 필드에 판정 마감을 반환한다.
- 프론트는 JUDGING 동안 약 2초마다 GET result로 조회하고, 다른 phase가 되면 로딩·조회 타이머를 종료한다. message를 그대로 표시하거나 문구 분기 자료로 사용할 수 있다. 10초 이상 대기하면 지연 안내 문구를 반환한다.
- 마감이 지난 결과 조회는 작업 실행을 기다리지 않고 FAILED·failureCode=ANALYSIS_TIMEOUT·안내 사유를 반환한다. 처리 중이던 외부 I/O가 즉시 취소되는 하드 타임아웃은 아니며, 실제 상태 저장·원본 정리는 해당 작업 또는 다음 재시도에서 수행한다.
- 마감 이후 도착한 성공 응답도 통과시키지 않는다. 가림 대기 중 임시로 저장한 passed=true도 시간 초과 확정 시 제거한다. 손 모양 불일치·저화질로 오인시키지 않도록 미완료 2차 체크는 NOT_CHECKED로 표시한다.

결과 조회의 추가 필드 예시:

```json
{
  "judgmentDeadline":"2026-11-15T12:01:00Z",
  "pollAfterMillis":2000,
  "failureCode":null
}
```

분석 시간 초과 시 status와 phase는 FAILED, pollAfterMillis는 null, failureCode는 ANALYSIS_TIMEOUT이다. 새 인증 링크를 요청하도록 안내하며 수동 재시도 API나 사진 재업로드로 기존 요청을 되살리지 않는다.

설정값:

| 환경변수 | 기본값 | 의미 |
| --- | --- | --- |
| TRUESHOT_JUDGMENT_RETRY_DELAY_MS | 10000 | 자동 재시도 작업 간격(ms) |
| TRUESHOT_JUDGMENT_MAX_WAIT | 60s | 사진 접수 후 최대 판정 대기 시간 |

설정 변경은 새로 접수한 사진에 적용한다. 진행 중 요청은 저장된 마감을 유지한다. 이전 기록에 마감이 없으면 수신 시각 + 60초를 적용한다. 새 DB 컬럼·SQL 적용은 필요 없다.
