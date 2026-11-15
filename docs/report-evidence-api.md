# RP-05 송금 증빙 검증 API

JD 판독 결과에 포함된 신고 대상 계좌와 사용자가 제출한 송금 사진을 비교하여 송금 완료 화면인지 확인하는 API이다.

AI-03이 사진에서 수취 은행과 계좌번호를 추출하고, 신고 대상 계좌와 일치하는지 검증한다.

검증에 성공하면 사진에서 수취 계좌번호 영역만 잘라 개인정보 노출 여부를 추가 확인한 뒤, Supabase Storage에 임시 저장하고 `evidenceToken`을 발급한다.

발급된 토큰은 RP-04 신고 등록 API에서 사용한다.

본 문서는 변경된 `ReportEvidenceController`, `ReportEvidenceService.verifySelected()`, `SupabaseReportEvidenceStore`를 기준으로 작성하였다.

---

## 1. API 개요

| 항목 | 내용 |
|---|---|
| 기능 ID | RP-05 |
| Method | POST |
| Endpoint | `/reports/evidence` |
| Content-Type | `multipart/form-data` |
| 인증 | 로그인 필요 |
| CSRF | 프로젝트의 SecurityConfig 정책 적용 |
| 성공 코드 | 200 OK |
| Controller | `ReportEvidenceController` |
| Service | `ReportEvidenceService` |
| AI 서비스 | `ReportEvidenceAiService` |
| 저장 서비스 | `PendingReportEvidenceService` |
| Storage 서비스 | `SupabaseReportEvidenceStore` |

### 연동 구조

```text
JD 판독
POST /analyze
    |
    | analysisId 반환
    v
RP-05 송금 증빙 검증
POST /reports/evidence
    |
    | accountIndex 또는 account 선택
    | 송금 사진 업로드
    v
계좌 및 송금 증빙 검증
    |
    | evidenceToken 반환
    v
RP-04 신고 등록
POST /reports
    |
    v
최종 신고 및 증빙 저장
```

RP-05는 송금 증빙의 검증과 임시 저장을 담당한다.

실제 신고 레코드를 생성하지는 않는다.

---

## 2. 요청

### 2.1 API 정보

**POST `/reports/evidence`**

Content-Type: `multipart/form-data`

### 2.2 요청 파라미터

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `analysisId` | UUID | O | JD 판독 결과 ID |
| `account` | String | X | 은행명과 전체 계좌번호 |
| `accountIndex` | Integer | X | JD 판독 계좌 목록에서 선택할 인덱스 |
| `file` | MultipartFile | O | 송금 완료 사진 |

`account`와 `accountIndex`는 Controller에서 모두 `required=false`로 선언되어 있다.

하지만 실제 사용 조건은 `ReportEvidenceService.selectAccount()`에서 검증한다.

### 2.3 계좌 선택 규칙

| 요청 상황 | 처리 결과 |
|---|---|
| `accountIndex=0` | JD 계좌 목록의 첫 번째 계좌 선택 |
| `accountIndex=1` | JD 계좌 목록의 두 번째 계좌 선택 |
| `account`만 전달 | 입력한 전체 계좌번호로 검증 |
| 두 값 모두 전달 | HTTP 400 |
| 두 값 모두 생략하고 JD 계좌가 1개 | 자동 선택 |
| 두 값 모두 생략하고 JD 계좌가 0개 | HTTP 400 |
| 두 값 모두 생략하고 JD 계좌가 2개 이상 | HTTP 400 |
| 음수 인덱스 | HTTP 400 |
| 계좌 목록 범위를 벗어난 인덱스 | HTTP 400 |
| `*`가 포함된 마스킹 계좌 입력 | HTTP 400 |

**`accountIndex`는 0부터 시작한다.**

JD가 계좌 3개를 추출했다면 유효한 인덱스는 `0`, `1`, `2`이다.

`accountIndex`와 `account`는 동시에 전달할 수 없다.

### 2.4 권장 요청 방식

프론트엔드는 사용자가 JD 판독 결과에서 선택한 계좌를 `accountIndex`로 전달하는 방식을 권장한다.

특히 계좌번호가 마스킹된 상태로 표시되는 화면에서는 전체 계좌번호를 다시 구성하지 말고 `accountIndex`를 사용해야 한다.

### 2.5 요청 예시 1 — accountIndex 사용

```http
POST /reports/evidence
Content-Type: multipart/form-data
```

Form-data:

```text
analysisId   = 89ee4f8f-c83c-4038-a2a9-403e155b20e4
accountIndex = 0
file         = transfer.png
```

위 요청은 JD 판독 결과의 첫 번째 계좌를 대상으로 송금 증빙을 검증한다.

### 2.6 요청 예시 2 — account 사용

```http
POST /reports/evidence
Content-Type: multipart/form-data
```

Form-data:

```text
analysisId = 89ee4f8f-c83c-4038-a2a9-403e155b20e4
account    = 신한 111-111-1-111111
file       = transfer.png
```

`account`는 마스킹되지 않은 전체 은행 계좌 정보여야 한다.

