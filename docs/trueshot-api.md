# 트루샷 API

구매자 요청 생성부터 판매자 촬영·판정·사진 공유까지의 API 안내다.

- [2차 판정·결과 조회·사진 공유·화면 복구](trueshot-finalization-api.md)
- [MediaPipe 입력과 프론트 전송 예제](hand-analyze-api.md)
- [AI·DB·Storage 설정과 데이터 처리](trueshot-ai-cv.md)
- [7일 보관 및 자동 삭제](trueshot-retention.md)

## 공통 규칙

- 구매자 API는 로그인 세션이 필요하다.
- 판매자는 로그인하지 않는다. `POST /s/{token}/start` 이후에는 시작한 브라우저의 세션 쿠키를 유지해야 한다. 링크 토큰만으로 사진·미리보기에 접근할 수 없다.
- POST 요청에는 `GET /auth/csrf` 응답의 `headerName`과 `token`을 헤더로 전달한다. 로그인 후에는 CSRF 토큰을 다시 받는다.
- 프론트와 API의 출처가 다르면 쿠키 전달 설정도 필요하다.
- 응답은 `Cache-Control: no-store`다. 시각은 서버가 정한 ISO 8601 값이며 타이머는 반환된 마감 시각을 사용한다.
- 사진은 JPEG·PNG만, 최대 5MB(5 × 1024 × 1024바이트)다. 실제 형식과 해상도(가로·세로 각각 320픽셀 이상, 전체 4천만 픽셀 이하)를 검사하고 JPEG로 변환한다.

## API 목록 (13개)

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| POST | `/trueshot` | 구매자 요청 생성 |
| GET | `/trueshot` | 내 요청 목록 |
| GET | `/trueshot/{id}` | 내 요청 상세·진행 상황 |
| PATCH | `/trueshot/{id}` | 내 요청 제목 수정 |
| GET | `/trueshot/{id}/photos/{number}` | 구매자용 공유된 가림 사진 |
| GET | `/s/{token}` | 판매자 시작 전 안내 |
| POST | `/s/{token}/start` | 동의 기록·첫 촬영 시작 |
| POST | `/s/{token}/photo` | 첫 번째·두 번째 사진 제출 |
| POST | `/s/{token}/mask` | 첫 사진 자동 가림 생성 |
| GET | `/s/{token}/preview/{number}` | 판매자용 가린 사진 미리보기 |
| POST | `/s/{token}/first-mask/confirm` | 추가 가림 저장·두 번째 촬영 시작 |
| GET | `/s/{token}/result` | 판매자 결과·진행 상태·화면 복구 |
| POST | `/s/{token}/share` | 두 번째 추가 가림·사진 공유 동의/거절 |

## 1. 요청 생성·조회

### `POST /trueshot`

본문: `{}` 또는 `{"title":"공연 양도"}`. 제목은 최대 20자이며 비우면 `요청 N`을 사용한다. `201`과 요청 상세를 반환한다.

서버가 영문·숫자 4자리 `code`, 일회용 링크 토큰, 30분 `expiresAt`과 7일 보관 시각 `purgeAt`을 생성한다. `purgeAt`은 DB에 저장하는 값이며 현재 상세 응답에는 포함하지 않는다.

### `GET /trueshot`

내 요청을 최신순 배열로 반환한다. 항목은 `id`, `title`, `status`, `phase`, `createdAt`, `expiresAt`이다. 30분 요청 기한이 지나면 주기 작업 전 조회에서도 실패 상태로 보인다.

### `GET /trueshot/{id}`

내 요청 상세를 반환한다. 필드는 다음과 같다.

```text
id, title, code, sellerPath, status, phase, instruction2, mission2,
createdAt, expiresAt, startedAt, deadline1, deadline2,
photosSharedAt, failReasons, judgedAt, judgmentResult, photos
```

`sellerPath`는 `/s/{token}` 상대 경로다. `instruction2`는 두 번째 촬영을 시작하기 전까지 null이다. 다른 구매자의 요청은 `404`다.

### `PATCH /trueshot/{id}`

로그인한 구매자 본인의 요청 제목을 수정한다. 세션 쿠키와 `GET /auth/csrf`로 받은 CSRF 헤더가 필요하다.

