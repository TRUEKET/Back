# RP-08 이의제기 API

판매자는 로그인 없이 계좌·전화번호·SNS 식별자로 이의제기 가능 여부를 조회할 수 있다.

실제 이의제기 접수는 **은행 계좌만** 지원하며, 정상 거래(`NORMAL`) 또는 환불(`REFUND`) 증빙을 제출한다.

AI-04 검증을 통과하면 연결된 신고의 게시자에게 이의제기가 전달된다. 게시자는 자신의 신고를 유지·삭제할 수 있으며, 3일간 응답하지 않아 자동 비공개된 신고는 1회 재게시할 수 있다.

본 문서는 2026-11-15 기준 RP-08 Controller, DTO, Service, 예외 처리기, 스케줄러, Storage 및 RP-06 연동 코드와 실제 통합 테스트를 바탕으로 작성했다.

---

## 1. 환경변수 및 의존 서비스

### 1.1 데이터베이스

Supabase PostgreSQL을 사용한다.

프로젝트의 기존 `spring.datasource.*` 설정을 따른다.

### 1.2 AI-04

증빙 이미지 분석에는 프로젝트에서 선택한 AI 공급자를 사용한다.

주요 환경변수:

- `AI_PROVIDER`
- `GEMINI_API_KEY`
- `GEMINI_MODEL`

AI-04는 제출된 이미지에서 이체 내역 및 표 전달 사실을 분석한다.

### 1.3 Supabase Storage

| 환경변수 | 설명 |
|---|---|
| `SUPABASE_URL` | Supabase 프로젝트 기본 URL |
| `SUPABASE_SERVICE_ROLE_KEY` | Storage 서버 접근용 서비스 역할 키 |

이의제기 증빙 버킷은 `dispute-evidence`이다.

`DisputeEvidenceStorageService`에 버킷명이 상수로 정의되어 있다.

`SUPABASE_URL`에는 프로젝트 기본 URL만 입력해야 하며, `/rest/v1/` 경로를 추가하지 않는다.

### 1.4 이메일

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `DISPUTE_MAIL_ENABLED` | false | 실제 메일 발송 활성화 |
| `MAIL_HOST` | smtp.gmail.com | SMTP 서버 |
| `MAIL_PORT` | 587 | SMTP 포트 |
| `MAIL_USERNAME` | - | 발송 계정 |
| `MAIL_PASSWORD` | - | SMTP 인증 비밀번호 |
| `MAIL_FROM` | - | 발신자 주소 |
| `DISPUTE_SUPPORT_EMAIL` | - | 고객지원 주소 |

Gmail SMTP는 STARTTLS 방식으로 설정한다.

```properties
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.starttls.required=true
```

Gmail을 사용할 경우 일반 로그인 비밀번호가 아닌 Google 앱 비밀번호를 사용한다.

API 키, SMTP 비밀번호, Supabase 서비스 역할 키는 Git에 커밋하지 않는다.

`DISPUTE_MAIL_ENABLED=true`로 설정하면 실제 이메일이 발송되므로 테스트 환경에서는 테스트 계정을 사용한다.

---

## 2. 인증 및 CSRF

| API | 로그인 | CSRF |
|---|---|---|
| `GET /disputes/availability` | 불필요 | 해당 없음 |
| `POST /disputes` | 불필요 | 제외 |
| `POST /disputes/{disputeId}/decision` | 필요 | 적용 |
| `POST /disputes/{disputeId}/republish` | 필요 | 적용 |

`SecurityConfig`에서 `POST /disputes`만 CSRF 검사 대상에서 제외한다.

로그인이 필요한 API는 Spring Security의 인증 정보를 사용한다.

서버는 `Authentication.getName()`을 통해 사용자 ID를 얻고, 해당 사용자가 신고 작성자인지 검사한다.

세션 인증과 CSRF 토큰 발급은 프로젝트의 `/auth` API 정책을 따른다.

---

## 3. API 요청 및 응답

### RP-08-01. 이의제기 가능 여부 조회

**GET `/disputes/availability`**

로그인 없이 이의제기 가능 여부를 조회한다.

#### 요청

| 항목 | 위치 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| `q` | Query | String | O | 계좌번호, 전화번호 또는 SNS 계정 |

요청 예시:

```http
GET /disputes/availability?q=신한%20111-111-1-111111
```