JD 판독 결과의 계좌 목록에 동일한 계좌가 존재해야 한다.

### 2.7 요청 예시 3 — JD 계좌가 1개인 경우

JD 판독 결과에 계좌가 정확히 1개라면 `account`와 `accountIndex`를 생략할 수 있다.

```text
analysisId = 89ee4f8f-c83c-4038-a2a9-403e155b20e4
file       = transfer.png
```

이 경우 서버가 JD 계좌 목록의 유일한 계좌를 자동 선택한다.

단, 계좌가 없거나 2개 이상이면 HTTP 400을 반환한다.

### 2.8 인증 조건

1. 로그인한 사용자여야 한다.
2. JD 판독 결과가 존재해야 한다.
3. JD 판독 세션 소유자와 로그인 사용자가 일치해야 한다.
4. 선택한 계좌가 JD 판독 결과에 존재해야 한다.

Controller는 다음 코드로 로그인 사용자 ID를 가져온다.

```java
Long userId = Long.valueOf(authentication.getName());
```

인증 정보가 없거나 사용자 ID를 숫자로 변환할 수 없으면 HTTP 401을 반환한다.

JD 판독 세션 검증에는 `AnalysisReportSourceService`를 사용한다.

---

## 3. 송금 사진 제한

| 항목 | 제한 |
|---|---|
| 파일 형식 | JPEG, PNG |
| 최대 파일 크기 | 5MiB |
| 최대 이미지 픽셀 수 | 40,000,000 |
| AI 분석용 이미지 | 원본 JPEG 또는 PNG |
| 최종 임시 저장 형식 | PNG |
| 저장 대상 | 수취 계좌번호가 포함된 잘라낸 이미지 |

`ReportEvidenceImageValidator`는 파일이 비어 있는지, 크기가 초과되었는지, 이미지 디코딩이 가능한지, 실제 이미지 형식이 허용되는지를 검사한다.

파일 확장자만으로 이미지를 신뢰하지 않는다.

### 이미지 크기 초과

HTTP 400

```json
{
  "message": "송금 사진은 5MB 이하로 올려 주세요."
}
```

### 지원하지 않는 이미지

HTTP 400

```json
{
  "message": "JPEG, PNG 이미지만 업로드할 수 있습니다."
}
```

위 오류 메시지는 기존 이미지 검증 정책을 기준으로 한다.

파일이 요청 자체에서 누락된 경우에는 Spring MVC 예외 처리 결과가 다를 수 있으므로 별도 통합 테스트가 필요하다.

---

## 4. 검증 처리 흐름

현재 `ReportEvidenceController`는 다음 메서드를 호출한다.

```
reportEvidenceService.verifySelected(
        userId,
        analysisId,
        accountIndex,
        account,
        file,
        httpRequest.getSession(false)
);
```

전체 처리 흐름:

```text
POST /reports/evidence
    |
    v
로그인 사용자 확인
    |
    v
JD 판독 세션 조회
    |
    v
판독 세션 사용자와 로그인 사용자 비교
    |
    v
accountIndex / account 기반 계좌 선택
    |
    v
선택한 계좌 정규화
    |
    v
JD 계좌 목록과 일치 여부 확인
    |
    v
업로드 이미지 검증
    |
    v
AI-03 송금 내역 분석
    |
    v
송금 완료 여부 검사
    |
    v
수취 은행명 및 계좌번호 추출
    |
    v
신고 대상 계좌와 일치 여부 검사
    |
    v
계좌번호 위치 좌표 검증
    |
    v
수취 계좌번호 영역만 자르기
    |
    v
개인정보 노출 여부 AI 검사
    |
    v
Supabase Storage 임시 업로드
    |
    v
임시 증빙 메타데이터 DB 저장
    |
    v
evidenceToken 반환
```

모든 검증에 성공한 경우에만 `evidenceToken`을 발급한다.

---

## 5. JD 판독 결과 검증

### 5.1 JD 계좌 목록

RP-05는 JD 판독 결과에서 다음 계좌 목록을 가져온다.

```
source.identifiers().accounts()
```

이 목록을 기준으로 신고 대상 계좌를 선택한다.

### 5.2 계좌 선택

`ReportEvidenceService`는 다음 메서드를 사용한다.

```
private String selectAccount(
        AnalysisReportSource source,
        Integer index,
        String account
)
```

계좌 선택 규칙:

1. `accountIndex`가 존재하면 해당 인덱스의 계좌를 선택한다.
2. 이때 `account`를 함께 전달하면 요청을 거절한다.
3. 인덱스가 음수이거나 목록 범위를 벗어나면 요청을 거절한다.
4. `accountIndex`가 없고 `account`가 존재하면 해당 값을 사용한다.
5. `account`에 `*`가 포함되어 있으면 요청을 거절한다.
6. 두 값 모두 없고 JD 계좌가 정확히 1개면 자동 선택한다.
7. 그 외에는 계좌 선택 오류를 반환한다.

### 5.3 실제 계좌 선택 코드