```http
PATCH /trueshot/1
Content-Type: application/json
X-CSRF-TOKEN: 발급받은 토큰

{"title":"DAY6 티켓 인증"}
```

제목은 필수이며 최대 20자다. 누락·null·빈 문자열·공백만 입력·20자 초과는 400이며, 앞뒤 공백은 저장 시 제거한다. 길이는 공백 제거 전 입력을 기준으로 검사한다. 다른 구매자의 요청과 없는 요청은 모두 404, 미로그인은 401, CSRF 누락·불일치는 403이다.

성공 시 **200과 `GET /trueshot/{id}`와 동일한 요청 상세 JSON**을 반환한다. 프론트는 응답의 `title`로 화면을 갱신하면 된다. 대기·인증 진행·성공·실패·만료 상태에서 모두 수정할 수 있다. 인증코드·판매자 링크·상태·촬영 및 공유 기한은 변경하지 않는다. DB 스키마 변경은 없다.

## 2. 판매자 시작

### `GET /s/{token}`

시작 전 안내용 `title`, `expiresAt`을 반환한다. 조회만으로 링크를 소모하지 않는다. 시작한 링크는 `409`, 요청 기한이 지난 링크는 `410`이다.

### `POST /s/{token}/start`

```json
{"sellerConsented":true}
```

동의하지 않으면 `400`이다. 성공 시 `title`, `code`, `deadline1`, `expiresAt`을 반환한다. 상태는 `IN_PROGRESS / FIRST_CHECKING`이 되고 동의 시각과 판매자 브라우저 세션을 기록한다. 첫 사진 3분 타이머가 시작되며 링크 재사용은 `409`다.

## 3. 사진 제출

### `POST /s/{token}/photo`

```text
Content-Type: multipart/form-data
n: 1 또는 2 (Text)
photo: 사진 파일 1개 (File)
```

2차 사진은 `analysis` JSON part도 필수다. [MediaPipe 입력 형식](hand-analyze-api.md)을 참고한다. multipart Content-Type과 boundary는 클라이언트가 자동 설정하도록 한다.