#### 성공 응답

HTTP 200

```json
{
  "status": "NEW_REPORTS_ONLY",
  "canApply": true,
  "targetReportCount": 3
}
```

#### 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | String | 이의제기 가능 상태 |
| `canApply` | Boolean | 이의제기 신청 가능 여부 |
| `targetReportCount` | Integer | 신청 대상 신고 수 |

#### 상태 종류

| status | canApply | 의미 |
|---|---|---|
| `AVAILABLE` | true | 최초 이의제기 신청 가능 |
| `NEW_REPORTS_ONLY` | true | 과거 이의제기 이력이 있지만 새로운 신고가 있음 |
| `PROCESSING` | false | AI 검증 또는 게시자·운영팀 처리가 진행 중 |
| `NO_NEW_REPORTS` | false | 이의제기할 새로운 신고가 없음 |

#### 신청 가능 조건

1. 입력 식별자를 정규화한다.
2. 정규화된 식별자의 해시로 DB에서 기존 식별자를 조회한다.
3. 동일 식별자의 `CHECKING` 이의제기가 있는지 확인한다.
4. `RESPONDING`, `REVIEWING` 상태의 이의제기 항목이 있는지 확인한다.
5. 최근 1년 이내의 `PUBLISHED` 신고를 조회한다.
6. 동일 식별자로 이미 이의제기를 받은 신고를 제외한다.
7. 새롭게 신청 가능한 신고가 존재하면 신청을 허용한다.

기존에 전달된 이의제기가 있더라도 새로운 신고가 존재하면 `NEW_REPORTS_ONLY`를 반환한다.

등록되지 않은 식별자 또는 신청 가능한 신고가 없는 경우 `NO_NEW_REPORTS`를 반환한다.

식별자 정규화에 실패하면 HTTP 400을 반환한다.

**주의:** 조회 API는 계좌·전화번호·SNS를 지원하지만 현재 이의제기 접수 API는 은행 계좌만 지원한다.

---

### RP-08-02. 이의제기 접수

**POST `/disputes`**

Content-Type: `multipart/form-data`

로그인 불필요.

판매자가 정상 거래 또는 환불 사실을 주장하며 증빙을 제출하는 API이다.

#### 요청 Body

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `identifier` | String | O | 이의제기 대상 은행 계좌 |
| `reason` | Enum | O | `NORMAL` 또는 `REFUND` |
| `description` | String | X | 이의제기 설명 |
| `sellerEmail` | String | O | 결과를 받을 판매자 이메일 |
| `deliveryEvidence` | File | 조건부 | 정상 거래의 표 전달 내역 |
| `transferEvidence` | File | O | 입금 또는 환불 이체확인증 |

`sellerEmail`은 기본 이메일 형식을 검사하며 최대 254자까지 허용한다.

#### NORMAL — 정상 거래

필수 증빙:

- `deliveryEvidence`: 표 전달 대화 캡처
- `transferEvidence`: 판매자 입금 내역

AI-04가 다음 조건을 확인한다.

1. 전달 증빙을 읽을 수 있다.
2. 문서 종류가 `ticket_delivery`이다.
3. 티켓 전달 완료 근거가 확인된다.
4. 입금 증빙을 읽을 수 있다.
5. 문서 종류가 `transfer_receipt`이다.
6. 이체 완료가 확인된다.
7. 이의제기 대상 계좌가 **받는 계좌(receiver)** 와 일치한다.

#### REFUND — 환불 완료

필수 증빙:

- `transferEvidence`: 환불 이체확인증 1장

`deliveryEvidence`는 제출하지 않는다.

AI-04가 다음 조건을 확인한다.

1. 증빙을 읽을 수 있다.
2. 문서 종류가 `transfer_receipt`이다.
3. 이체 완료가 확인된다.
4. 이의제기 대상 계좌가 **보내는 계좌(sender)** 와 일치한다.

#### 지원 사유

| 사유 | 지원 여부 |
|---|---|
| `NORMAL` | 지원 |
| `REFUND` | 지원 |
| `IMPERSONATION` | 현재 미지원 |
| `OTHER` | 현재 미지원 |

`DisputeReason` Enum에는 네 종류가 정의되어 있지만 현재 접수 API는 `NORMAL`과 `REFUND`만 허용한다.