```java
private String selectAccount(
        AnalysisReportSource source,
        Integer index,
        String account
) {
    boolean hasAccount =
            account != null && !account.isBlank();

    var accounts = source.identifiers().accounts();

    if (index != null) {
        if (hasAccount || index < 0 || index >= accounts.size()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "판독 결과에서 신고할 계좌를 다시 선택해 주세요."
            );
        }

        return accounts.get(index);
    }

    if (hasAccount) {
        if (account.contains("*")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "가려진 표시값으로 계좌를 비교할 수 없어요. account 대신 accountIndex로 선택해 주세요."
            );
        }

        return account;
    }

    if (accounts.size() == 1) {
        return accounts.get(0);
    }

    throw new ApiException(
            HttpStatus.BAD_REQUEST,
            "판독 결과에서 신고할 계좌를 선택해 주세요."
    );
}
```

### 5.4 식별자 정규화

선택된 계좌는 `IdentifierNormalizer`를 이용해 정규화한다.

```java
String selected =
        selectAccount(source, accountIndex, account);

String targetAccount = normalizeAccount(selected);
```

정규화 결과의 식별자 종류는 `ACCOUNT`여야 한다.

은행명과 전체 계좌번호가 올바른 형식이 아니면 검증에 실패한다.

### 5.5 JD 계좌 일치 여부

정규화된 신고 대상 계좌가 JD 판독 결과의 계좌 목록에 존재해야 한다.

```
var jdAccounts = source.identifiers().accounts();

boolean accountExists = jdAccounts.stream()
        .map(this::normalizeAccount)
        .anyMatch(targetAccount::equals);

if (!accountExists) {
    throw invalidEvidence();
}
```

일치하는 계좌가 없으면 AI 이미지 분석을 진행하지 않는다.

### 계좌 불일치

HTTP 422 Unprocessable Entity

```json
{
  "message": "송금 내역을 확인하지 못했어요. 신고 계좌와 송금 사진을 확인해 주세요."
}
```

---

## 6. AI-03 송금 내역 분석

`ReportEvidenceAiService`에서 공통 `AnalysisModelClient`를 사용한다.

기존 AI 요청 제한 시간은 30초로 문서화되어 있다.

### 6.1 AI 전달 이미지

검증된 원본 이미지 데이터를 읽고 실제 이미지 헤더를 기반으로 MIME 타입을 결정한다.

지원 MIME 타입:

- `image/png`
- `image/jpeg`

파일 내용은 Base64로 인코딩하여 AI 분석 요청에 사용한다.

```
return new AnalysisModelClient.Image(
        mimeType,
        Base64.getEncoder().encodeToString(bytes)
);
```

### 6.2 분석 항목

| 필드 | 타입 | 설명 |
|---|---|---|
| transferCompleted | boolean | 송금 완료 여부 |
| bank | String | 수취 은행명 |
| accountNumber | String | 수취 계좌번호 전체 |
| accountBox | BoundingBox | 계좌번호 영역 |
| confidence | Double | AI 분석 신뢰도 |

AI 응답 구조 예시:

```json
{
  "transferCompleted": true,
  "bank": "신한",
  "accountNumber": "111-111-1-111111",
  "accountBox": {
    "x1": 0.2,
    "y1": 0.35,
    "x2": 0.8,
    "y2": 0.43
  },
  "confidence": 0.95
}
```

위 JSON은 AI 분석 결과의 구조를 설명하기 위한 예시이다.

### 6.3 검증 조건

1. `transferCompleted=true`
2. 수취 은행명이 존재한다.
3. 수취 계좌번호가 존재한다.
4. 은행명과 계좌번호를 정규화할 수 있다.
5. 정규화된 수취 계좌와 신고 대상 계좌가 일치한다.
6. 유효한 계좌번호 영역 좌표가 존재한다.

AI가 검출한 계좌는 다음과 같이 정규화한다.

```java
String detectedAccount = normalizeAccount(
        analysis.bank() + " " + analysis.accountNumber()
);
```

이후 신고 대상 계좌와 비교한다.

```
if (!Objects.equals(targetAccount, detectedAccount)) {
    throw invalidEvidence();
}
```

RP-05는 보내는 계좌가 아닌 **받는 계좌(receiver)** 를 신고 대상 계좌와 비교한다.

AI가 은행 거래의 실제 진위를 금융기관 시스템을 통해 확인하는 것은 아니다.

### 송금 내역 검증 실패

HTTP 422

```json
{
  "message": "송금 내역을 확인하지 못했어요. 신고 계좌와 송금 사진을 확인해 주세요."
}
```

---

## 7. 계좌번호 영역 추출

`ReportEvidenceImageProcessor`에서 AI가 반환한 좌표를 사용한다.

### 7.1 BoundingBox

AI 좌표는 이미지 전체 크기를 기준으로 0~1 사이의 값이다.

```text
x1, y1 = 영역의 시작 좌표
x2, y2 = 영역의 끝 좌표
```

### 7.2 좌표 검증