제출 응답 필드는 `number`, `phase`, `accepted`, `deadline`, `retryReasons`, `checks`, `failReasons`다. 아래 예시는 주요 필드만 표시한다. 체크 항목 형식은 [판매자 화면 체크 결과](trueshot-finalization-api.md#판매자-화면-체크-결과)를 참고한다.

첫 사진은 OpenAI로 코드 표시 여부·일치·화질을 확인한다. 실패 사진은 저장하지 않는다. 검사 시간을 3분 제한에서 제외하며 전체 30분 요청 기한은 연장하지 않는다.

첫 사진의 3분 제출 기한과 전체 링크 기한은 서버 접수 시각으로 검사한다. 기한 안에 접수된 사진은 AI 검사·저장소 업로드가 늦게 끝났다는 이유로 접수를 거절하지 않는다. 검사 결과가 통과이면 정상 접수하고, 재촬영이 필요한 결과이면 새 제출은 다시 접수 시점의 기한을 검사한다. 다음 단계 진입·사진 공유의 링크 유효기간 및 별도의 최종 분석 대기 제한은 기존대로 적용한다.

첫 사진 통과 (`200`):

```json
{"number":1,"phase":"FIRST_MASKING","accepted":true,"deadline":null,"retryReasons":[]}
```

첫 사진 재촬영 (`200`):

```json
{"number":1,"phase":"FIRST_CHECKING","accepted":false,"deadline":"2026-11-13T12:03:05Z","retryReasons":["인증코드가 잘 보이게 다시 찍어 주세요"]}
```

`deadline`은 서버가 갱신한 제출 기한이다. 예시 시각은 고정값이 아니다. 재촬영 응답은 인증 전체의 실패가 아니다.

두 번째 사진은 아래 가림 확인으로 `SECOND_WAITING`이 된 후 한 번만 제출 가능하다. 1분 기한과 전체 30분 기한을 동시에 검사한다.

두 번째 사진 접수 (`200`):

```json
{"number":2,"phase":"JUDGING","accepted":true,"deadline":"2026-11-15T12:01:00Z","retryReasons":[]}
```

`deadline`은 JUDGING일 때 판정 마감이며, 판정이 끝나면 null이다. `accepted`는 접수 성공이다. 2차 사진은 최종 판정까지 연결하며 재제출할 수 없다. 2차 제출에는 사진과 같은 이미지의 MediaPipe 결과를 analysis JSON part로 함께 보내야 한다. 외부 서비스 장애 시 JUDGING으로 접수 결과를 반환하고 동일 원본으로 재시도한다. 상세 계약은 [최종 판정·공유 API](trueshot-finalization-api.md)를 참고한다. 업로드 마감 검사에 별도의 전송 유예 초는 없다.

## 4. 첫 사진 가림·두 번째 촬영 시작

### `POST /s/{token}/mask`

본문 없이 호출한다. OpenAI가 QR·바코드·이름·예매번호의 비율 좌표를 찾고 서버가 여백을 추가해 검정 사각형으로 덮는다.

```json
{"maskStatus":"READY","previewPath":"/s/{token}/preview/1","message":"가린 사진을 확인하고 필요하면 더 가려 주세요."}
```

탐지 불확실·영역 없음·좌표 오류이면 `maskStatus: FAILED`, `previewPath: null`이다. 이 경우 가리지 않은 사진을 대신 제공하지 않는다. OpenAI·Storage 연결 오류는 `503`이며 재시도할 수 있다.

### `GET /s/{token}/preview/1`

시작한 판매자 세션에만 가린 JPEG를 반환한다. 요청 만료는 `410`, 가림 단계가 아니면 `409`, 가림 실패는 `404`, 다른 브라우저는 `403`이다.

### `POST /s/{token}/first-mask/confirm`

```json
{"boxes":[{"x":0.2,"y":0.3,"width":0.1,"height":0.05}]}
```

프론트가 판매자의 추가 가림 영역만 보낸다. 좌표는 왼쪽 위 원점, 가로·세로를 1로 정규화한 사각형이다. 이미지 바깥·음수·0 크기는 거부하며 최대 300개다. 추가 가림이 없으면 `{"boxes":[]}`다. 프론트의 브러시는 이 사각형 목록으로 변환하며, 판매자가 그린 영역을 지우는 것은 확인 전에 프론트에서 처리한다.

서버는 자동 가림 사진 위에만 덧칠한다. 자동 가림을 삭제하는 입력이나 원본 재업로드는 받지 않는다. 확인 후 `SECOND_WAITING`으로 바꾸고 미션을 공개한다.

2차 미션은 브이 따라 하기(`GESTURE`), 화면 가리키기(`POINT_SCREEN`), 브이+4방향(`GESTURE_POSITION`) 중 하나다. 종류를 무작위 선택하고 위치 미션에서는 `LEFT/RIGHT/ABOVE/BELOW` 중 하나를 선택한다. 브이 외 손 모양은 미션으로 생성하지 않는다.

```json
{"instruction2":"브이를 예매 화면 왼쪽에 두고 찍어 주세요.","mission2":{"type":"GESTURE_POSITION","gesture":"Victory","position":"LEFT"},"deadline2":"2026-11-13T12:05:00Z","expiresAt":"2026-11-13T12:30:00Z"}
```

`deadline2`는 지시 공개 시각 + 1분이며 전체 요청 기한도 적용된다. 재확인은 `409`이고 타이머를 다시 시작하지 않는다. 자동 가림이 `FAILED`여도 빈 영역 목록으로 다음 촬영은 시작할 수 있지만 사진은 비공개로 유지한다. 두 사진의 가림이 모두 준비되어야 사진 공유가 가능하다.

## 오류 코드

| HTTP | 의미 |
| --- | --- |
| 400 | 동의·사진 번호·이미지 형식·해상도·가림 좌표 등의 입력 오류 |
| 401 | 구매자 로그인 필요 |
| 403 | 판매자 세션 또는 CSRF 오류 |
| 404 | 요청·사진 없음 또는 다른 구매자의 요청 |
| 409 | 단계 불일치·중복 시작·재제출 |
| 410 | 사진별 제한 시간 또는 요청 30분 기한 초과 |
| 413 | 5MB 크기 제한 초과 |
| 503 | OpenAI·Storage 처리 실패 |

업무 오류는 보통 `{"message":"안내 문구"}`다. Spring Security의 인증·CSRF 오류는 응답 형식이 다를 수 있다.