#### 증빙 파일 제한

| 항목 | 제한 |
|---|---|
| 파일 형식 | PNG, JPEG |
| 파일 크기 | 장당 최대 5MiB |
| 최대 픽셀 | 20,000,000 |
| 분석용 형식 | PNG |
| Storage 저장 형식 | PNG |

서버는 파일의 Content-Type과 실제 이미지 디코딩 가능 여부를 확인한다.

#### 검증 성공 응답

HTTP 200

```json
{
  "disputeId": 19,
  "receiptNo": "TK-2611-000007",
  "status": "DELIVERED",
  "targetReportCount": 3,
  "message": "증빙 검증을 통과하여 이의제기가 전달되었습니다."
}
```

접수번호는 다음 형식으로 생성된다.

```text
TK-YYMM-000001
```

연월은 `Asia/Seoul` 기준이며 순번은 DB 시퀀스를 사용한다.

#### 증빙 검증 거절 응답

HTTP 200

```json
{
  "disputeId": 19,
  "receiptNo": "TK-2611-000007",
  "status": "REJECTED",
  "targetReportCount": 0,
  "message": "증빙 확인 결과가 이의제기 조건과 일치하지 않습니다."
}
```

**중요:** 증빙 검증에서 반려된 경우 HTTP 오류가 아니라 HTTP 200과 업무 상태 `REJECTED`를 반환한다.

#### 오류 응답

| 조건 | HTTP | 메시지 |
|---|---|---|
| 이메일 형식 오류 | 400 | 올바른 이메일 주소를 입력해 주세요. |
| NORMAL 증빙 누락 | 400 | 정상 거래는 표 전달 내역과 입금 내역이 모두 필요합니다. |
| REFUND 증빙 누락 | 400 | 환불 이체확인증이 필요합니다. |
| REFUND에 전달 증빙 추가 | 400 | 환불 이의제기에는 환불 증빙만 첨부해 주세요. |
| 지원하지 않는 사유 | 400 | 현재는 정상 거래와 환불 이의제기만 지원합니다. |
| 은행 계좌 외 식별자 | 400 | 현재는 은행 계좌 이의제기만 지원합니다. |
| 증빙 파일 5MiB 초과 | 400 | 5MB 이하의 증빙 사진이 필요합니다. |
| 지원하지 않는 이미지 형식 | 400 | PNG 또는 JPEG 이미지만 업로드할 수 있습니다. |
| 이미 AI 검증 중 | 409 | 이미 증빙을 확인 중인 이의제기가 있습니다. |
| 게시자·운영팀 처리 중 | 409 | 현재 처리 중인 이의제기가 있습니다. |
| 새롭게 신청할 신고 없음 | 409 | 새롭게 이의제기할 기록이 없습니다. |
| AI·Storage 처리 장애 | 503 | 증빙 확인 중 오류가 발생했습니다. 접수번호를 보관하고 다시 신청해 주세요. |

`DisputeTransactionService.deliver()`에서 발생하는 일부 409 예외는 `DisputeCreateService`의 `catch(RuntimeException)`에 의해 503으로 변환될 수 있다.

#### 접수 처리 흐름

```text
요청 수신
    ↓
필수 입력·이미지 검증
    ↓
식별자 정규화 및 대상 신고 조회
    ↓
CHECKING 접수 생성
    ↓
판매자 접수 안내 이메일
    ↓
AI-04 증빙 분석
    ↓
검증 결과 확인
    ├─ 실패 → REJECTED
    └─ 통과
         ↓
      개인정보 마스킹
         ↓
      Supabase Storage 업로드
         ↓
      신고별 DisputeItem 생성
         ↓
      신고 상태 RESPONDING
         ↓
      이의제기 DELIVERED
         ↓
      판매자·신고 게시자 안내 이메일
```

AI 분석 또는 Storage 업로드 과정에서 장애가 발생하면 `FAILED` 상태 변경을 시도한다.

이미 업로드된 이미지가 있다면 삭제를 시도한다.

이메일 발송 실패는 로그로 남기며 정상 접수 결과를 변경하지 않는다.

---

### RP-08-03. 신고 유지 또는 삭제 결정

**POST `/disputes/{disputeId}/decision`**

Content-Type: `application/json`

로그인 필요.