`ReportEvidenceService.validateBoundingBox()`는 다음 조건을 검사한다.

1. BoundingBox가 null이 아니어야 한다.
2. 모든 좌표가 유한한 숫자여야 한다.
3. 좌표는 0~1 범위여야 한다.
4. x1은 x2보다 작아야 한다.
5. y1은 y2보다 작아야 한다.

조건을 만족하지 않으면 HTTP 422를 반환한다.

### 7.3 이미지 영역 추출

```java
byte[] croppedImage = imageProcessor.cropAccountNumber(
        file,
        analysis.accountBox()
);
```

기존 이미지 처리 구현의 최소 추출 크기:

| 항목 | 최소 크기 |
|---|---|
| 너비 | 40px |
| 높이 | 12px |

영역을 추출한 뒤 PNG로 변환한다.

### 7.4 추출 결과 검증

`verifySelected()`는 잘라낸 이미지의 크기를 다시 검사한다.

```
if (croppedImage.length == 0
        || croppedImage.length > 5L * 1024 * 1024) {
    throw invalidEvidence();
}
```

이미지가 비어 있거나 5MiB를 초과하면 HTTP 422가 발생한다.

---

## 8. 개인정보 노출 검증

계좌번호 영역을 잘라낸 후 `ReportEvidencePrivacyValidator`로 추가 검증한다.

### 8.1 검증 항목

| 항목 | 의미 |
|---|---|
| accountVisible | 수취 계좌번호가 보이는지 |
| containsName | 사람 이름이 포함되는지 |
| containsOtherAccount | 다른 계좌번호가 포함되는지 |
| containsBalance | 잔액이 포함되는지 |
| containsOtherPersonalInfo | 기타 개인정보가 포함되는지 |
| safe | 저장해도 안전한 영역인지 |

### 8.2 저장 허용 조건

```text
accountVisible = true
containsName = false
containsOtherAccount = false
containsBalance = false
containsOtherPersonalInfo = false
safe = true
```

위 조건을 모두 충족해야 한다.

AI 분석 결과가 불확실하거나 개인정보가 함께 보이면 증빙 저장을 거부한다.

### 개인정보 검증 실패

HTTP 422

```json
{
  "message": "송금 사진에서 개인정보를 안전하게 분리하지 못했어요. 다른 사진으로 다시 시도해 주세요."
}
```

RP-05는 전체 송금 사진을 검은색으로 마스킹하는 방식이 아니라, 필요한 계좌번호 영역만 잘라내고 나머지를 저장하지 않는 방식이다.

---

## 9. 임시 증빙 저장

모든 검증이 성공하면 다음 메서드를 호출한다.

```java
UUID evidenceToken = pendingReportEvidenceService.save(
        authenticatedUserId,
        analysisId,
        targetAccount,
        croppedImage
);
```

### 9.1 저장 과정

1. 정규화된 계좌번호의 해시 생성
2. 임시 Storage 경로 생성
3. 임시 증빙 엔티티 생성
4. 검증된 PNG 이미지 업로드
5. 임시 증빙 메타데이터 DB 저장
6. evidenceToken 반환

### 9.2 계좌번호 저장

계좌번호 원문 대신 HMAC 해시를 저장한다.

```
hashService.hash(
        IdentifierType.ACCOUNT,
        normalizedAccount
);
```

임시 증빙 DB에는 계좌번호 해시를 저장한다.

### 9.3 Supabase Storage

기본 버킷:

```text
report-evidence
```

Storage 설정값:

```properties
storage.supabase-url
storage.service-role-key
storage.evidence-bucket
```

`storage.evidence-bucket` 기본값은 `report-evidence`이다.

### 9.4 임시 저장 경로

```text
pending/{userId}/{uuid}.png
```

예시:

```text
pending/15/550e8400-e29b-41d4-a716-446655440000.png
```

### 9.5 Storage 업로드 방식

`SupabaseReportEvidenceStore.upload()`을 사용한다.

요청:

```http
POST /storage/v1/object/{bucket}/{key}
Content-Type: image/png
```

주요 헤더:

```text
apikey: {service-role-key}
Authorization: Bearer {service-role-key}
Content-Type: image/png
x-upsert: false
```

HTTP 200 또는 201을 반환하면 성공으로 처리한다.

그 외 응답이나 네트워크 오류가 발생하면 `ApiException(503)`을 발생시킨다.

### 9.6 Storage 오류 응답

HTTP 503 Service Unavailable

```json
{
  "message": "신고 증빙 사진을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
}
```

### 9.7 DB 저장 실패 처리

기존 임시 저장 서비스는 Storage 업로드 성공 후 DB 저장에 실패하면 업로드된 파일의 삭제를 시도하도록 문서화되어 있다.

```text
Storage 업로드 성공
    |
    v
DB 저장 실패
    |
    v
업로드된 임시 파일 삭제 시도
    |
    v
예외 다시 발생
```

삭제에도 실패한 경우의 예외 전파 방식은 `PendingReportEvidenceService` 구현을 기준으로 확인해야 한다.

---

## 10. 성공 응답

### 10.1 HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "verified": true,
    "message": "송금 내역의 수취 계좌가 신고 대상과 일치합니다.",
    "evidenceToken": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

위 UUID는 응답 구조를 설명하기 위한 예시이다.

### 10.2 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| code | String | SUCCESS |
| message | String | 공통 성공 메시지 |
| data.verified | boolean | 송금 증빙 검증 성공 여부 |
| data.message | String | 검증 결과 안내 |
| data.evidenceToken | UUID | RP-04에 전달할 임시 증빙 토큰 |

### 10.3 성공 응답 생성 코드

```
return new ReportEvidenceResponse(
        true,
        "송금 내역의 수취 계좌가 신고 대상과 일치합니다.",
        evidenceToken
);
```

현재 `ReportEvidenceService.verifySelected()`는 검증에 실패하면 `verified=false`를 반환하지 않고 예외를 발생시킨다.

따라서 정상 성공 응답에서 `verified`는 true이다.

---

## 11. evidenceToken 사용

RP-05에서 발급한 `evidenceToken`은 RP-04 신고 등록에서 사용한다.

```http
POST /reports
Content-Type: application/json
```

요청 예시:

```json
{
  "analysisId": "89ee4f8f-c83c-4038-a2a9-403e155b20e4",
  "kind": "FRAUD",
  "sentDate": "2026-11-15",
  "referenceLinks": [],
  "evidenceToken": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 11.1 토큰 사용 조건

`PendingReportEvidenceService.consume()`에서 다음을 확인한다.

1. 토큰이 존재해야 한다.
2. 로그인 사용자 ID가 일치해야 한다.
3. analysisId가 일치해야 한다.
4. 만료되지 않은 토큰이어야 한다.
5. 이전에 사용하지 않은 토큰이어야 한다.
6. 증빙 계좌 해시가 JD 판독 결과의 계좌 중 하나와 일치해야 한다.

### 11.2 중복 사용 방지

기존 토큰 처리 코드에서 DB 행 잠금을 사용한다.

```
repository.findByTokenForUpdate(token);
```

검증이 성공하면 다음 메서드로 토큰을 사용 처리한다.

```
pending.consume(now);
```

동일한 토큰의 중복 사용을 방지한다.

### 11.3 토큰 유효 시간

토큰 만료 여부는 다음 메서드로 확인한다.

```
pending.isExpired(now);
```

정확한 유효 시간은 `PendingReportEvidence` 엔티티의 정의를 따른다.

현재 공유된 코드만으로 구체적인 만료 시간은 확정하지 않는다.

---

## 12. RP-04 최종 증빙 연결

RP-04에서 토큰 검증에 성공하면 임시 증빙을 최종 신고와 연결한다.

```text
임시 증빙 토큰 확인
    |
    v
사용자·analysisId·계좌 검증
    |
    v
토큰 사용 처리
    |
    v
Storage 최종 경로 생성
    |
    v
임시 이미지 최종 경로로 복사
    |
    v
Evidence 생성
    |
    v