신고 작성자가 자신에게 전달된 이의제기에 응답하는 API이다.

#### 요청 Body

```json
{
  "reportId": 359,
  "decision": "MAINTAINED"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `disputeId` | Long (Path) | O | 이의제기 ID |
| `reportId` | Long | O | 대상 신고 ID |
| `decision` | Enum | O | `MAINTAINED` 또는 `DELETED` |

#### 신고 유지

`MAINTAINED` 선택 시:

- `dispute_items.status = MAINTAINED`
- `reports.status = PUBLISHED`

성공 응답:

```json
{
  "disputeId": 18,
  "reportId": 359,
  "decision": "MAINTAINED",
  "message": "신고 유지가 완료되었습니다."
}
```

#### 신고 삭제

`DELETED` 선택 시:

- RP-07 신고 삭제 로직을 재사용한다.
- 신고 원본 및 연결 데이터를 삭제한다.
- 삭제 결과는 `dispute_deleted_records`에 보존한다.
- 결과 이메일 집계에서 삭제 건수로 반영한다.

성공 응답:

```json
{
  "disputeId": 18,
  "reportId": 360,
  "decision": "DELETED",
  "message": "신고 삭제가 완료되었습니다."
}
```

#### 오류

| 조건 | HTTP | 설명 |
|---|---|---|
| 지원하지 않는 decision | 400 | 유지 또는 삭제만 선택 가능 |
| 로그인하지 않음 | 401 | 인증 필요 |
| 대상 없음 | 404 | 이의제기 대상 신고 없음 |
| 타인 신고 | 404 | 존재 여부를 노출하지 않도록 동일 응답 |
| 이미 처리됨 | 409 | 중복 결정 차단 |
| 응답 기한 초과 | 409 | 결정 기한 종료 |

응답 기한은 이의제기가 전달된 시점부터 3일이다.

---

### RP-08-04. 비공개 신고 재게시

**POST `/disputes/{disputeId}/republish`**

Content-Type: `application/json`

로그인 필요.

3일 동안 이의제기에 응답하지 않아 자동 비공개된 신고를 다시 게시하는 API이다.

#### 요청 Body

```json
{
  "reportId": 361
}
```

`reportId`는 필수이다.

#### 재게시 조건

- 신고 작성자 본인
- 현재 이의제기 항목 상태 `PRIVATE`
- `republished=false`
- 재게시 기회 1회

#### 성공 응답

HTTP 200

```json
{
  "disputeId": 18,
  "reportId": 361,
  "decision": "MAINTAINED",
  "message": "신고가 다시 공개되었습니다."
}
```

#### 성공 후 상태

| 항목 | 변경 결과 |
|---|---|
| `dispute_items.status` | MAINTAINED |
| `dispute_items.republished` | true |
| `reports.status` | PUBLISHED |

동일한 요청을 다시 보내면 다음 메시지로 차단된다.

```json
{
  "message": "비공개 상태의 신고만 재게시할 수 있습니다."
}
```

두 번째 요청의 정확한 HTTP 상태 코드는 별도로 확인하지 않았다.

---

## 4. 이의제기 상태 및 자동 처리

### 4.1 이의제기 상태

| 상태 | 의미 |
|---|---|
| `CHECKING` | AI-04 증빙 확인 중 |
| `DELIVERED` | 증빙 검증 통과, 게시자에게 전달 |
| `REJECTED` | 증빙 검증 반려 |
| `FAILED` | 증빙 처리를 완료하지 못함 |

### 4.2 신고별 이의제기 상태

| 상태 | 의미 |
|---|---|
| `RESPONDING` | 게시자 응답 대기 |
| `MAINTAINED` | 게시자가 신고 유지 |
| `DELETED` | 게시자가 신고 삭제 |
| `PRIVATE` | 무응답 자동 비공개 |
| `REVIEWING` | 운영팀 검토 중 |

### 4.3 자동 비공개

`DisputeExpirationScheduler`는 1분 주기로 만료된 이의제기를 확인한다.

```java
@Scheduled(fixedDelay = 60000){}
```

다음 조건을 만족하면 비공개 처리한다.

- `DisputeItem.status = RESPONDING`
- 현재 시간이 `deadline` 이상

`DisputeExpirationService.expireOne()`은 행 잠금 후 상태와 기한을 다시 확인한다.

조건을 만족하면 `item.expireWithoutResponse(now)`를 호출한다.

실제 통합 테스트에서 `dispute_items`와 `reports`가 모두 `PRIVATE`로 변경되는 것을 확인했다.

### 4.4 RP-06 내 신고 조회와 연동

`DisputeNoticeResponse`는 RP-06의 다음 서비스 응답에 포함된다.

- `ReportService.getMyReports()`
- `ReportService.getMyReportDetail()`

응답에 포함되는 필드:

| 필드 | 설명 |
|---|---|
| `disputeId` | 이의제기 ID |
| `reason` | 이의제기 사유 |
| `status` | 신고별 처리 상태 |
| `deadline` | 응답 기한 |
| `canRespond` | 유지·삭제 결정 가능 여부 |
| `canRepublish` | 재게시 가능 여부 |

`canRespond`는 `RESPONDING`이며 기한 전일 때 `true`이다.

`canRepublish`는 `PRIVATE`이며 아직 재게시하지 않았을 때 `true`이다.

별도의 RP-08 알림 조회 API는 존재하지 않으며 RP-06 조회 응답에 포함하는 방식이다.

---

## 5. 이메일 발송

### 5.1 이메일 종류

| 수신자 | 발송 시점 | 내용 |
|---|---|---|
| 판매자 | 이의제기 접수 | 접수번호 안내 |
| 판매자 | 증빙 검증 통과 | 게시자 전달 결과 및 결정 기한 |
| 판매자 | 증빙 검증 반려 | 반려 결과 안내 |
| 신고 게시자 | 이의제기 전달 | 신고 유지·삭제 요청 |
| 판매자 | 모든 신고 처리 완료 | 최종 유지·삭제·비공개 건수 |

발송에는 `SmtpDisputeNotificationService`를 사용한다.

### 5.2 결과 이메일 집계

최종 결과 이메일에는 다음 건수를 포함한다.

- 신고 유지 건수
- 신고 삭제 건수
- 비공개 처리 건수

삭제된 신고는 `dispute_deleted_records`를 조회해 집계한다.

### 5.3 결과 이메일 발송 조건

다음 조건을 만족해야 한다.

1. 이의제기 상태가 `DELIVERED`
2. `RESPONDING`, `REVIEWING` 항목이 없음
3. 현재 이의제기 항목 수와 삭제 이력 수의 합계가 최초 대상 신고 수와 일치
4. 최초 대상 신고 수가 1건 이상
5. 발송 작업 선점이 가능한 상태

### 5.4 발송 상태

```text
PENDING
   ↓