신고 DB 트랜잭션 완료
```

### 12.1 최종 Storage 경로

```text
reports/{userId}/{uuid}.png
```

### 12.2 최종 증빙 종류

```text
NUMBER_FRAGMENT
```

### 12.3 Supabase Storage 복사

`SupabaseReportEvidenceStore.copyPendingToReport()`를 사용한다.

```
copyPendingToReport(sourceKey, targetKey);
```

요청:

```http
POST /storage/v1/object/copy
Content-Type: application/json
```

Body 구조:

```json
{
  "bucketId": "report-evidence",
  "sourceKey": "pending/15/550e8400-e29b-41d4-a716-446655440000.png",
  "destinationBucket": "report-evidence",
  "destinationKey": "reports/15/11111111-2222-4333-8444-555555555555.png"
}
```

서버는 원본 경로와 대상 경로의 사용자 ID가 동일한지 검증한다.

HTTP 200 또는 201을 반환하면 복사 성공으로 처리한다.

### 12.4 Storage 파일 삭제

임시 파일 삭제:

```
supabaseReportEvidenceStore.deletePending(key);
```

최종 파일 삭제:

```
supabaseReportEvidenceStore.deleteReport(key);
```

두 메서드는 내부적으로 `deleteObject()`를 사용한다.

삭제 요청:

```http
DELETE /storage/v1/object/{bucket}
Content-Type: application/json
```

Body 예시:

```json
{
  "prefixes": [
    "reports/15/11111111-2222-4333-8444-555555555555.png"
  ]
}
```

HTTP 200 또는 204를 반환하면 성공으로 처리한다.

그 외 응답은 Storage 오류로 처리한다.

### 12.5 트랜잭션 후 정리

기존 RP-04 증빙 연결 구현의 정리 정책:

DB 저장 성공:

```text
임시 증빙 파일 삭제
```

DB 저장 실패:

```text
최종 복사본 삭제 시도
```

Storage와 DB는 하나의 원자적 트랜잭션이 아니므로 부분 실패 가능성이 존재한다.

### 12.6 최종 증빙 보관 기간

`ReportEvidenceRetentionPolicy`에 따라 증빙 생성 시각으로부터 1년이다.

```
createdAt.plusYears(1);
```

이는 만료 시각 설정 규칙이다.

실제 파일 자동 삭제 스케줄러의 동작은 별도 확인이 필요하다.

---

## 13. 오류 응답

### 13.1 인증 및 세션 오류

| 조건 | HTTP | 설명 |
|---|---|---|
| 로그인하지 않음 | 401 | 로그인 필요 |
| 인증 사용자 ID 형식 오류 | 401 | 로그인 필요 |
| JD 판독 사용자 불일치 | 401 | 세션 사용자 불일치 |
| JD 판독 결과 없음·만료 | 별도 확인 | 판독 세션 조회 실패 |

### 로그인 필요

HTTP 401

```json
{
  "message": "로그인이 필요합니다."
}
```

### 판독 세션 사용자 불일치

HTTP 401

```json
{
  "message": "판독 세션의 사용자 정보가 일치하지 않습니다."
}
```

### 13.2 계좌 선택 오류

| 조건 | HTTP | 설명 |
|---|---|---|
| accountIndex와 account 동시 전달 | 400 | 계좌 선택 오류 |
| accountIndex가 음수 | 400 | 잘못된 인덱스 |
| accountIndex가 목록 범위 초과 | 400 | 잘못된 인덱스 |
| 두 값 생략, JD 계좌 0개 | 400 | 계좌 선택 필요 |
| 두 값 생략, JD 계좌 2개 이상 | 400 | 계좌 선택 필요 |
| account에 `*` 포함 | 400 | 마스킹 값 비교 불가 |
| 유효하지 않은 전체 계좌 입력 | 422 | 계좌 검증 실패 |
| JD 목록에 없는 계좌 입력 | 422 | JD 계좌 불일치 |

#### 두 값 동시 전달 또는 인덱스 오류

HTTP 400

```json
{
  "message": "판독 결과에서 신고할 계좌를 다시 선택해 주세요."
}
```

#### 계좌 미선택

HTTP 400

```json
{
  "message": "판독 결과에서 신고할 계좌를 선택해 주세요."
}
```

#### 마스킹된 계좌번호 입력

HTTP 400

```json
{
  "message": "가려진 표시값으로 계좌를 비교할 수 없어요. account 대신 accountIndex로 선택해 주세요."
}
```

#### JD 계좌 불일치

HTTP 422

```json
{
  "message": "송금 내역을 확인하지 못했어요. 신고 계좌와 송금 사진을 확인해 주세요."
}
```

### 13.3 이미지 및 AI 오류

| 조건 | HTTP | 설명 |
|---|---|---|
| 이미지 누락 | 별도 확인 | 요청 파트 누락 처리 |
| 이미지가 비어 있음 | 400 | 이미지 검증 실패 |
| 이미지 크기 초과 | 400 또는 413 | 업로드 제한 설정에 따라 다를 수 있음 |
| 지원하지 않는 이미지 형식 | 400 | JPEG/PNG만 허용 |
| 이미지 해상도 초과 | 400 | 최대 픽셀 수 초과 |
| 송금 완료 확인 실패 | 422 | 송금 증빙 검증 실패 |
| 이미지 수취 계좌 불일치 | 422 | 계좌 일치 검사 실패 |
| 계좌번호 영역 좌표 오류 | 422 | 좌표 검증 실패 |
| 계좌번호 이미지 추출 실패 | 422 | 이미지 가공 실패 |
| 개인정보 노출 검사 실패 | 422 | 개인정보 분리 실패 |
| Storage 저장 실패 | 503 | 임시 증빙 저장 장애 |

이미지 누락의 HTTP 응답은 `@RequestPart("file")`와 Spring MVC의 예외 처리 설정을 실제 요청으로 확인해야 한다.

### 13.4 ApiException 응답 구조

`ApiExceptionHandler`는 다음 형태로 오류를 반환한다.

```json
{
  "message": "오류 메시지"
}
```

예시:

```java
@ExceptionHandler(ApiException.class)
ResponseEntity<?> handle(ApiException e) {
    return ResponseEntity
            .status(e.getStatus())
            .body(Map.of("message", e.getMessage()));
}
```

현재 `ApiExceptionHandler`에 `@Order(Ordered.HIGHEST_PRECEDENCE)`가 설정되어 있다.

`BusinessException` 또는 일부 전역 오류는 별도의 `ApiResponse` 형식으로 반환된다.

### 13.5 Storage 오류

HTTP 503 Service Unavailable

```json
{
  "message": "신고 증빙 사진을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
}
```

---

## 14. 데이터 저장 및 보관 정책

### 14.1 RP-05 임시 데이터

저장 대상:

- 사용자 ID
- JD analysisId
- 계좌 해시
- 임시 Storage 파일 키
- 증빙 토큰
- 토큰 사용 및 만료 관련 정보

구체적인 DB 컬럼과 기본 만료 시간은 `PendingReportEvidence` 엔티티 정의를 따른다.

### 14.2 최종 신고 데이터

RP-04에서 증빙 토큰을 사용하면 다음 정보가 `Evidence`로 저장된다.

- 신고 ID
- 증빙 종류
- 최종 Storage 파일 키
- 계좌 해시
- 만료 시각

### 14.3 개인정보 보호

- 전체 송금 사진을 최종 신고 증빙으로 저장하지 않는다.
- 수취 계좌번호가 포함된 작은 영역만 저장한다.
- AI로 추가 개인정보 노출 여부를 검사한다.
- 계좌번호 원문 대신 해시를 저장한다.
- 클라이언트에 계좌번호 원문을 반환하지 않는다.
- 증빙 토큰으로 사용자와 JD 판독 결과의 소유권을 검사한다.
- 마스킹된 계좌번호만 아는 경우 accountIndex로 계좌를 선택한다.

AI 판독 오류로 개인정보가 포함된 영역이 통과할 가능성은 남아 있으므로 운영 전 검증이 필요하다.

---

## 15. 실제 구현 및 테스트 현황

### 15.1 구현 확인

| 항목 | 상태 |
|---|---|
| 로그인 사용자 확인 | 코드 확인 |
| JD 판독 세션 조회 | 코드 확인 |
| accountIndex 계좌 선택 | 코드 확인 |
| account 전체 계좌 선택 | 코드 확인 |
| accountIndex 0-based 검증 | 코드 확인 |
| 두 선택값 동시 전달 차단 | 코드 확인 |
| 계좌 1개 자동 선택 | 코드 확인 |
| 마스킹된 계좌 입력 차단 | 코드 확인 |
| JD 계좌와 선택 계좌 비교 | 코드 확인 |
| AI-03 송금 완료 판독 | 코드 확인 |
| 수취 계좌 추출 및 비교 | 코드 확인 |
| 계좌번호 영역 추출 호출 | 코드 확인 |
| 개인정보 노출 검증 호출 | 코드 확인 |
| 임시 증빙 저장 호출 | 코드 확인 |
| evidenceToken 발급 | 코드 확인 |
| Supabase Storage 업로드 | 코드 확인 |
| Supabase Storage 복사·삭제 | 코드 확인 |

위 표는 제공된 구현 코드에서 확인한 항목이다.

실제 외부 AI, DB, Supabase Storage를 사용하는 통합 테스트 성공을 의미하지는 않는다.

### 15.2 이전 연동 테스트 오류

이전 JD → RP-05 연동 테스트에서는 다음 로그가 발생했다.

```text
RP-05 실패: JD 판독 결과에 신고 계좌 없음
```

발생 위치:

```java
boolean accountExists = jdAccounts.stream()
        .map(this::normalizeAccount)
        .anyMatch(targetAccount::equals);
```

JD가 반환한 계좌 목록과 RP-05에서 선택한 계좌가 정규화 후 일치하지 않으면 검증이 중단된다.

현재 `accountIndex` 선택 방식이 추가되었지만, 이 방식으로 이전 연동 오류가 해결되었는지는 재테스트가 필요하다.

---

## 16. 추가 테스트 및 개선 사항

### 계좌 선택 테스트

- [ ] accountIndex=0으로 첫 번째 계좌 선택
- [ ] accountIndex=1로 두 번째 계좌 선택
- [ ] account만 전달하여 전체 계좌 검증
- [ ] accountIndex와 account 동시 전달 시 400
- [ ] accountIndex=-1 전달 시 400
- [ ] accountIndex가 목록 범위를 벗어나면 400
- [ ] 계좌 1개인 경우 선택값 없이 자동 선택
- [ ] 계좌 0개인 경우 선택값 없이 요청 시 400
- [ ] 계좌 2개 이상인 경우 선택값 없이 요청 시 400
- [ ] 마스킹된 account 전달 시 400
- [ ] JD에 없는 전체 계좌 입력 시 422

### 이미지 및 AI 테스트

- [ ] JPEG 이미지 업로드 성공
- [ ] PNG 이미지 업로드 성공
- [ ] 비어 있는 파일 요청
- [ ] 이미지 파트 누락 요청
- [ ] 5MiB 초과 파일 요청
- [ ] 지원하지 않는 파일 형식
- [ ] 송금 미완료 이미지
- [ ] 수취 계좌번호 불일치
- [ ] BoundingBox 좌표 오류
- [ ] 계좌번호 영역 추출 오류
- [ ] 개인정보 노출 검증 실패

### Storage 및 토큰 테스트

- [ ] Supabase 임시 증빙 업로드 성공
- [ ] Storage 업로드 실패 시 503 반환
- [ ] DB 저장 실패 시 임시 파일 정리
- [ ] evidenceToken 정상 발급
- [ ] evidenceToken 사용자 불일치 차단
- [ ] evidenceToken analysisId 불일치 차단
- [ ] evidenceToken 만료 차단
- [ ] evidenceToken 중복 사용 차단
- [ ] RP-05 → RP-04 최종 증빙 연결
- [ ] Storage 복사 실패 시 오류 처리
- [ ] 최종 신고 삭제 시 Storage 이미지 삭제

### 추가 확인

1. `PendingReportEvidence`의 토큰 만료 시간 확인
2. 임시 증빙 만료 후 자동 삭제 스케줄러 확인
3. 계좌 선택 인덱스가 프론트엔드 JD 계좌 목록 순서와 일치하는지 확인
4. AI-03 계좌번호 영역 추출 정확도 검증
5. 개인정보 노출 검사의 오탐·미탐 테스트
6. Spring MVC 이미지 누락 및 업로드 크기 초과 응답 확인
7. Storage 장애 및 DB 실패 시 파일 정리 확인

---

## 17. 프론트엔드 연동 가이드

### 17.1 권장 연동 순서

```text
1. 사용자가 JD 판독 실행
        |
        v