SENDING
   ├─ 성공 → SENT
   └─ 실패 → PENDING
```

`DisputeResultMailScheduler`는 다음 설정으로 실행된다.

```java
@Scheduled(
        fixedDelay = 60000,
        initialDelay = 15000
) {}
```

`DISPUTE_MAIL_ENABLED=true`일 때만 활성화된다.

### 5.5 실패 및 재시도

- 발송 실패 시 `PENDING`으로 변경한다.
- 실패 후 최소 1분이 지나면 재시도할 수 있다.
- `SENDING` 상태가 5분 이상 유지된 작업은 재선점할 수 있다.
- 코드상 최대 재시도 횟수 제한은 없다.
- 발송 오류는 서버 로그에 기록된다.

SMTP 발송에 성공했지만 DB에서 `SENT` 상태 확정에 실패하면 이메일이 중복 발송될 가능성이 있다.

초기 접수·전달·반려·게시자 알림은 `notifySafely()`에서 실패를 기록하고 정상 접수 결과에는 영향을 주지 않는다.

초기 안내 메일의 별도 자동 재시도는 공유된 코드에서 확인되지 않았다.

---

## 6. 실제 HTTP 응답 형식

프로젝트에는 다음 공통 응답 DTO가 존재한다.

```java
public record ApiResponse<T>(
        String code,
        String message,
        T data
) {}
```

하지만 현재 RP-08 Controller의 성공 응답은 DTO를 직접 반환한다.

예를 들어 다음 형태이다.

```json
{
  "disputeId": 19,
  "receiptNo": "TK-2611-000007",
  "status": "DELIVERED",
  "targetReportCount": 3,
  "message": "증빙 검증을 통과하여 이의제기가 전달되었습니다."
}
```

`ApiExceptionHandler`가 처리하는 오류는 다음과 같다.

```json
{
  "message": "현재 처리 중인 이의제기가 있습니다."
}
```

반면 `GlobalExceptionHandler`에서 처리하는 일부 오류는 다음과 같은 공통 응답을 사용한다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "잘못된 요청 값입니다.",
  "data": null
}
```

즉 현재 성공 및 오류 응답은 전부 `ApiResponse<T>`로 통일되어 있지 않다.

또한 `MethodArgumentNotValidException`이 두 개의 `@RestControllerAdvice`에서 각각 처리되고 있으므로 오류 메시지와 응답 형식을 정리할 필요가 있다.

`DataIntegrityViolationException`의 공통 처리 메시지가 회원가입 중복에 맞춰 작성되어 있어 RP-08 오류 상황과 일치하지 않을 가능성도 있다.

---

## 7. 증빙 이미지 저장 및 개인정보 보호

### 7.1 이미지 처리

증빙 이미지는 다음 순서로 처리된다.

```text
PNG/JPEG 업로드
    ↓
파일 크기 및 이미지 검증
    ↓
AI 분석용 PNG 변환
    ↓
AI-04 분석
    ↓
개인정보 마스킹 좌표 추출
    ↓
검은 사각형 마스킹
    ↓
마스킹 결과 검증
    ↓
Supabase Storage 업로드
```

AI-04는 0~1 사이의 정규화된 좌표를 반환한다.

서버는 좌표의 유효성을 검사하고 개인정보 영역을 검은색으로 가린다.

마스킹 좌표가 유효하지 않거나 결과 이미지가 제한 크기를 초과하면 처리에 실패한다.

### 7.2 Storage 구조

버킷:

```text
dispute-evidence
```

파일 키:

```text
disputes/{disputeId}/{uuid}.png
```

저장 파일 수:

| 이의제기 사유 | 저장 이미지 |
|---|---|
| NORMAL | 2장 |
| REFUND | 1장 |

DB의 `disputes.proof_images`에는 Storage 파일 키 목록을 저장한다.

### 7.3 개인정보 보호

- AI가 추출한 전체 계좌번호를 결과 응답에 반환하지 않는다.
- AI 검증 요약 정보만 `check_result`에 저장한다.
- 마스킹된 증빙 이미지를 Storage에 저장한다.
- Storage 서비스 역할 키는 서버 환경변수에서 관리한다.
- 신고 작성자 개인정보는 판매자 최종 결과 이메일에 포함하지 않는다.

AI 마스킹 결과가 모든 개인정보를 정확하게 가렸는지는 별도 검증이 필요하다.

AI 증빙 검증 통과는 실제 송금이나 티켓 전달 사실을 보증하지 않는다.

### 7.4 보관 및 삭제

접수 도중 장애가 발생하면 이미 업로드된 파일의 삭제를 시도한다.

삭제에 실패하면 서버 로그에 오류를 기록한다.

현재 확인한 코드에는 **이의제기 증빙 이미지의 장기 보관 기한이나 정기 자동 삭제 작업이 구현되어 있지 않다.**

운영 전 확인해야 할 사항은 다음과 같다.

- 이의제기 증빙 보관 기간
- Storage 버킷 접근 권한
- 만료된 이미지 자동 삭제
- 관리자 삭제 절차
- 개인정보 마스킹 품질

RP-07 신고 삭제는 신고 관련 DB 데이터를 삭제하지만 이의제기 증빙 Storage 파일의 삭제와는 별개이다.

---

## 8. Swagger(OpenAPI)

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

Controller 그룹:

```text
RP-08 이의제기
```

문서화 대상 API:

| Method | Endpoint | 설명 |
|---|---|---|
| GET | `/disputes/availability` | 이의제기 가능 여부 |
| POST | `/disputes` | 이의제기 접수 |
| POST | `/disputes/{disputeId}/decision` | 신고 유지·삭제 |
| POST | `/disputes/{disputeId}/republish` | 신고 재게시 |

`POST /disputes`는 `multipart/form-data`이며 증빙 이미지 필드를 binary로 표시한다.

Swagger UI의 `Try it out`을 실행하면 실제 DB 변경 및 이메일 발송이 발생할 수 있으므로 테스트 데이터를 사용해야 한다.