2. analysisId 확보
        |
        v
3. JD가 추출한 계좌 목록 표시
        |
        v
4. 사용자가 신고할 계좌 선택
        |
        v
5. 선택한 계좌의 accountIndex 확보
        |
        v
6. 송금 완료 사진 선택
        |
        v
7. POST /reports/evidence 요청
        |
        v
8. evidenceToken 저장
        |
        v
9. POST /reports 신고 등록
```

### 17.2 React 요청 예시

```javascript
async function verifyReportEvidence({
  baseUrl,
  analysisId,
  accountIndex,
  file,
  csrfToken
}) {
  const formData = new FormData();

  formData.append("analysisId", analysisId);
  formData.append("accountIndex", String(accountIndex));
  formData.append("file", file);

  const response = await fetch(
    `${baseUrl}/reports/evidence`,
    {
      method: "POST",
      credentials: "include",
      headers: csrfToken
        ? { "X-XSRF-TOKEN": csrfToken }
        : {},
      body: formData
    }
  );

  const result = await response.json();

  if (!response.ok) {
    throw new Error(
      result.message ?? "송금 증빙 검증에 실패했습니다."
    );
  }

  return result.data;
}
```

이 예시는 프론트엔드에서 `accountIndex`를 사용하는 방법을 보여준다.

로그인 세션 쿠키를 전달하기 위해 `credentials: "include"`를 사용한다.

CSRF 헤더 이름과 토큰 발급 방식은 실제 Spring Security 설정에 따라 확인해야 한다.

`multipart/form-data`의 `Content-Type` 헤더는 직접 지정하지 않는다. 브라우저가 FormData에 필요한 boundary를 자동 설정한다.

### 17.3 성공 응답 활용

```javascript
const result = await verifyReportEvidence({
  baseUrl: API_BASE_URL,
  analysisId,
  accountIndex: selectedAccountIndex,
  file: selectedFile,
  csrfToken
});

const evidenceToken = result.evidenceToken;
```

발급받은 evidenceToken은 이후 RP-04 요청에 전달한다.

### 17.4 프론트엔드 주의사항

- accountIndex는 0부터 시작한다.
- accountIndex와 account를 동시에 보내면 안 된다.
- 마스킹된 계좌 문자열을 account로 보내면 안 된다.
- accountIndex는 JD 판독 결과의 계좌 목록 순서와 일치해야 한다.
- JD 판독과 RP-05는 동일한 로그인 세션을 사용해야 한다.
- RP-05 응답의 evidenceToken은 RP-04 등록 요청에 전달한다.
- 오류 응답이 항상 동일한 JSON 구조는 아니므로 message 필드와 공통 오류 구조를 모두 처리해야 한다.
- 이미지 업로드 요청에는 FormData를 사용한다.

---

## 18. 최종 정리

| 기능 | 처리 |
|---|---|
| JD 계좌 선택 | accountIndex 또는 account |
| accountIndex 기준 | 0부터 시작 |
| 계좌 자동 선택 | JD 계좌가 정확히 1개인 경우 |
| 계좌 선택 오류 | HTTP 400 |
| JD 계좌 불일치 | HTTP 422 |
| 이미지 검증 | JPEG/PNG, 최대 5MiB |
| 송금 내역 분석 | AI-03 |
| 개인정보 처리 | 계좌번호 영역 추출 및 추가 검증 |
| 임시 파일 저장 | Supabase Storage |
| 성공 응답 | HTTP 200, verified=true |
| 증빙 토큰 | evidenceToken |
| 최종 신고 연결 | RP-04 POST /reports |

RP-05는 JD 판독 결과의 계좌를 선택하고, 송금 완료 사진에서 추출한 수취 계좌와 비교하여 임시 증빙 토큰을 생성하는 역할을 담당한다.

최종 신고 생성과 증빙 연결은 RP-04에서 수행한다.

현재 제공된 코드에서 계좌 선택 규칙과 Storage 요청 방식은 확인되었으며, 실제 API 통합 테스트와 외부 서비스 연동 성공 여부는 별도 검증이 필요하다.