---

## 9. 실제 통합 테스트 기록

검증일: **2026-11-15**

테스트 환경:

- Spring Boot 4.0.9
- Java 17
- Supabase PostgreSQL
- Supabase Storage
- AI-04 증빙 검증
- Gmail SMTP
- Postman

테스트에는 합성된 거래 대화 및 이체 증빙 이미지를 사용했다.

### 9.1 NORMAL 이의제기

이의제기 ID: `18`

접수번호: `TK-2611-000006`

결과: `DELIVERED`

대상 신고: 3건

| 신고 ID | 테스트 | 결과 |
|---|---|---|
| 359 | 신고 유지 | MAINTAINED |
| 360 | 신고 삭제 | DELETED |
| 361 | 무응답 자동 비공개 | PRIVATE |

신고 361은 이후 재게시하여 다음 상태로 변경됐다.

```text
dispute_items.status = MAINTAINED
dispute_items.republished = true
reports.status = PUBLISHED
```

동일한 재게시 요청을 다시 보내면 차단되는 것도 확인했다.

### 9.2 NORMAL 최종 결과 집계

```json
{
  "maintainedCount": 2,
  "deletedCount": 1,
  "privateCount": 0
}
```

결과 이메일 발송 완료 로그와 DB의 `SENT` 갱신 SQL을 확인했다.

### 9.3 REFUND 이의제기

이의제기 ID: `19`

결과: `DELIVERED`

대상 신고: 3건

| 신고 ID | 이의제기 상태 | 신고 상태 |
|---|---|---|
| 362 | RESPONDING | RESPONDING |
| 363 | RESPONDING | RESPONDING |
| 364 | RESPONDING | RESPONDING |

환불 이체확인증 1장으로 AI-04 검증 및 이의제기 접수에 성공했다.

판매자와 신고 게시자 양쪽의 Gmail 수신도 확인했다.

2026-11-15 확인 당시 이의제기 19번은 `DELIVERED`, 결과 이메일은 `PENDING`이었으며 게시자 응답 기한은 2026-11-18이었다.

### 9.4 테스트 결과 요약

| 테스트 항목 | 결과 |
|---|---|
| 이의제기 가능 여부 조회 | 성공 |
| NORMAL 증빙 2장 검증 | 성공 |
| REFUND 증빙 1장 검증 | 성공 |
| Supabase Storage 업로드 | 성공 |
| 신고별 이의제기 전달 | 성공 |
| 신고 유지 | 성공 |
| 신고 삭제 | 성공 |
| 3일 무응답 자동 비공개 | 성공 |
| 비공개 후 1회 재게시 | 성공 |
| 재게시 중복 요청 차단 | 성공 |
| 최종 결과 집계 | 성공 |
| Gmail SMTP 이메일 발송 | 성공 |
| 판매자·게시자 이메일 수신 | 성공 |

### 9.5 테스트 데이터

원본 신고:

```text
report_id = 358
identifier_id = 308
hidden = false
```

원본 신고의 `hidden` 설정은 테스트 이전 값으로 복구했다.

테스트용 신고:

```text
NORMAL: 359, 360, 361
REFUND: 362, 363, 364
```

테스트 신고 및 이의제기 기록은 별도로 삭제하지 않았다.

---

## 10. 운영 전 점검 사항

1. `GlobalExceptionHandler`와 `ApiExceptionHandler`의 중복 예외 처리를 정리한다.
2. 성공 및 오류 응답의 `ApiResponse<T>` 통일 여부를 팀 내에서 결정한다.
3. `DisputeCreateService`의 광범위한 `RuntimeException` 처리로 일부 409 오류가 503으로 변환될 가능성을 검토한다.
4. 이의제기 증빙 이미지 보관 기간 및 자동 삭제 정책을 확정한다.
5. 마스킹 결과에서 개인정보가 실제로 충분히 가려지는지 확인한다.
6. 이메일 재시도 과정에서 중복 발송 가능성을 점검한다.
7. 테스트 데이터 정리 시 원본 신고 358을 보존하고 연결된 DB 및 Storage 데이터를 함께 확인한다.

현재 NORMAL·REFUND 이의제기 접수, AI-04 검증, 게시자 결정, 자동 비공개, 재게시, 이메일 발송에 대한 주요 통합 테스트를 완료했다.