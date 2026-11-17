
# RP-01~03 신고 리스트 API 명세서

## 1. API 개요

계좌번호, 전화번호, X(SNS) 계정으로 신고 이력을 조회하고 위험도를 확인하는 API이다.

사용자는 식별자별 신고 현황과 연관 정보를 확인하고, 자신이 알고 있는 전체 식별자 값이 신고 리스트에 등록된 값과 일치하는지 비교할 수 있다.

모든 API는 로그인 없이 이용 가능하다.

### API 목록

| 기능 ID | 기능 | Method | Endpoint | 인증 |
|---|---|---|---|---|
| RP-01 | 신고 리스트 조회 | GET | `/lookup` | 불필요 |
| RP-02 | 신고 리스트 상세 조회 | GET | `/lookup/{identifierId}` | 불필요 |
| RP-03 | 식별자 전체 값 비교 | POST | `/lookup/{identifierId}/compare` | 불필요 |

### 구현 클래스

| 구분 | 클래스 |
|---|---|
| Controller | `LookupController` |
| Service | `LookupService` |
| 위험도 계산 | `LookupRiskCalculator` |
| 식별자 정규화 | `IdentifierNormalizer` |
| 식별자 마스킹 | `IdentifierMasker` |
| 식별자 해시 | `IdentifierHashService` |
| 요청 제한 | `LookupRateLimiter` |
| 요청 제한 인터셉터 | `LookupRateLimitInterceptor` |
| 요청 제한 설정 | `WebConfig` |
| 예외 처리 | `GlobalExceptionHandler` |

### 공통 응답 구조

모든 정상 응답은 `ApiResponse<T>` 형식을 사용한다.

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {}
}
```

`data`는 각 API의 응답 DTO에 따라 달라진다.

---

# 2. 공통 정책

## 2.1 조회 대상 식별자

| 종류 | Enum | 입력 예시 |
|---|---|---|
| 은행 계좌 | ACCOUNT | `신한 111-111-111-111` |
| 전화번호 | PHONE | `010-1234-5678` |
| X 계정 | SNS | `@Test_User` |

식별자 종류는 `IdentifierNormalizer`가 입력값을 분석하여 자동 판별한다.

## 2.2 식별자 정규화

입력값을 비교하기 전에 일관된 형식으로 변환한다.

| 입력값 | 정규화 결과 |
|---|---|
| `신한 111-111-111-111` | `SHINHAN:111111111111` |
| `신한은행 111-111-111-111` | `SHINHAN:111111111111` |
| `010-1234-5678` | `01012345678` |
| `@Test_User` | `test_user` |

### 은행 계좌 정규화

- 은행명과 계좌번호를 함께 입력해야 한다.
- 계좌번호는 하이픈과 공백을 제거한다.
- 계좌번호는 숫자 8~16자리만 허용한다.
- 은행명은 내부 표준 코드로 통일한다.
- 최종 형식은 `은행코드:계좌번호`이다.

지원 은행:

| 은행 | 내부 코드 |
|---|---|
| 국민은행 | KB |
| 신한은행 | SHINHAN |
| 우리은행 | WOORI |
| 하나은행 | HANA |
| 농협은행 | NH |
| 기업은행 | IBK |
| 카카오뱅크 | KAKAO |
| 토스뱅크 | TOSS |

### 전화번호 정규화

- `01`로 시작하는 지원 휴대전화번호 형식만 허용한다.
- 숫자, 공백, 하이픈을 포함한 입력을 허용한다.
- 정규화 시 공백과 하이픈을 제거한다.

### X 계정 정규화

- `@`으로 시작해야 한다.
- 영문, 숫자, 밑줄만 허용한다.
- 사용자명은 1~15자여야 한다.
- 정규화 시 `@`을 제거하고 소문자로 변환한다.

## 2.3 식별자 해시

검색 및 비교에는 HMAC-SHA256을 사용한다.

```text
식별자 원본 입력
       ↓
종류 판별 및 정규화
       ↓
type + ":" + normalizedValue
       ↓
HMAC-SHA256 해시 생성
       ↓
DB의 value_hash와 비교
```

해시 생성 시 서버 설정값 `security.identifier-hash-secret`을 사용한다.

RP-01 및 RP-03은 식별자 검색·비교를 위해 암호문을 복호화하지 않는다.

## 2.4 신고 집계 정책

최근 1년 이내의 유효한 신고만 집계한다.

### 집계에 포함되는 상태

| 상태 | 포함 여부 |
|---|---|
| PUBLISHED | 포함 |
| RESPONDING | 포함 |
| REVIEWING | 포함 |
| PRIVATE | 제외 |

`ReportIdentifierRepository.findActiveReportsByIdentifier()`를 사용하여 유효한 신고 연결 정보를 조회한다.

### 중복 신고자 처리

동일한 휴대폰 해시(`reporterHash`)를 가진 신고자는 신고 종류별로 한 명으로 계산한다.

```text
같은 신고자가 피해 신고를 3건 등록
            ↓
피해 신고자 수 = 1명
```

피해 신고와 의심 제보는 각각 별도로 집계한다.

## 2.5 위험도 계산 규칙

`LookupRiskCalculator`의 기준을 따른다.

| 위험도 | 조건 | 의미 |
|---|---|---|
| DANGER | 피해 신고자 1명 이상 | 위험 |
| SUSPICION | 피해 신고자 0명, 의심 제보자 2명 이상 | 의심 |
| SAFE | 그 외 | 신고 리스트 기준 위험 신호 없음 |

위험도 계산 우선순위:

```text
피해 신고자 수 >= 1
       ├─ YES → DANGER
       └─ NO
            ↓
     의심 제보자 수 >= 2
            ├─ YES → SUSPICION
            └─ NO  → SAFE
```

`SAFE`는 현재 신고 리스트 집계 기준에서 위험 조건을 충족하지 않았다는 뜻이며, 거래 자체의 안전성을 보증하지 않는다.

## 2.6 공통 요청 제한

RP-01~03에는 IP별 요청 제한이 적용된다.

| 항목 | 정책 |
|---|---|
| 기준 | 클라이언트 IP |
| 최대 요청 | 1분당 30회 |
| 적용 API | RP-01, RP-02, RP-03 |
| 요청 횟수 | 세 API 합산 |
| 31번째 요청 | HTTP 429 |
| 오류 코드 | TOO_MANY_REQUESTS |
| 저장 방식 | 서버 메모리 |
| 카운터 정리 | 60초마다 |

서버는 `request.getRemoteAddr()`로 클라이언트 IP를 확인한다.

최초 요청 시점부터 1분간 요청 횟수를 계산하며, 제한 시간이 만료되면 새로운 카운터를 사용한다.

요청 횟수 초과 시 다음 예외가 발생한다.

```
throw new BusinessException(
        ErrorCode.TOO_MANY_REQUESTS
);
```

카운터는 `ConcurrentHashMap`에 저장되므로 여러 서버 인스턴스를 사용하는 경우 서버별로 제한이 적용된다.

---

# 3. RP-01 신고 리스트 조회

## 3.1 API 정보

| 항목 | 내용 |
|---|---|
| 기능 ID | RP-01 |
| Method | GET |
| Endpoint | `/lookup` |
| 인증 | 불필요 |
| Request Body | 없음 |
| 성공 코드 | 200 OK |
| Response DTO | `LookupResponse` |

계좌번호, 전화번호 또는 X 계정으로 신고 리스트를 조회한다.

입력값을 정규화하고 HMAC 해시로 DB의 식별자와 비교하여 위험도를 계산한다.

## 3.2 요청

```http
GET /lookup?q=신한 111-111-111-111
```

실제 HTTP 요청에서는 공백 및 특수문자를 URL 인코딩하여 전송한다.

### Query Parameter

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| q | String | O | 조회할 계좌번호, 전화번호 또는 X 계정 |

### 요청 예시

은행 계좌:

```http
GET /lookup?q=신한%20111-111-111-111
```

전화번호:

```http
GET /lookup?q=010-1234-5678
```

X 계정:

```http
GET /lookup?q=%40Test_User
```

## 3.3 성공 응답

### 예시 1. 위험(DANGER)

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "identifierId": 308,
    "type": "ACCOUNT",
    "value": "SHINHAN:111111111111",
    "riskLevel": "DANGER",
    "fraudCount": 1,
    "suspicionCount": 2
  }
}
```

피해 신고자가 1명 이상이므로 `DANGER`이다.

### 예시 2. 의심(SUSPICION)

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "identifierId": 309,
    "type": "ACCOUNT",
    "value": "SHINHAN:222222222222",
    "riskLevel": "SUSPICION",
    "fraudCount": 0,
    "suspicionCount": 2
  }
}
```

피해 신고자는 없지만 의심 제보자가 2명 이상이므로 `SUSPICION`이다.

### 예시 3. 검색 결과 없음

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "identifierId": null,
    "type": "ACCOUNT",
    "value": null,
    "riskLevel": "SAFE",
    "fraudCount": 0,
    "suspicionCount": 0
  }
}
```

검색 대상 식별자가 DB에 존재하지 않으면 404를 반환하지 않는다.

`identifierId`와 `value`를 null로 설정하고 `SAFE`를 반환한다.

참고로 식별자 자체가 존재하지만 최근 1년간 유효한 신고가 없다면 `identifierId`와 `value`는 반환될 수 있으며, 위험도와 신고자 수는 `SAFE`, 0명으로 계산된다.

## 3.4 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| identifierId | Long | 식별자 ID, 검색 결과가 없으면 null |
| type | IdentifierType | ACCOUNT, PHONE, SNS |
| value | String | 검색 입력의 정규화 값, 식별자가 없으면 null |
| riskLevel | String | DANGER, SUSPICION, SAFE |
| fraudCount | long | 서로 다른 피해 신고자 수 |
| suspicionCount | long | 서로 다른 의심 제보자 수 |

`value`는 DB에서 복호화한 원문이 아니라 사용자가 입력한 값의 정규화 결과이다.

## 3.5 처리 흐름

```text
GET /lookup?q=...
       |
       v
입력값 유효성 검사
       |
       v
식별자 종류 판별 및 정규화
       |
       v
HMAC-SHA256 해시 생성
       |
       v
식별자 DB 검색
       |
       +-- 검색 결과 없음
       |       |
       |       v
       |   SAFE / 0명 반환
       |
       v
최근 1년간 유효한 신고 조회
       |
       v
피해 신고자 중복 제거
       |
       v
의심 제보자 중복 제거
       |
       v
위험도 계산
       |
       v
LookupResponse 반환
```

## 3.6 오류 응답

| HTTP | 오류 코드 | 상황 |
|---|---|---|
| 400 | INVALID_REQUEST | 잘못된 식별자 형식 |
| 400 | INVALID_REQUEST | 필수 q 파라미터 누락 |
| 429 | TOO_MANY_REQUESTS | IP별 요청 제한 초과 |
| 500 | INTERNAL_SERVER_ERROR | 서버 내부 오류 |

### 잘못된 식별자 형식

HTTP 400 Bad Request

```json
{
  "code": "INVALID_REQUEST",
  "message": "잘못된 요청 값입니다.",
  "data": null
}
```

### 필수 파라미터 누락

HTTP 400 Bad Request

```json
{
  "code": "INVALID_REQUEST",
  "message": "필수 요청 파라미터가 누락되었습니다: q",
  "data": null
}
```

---

# 4. RP-02 신고 리스트 상세 조회

## 4.1 API 정보

| 항목 | 내용 |
|---|---|
| 기능 ID | RP-02 |
| Method | GET |
| Endpoint | `/lookup/{identifierId}` |
| 인증 | 불필요 |
| 성공 코드 | 200 OK |
| Response DTO | `LookupDetailResponse` |

특정 식별자의 신고 현황, 위험도, 연관 식별자 및 신고 타임라인을 조회한다.

## 4.2 요청

```http
GET /lookup/308
```

### Path Parameter

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| identifierId | Long | O | 상세 조회할 식별자 ID |

## 4.3 성공 응답

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "identifierId": 308,
    "type": "ACCOUNT",
    "maskedValue": "신한 111-111-******",
    "riskLevel": "DANGER",
    "fraudCount": 1,
    "suspicionCount": 2,
    "relatedIdentifiers": [
      {
        "identifierId": 309,
        "type": "PHONE",
        "maskedValue": "010-****-1234"
      }
    ],
    "timeline": [
      {
        "date": "2026-11-15",
        "kind": "FRAUD"
      },
      {
        "date": "2026-11-14",
        "kind": "SUSPICION"
      }
    ]
  }
}
```

## 4.4 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| identifierId | Long | 조회 대상 식별자 ID |
| type | IdentifierType | 식별자 종류 |
| maskedValue | String | 마스킹된 식별자 |
| riskLevel | String | 신고 리스트 기준 위험도 |
| fraudCount | long | 서로 다른 피해 신고자 수 |
| suspicionCount | long | 서로 다른 의심 제보자 수 |
| relatedIdentifiers | List | 연관 식별자 목록 |
| timeline | List | 신고 타임라인 |

### 마스킹 정책

식별자 원문을 반환하지 않고 `IdentifierMasker`로 생성한 마스킹 값을 사용한다.

| 종류 | 정규화 값 예시 | 마스킹 결과 |
|---|---|---|
| ACCOUNT | `SHINHAN:111111111111` | `신한 111-111-******` |
| PHONE | `01012341234` | `010-****-1234` |
| SNS | `test_user` | `@test_use*` |

계좌번호는 마지막 6자리를 가리며, 전화번호는 중간 부분을 가린다.

SNS 계정은 최대 앞 8글자를 표시하되 최소 한 글자는 마스킹한다.

## 4.5 연관 식별자

같은 신고에 함께 등록된 다른 식별자를 조회한다.

다음 조건을 모두 만족해야 반환한다.

1. 현재 조회 대상 식별자와 다른 식별자여야 한다.
2. 최근 1년간 유효한 신고에 함께 등장해야 한다.
3. 숨김 처리되지 않은 식별자여야 한다.
4. 서로 다른 신고자 2명 이상이 해당 연관 식별자를 신고에 포함해야 한다.

예시:

```text
신고자 A
 ├── 계좌 308
 └── 전화번호 309

신고자 B
 ├── 계좌 308
 └── 전화번호 309

서로 다른 신고자 2명 확인
          ↓
전화번호 309를 연관 식별자로 반환
```

### 연관 식별자 응답

```json
{
  "identifierId": 309,
  "type": "PHONE",
  "maskedValue": "010-****-1234"
}
```

### RelatedIdentifierResponse

| 필드 | 타입 | 설명 |
|---|---|---|
| identifierId | Long | 연관 식별자 ID |
| type | IdentifierType | 연관 식별자 종류 |
| maskedValue | String | 마스킹된 식별자 값 |

연관 식별자는 `identifierId` 오름차순으로 정렬한다.

조건을 만족하는 연관 식별자가 없으면 빈 배열을 반환한다.

## 4.6 신고 타임라인

해당 식별자에 연결된 신고의 등록 날짜와 신고 종류를 반환한다.

```json
[
  {
    "date": "2026-11-15",
    "kind": "FRAUD"
  },
  {
    "date": "2026-11-14",
    "kind": "SUSPICION"
  }
]
```

### LookupTimelineResponse

| 필드 | 타입 | 설명 |
|---|---|---|
| date | LocalDate | 신고 등록 날짜 |
| kind | ReportKind | FRAUD 또는 SUSPICION |

타임라인은 다음 규칙으로 생성한다.

- 유효한 신고만 포함한다.
- 신고 ID를 기준으로 동일 신고의 중복을 제거한다.
- 생성 일시 최신순으로 정렬한다.
- 생성 일시가 같으면 신고 ID 내림차순으로 정렬한다.
- 타임라인에는 일자와 신고 종류만 반환한다.

타임라인은 신고자 수 집계와 다르게, 서로 다른 신고자의 수가 아니라 유효한 신고 기록을 기준으로 생성한다.

## 4.7 처리 흐름

```text
GET /lookup/{identifierId}
        |
        v
식별자 ID로 조회
        |
        +-- 없음 → 404
        |
        v
최근 1년간 유효한 신고 조회
        |
        v
신고자 해시 중복 제거
        |
        v
위험도 계산
        |
        v
신고 타임라인 생성
        |
        v
동일 신고의 연관 식별자 조회
        |
        v
숨김 식별자 제외
        |
        v
서로 다른 신고자 2명 이상 조건 적용
        |
        v
LookupDetailResponse 반환
```

## 4.8 오류 응답

| HTTP | 오류 코드 | 상황 |
|---|---|---|
| 400 | INVALID_REQUEST | 잘못된 identifierId 형식 |
| 404 | IDENTIFIER_NOT_FOUND | 식별자가 존재하지 않음 |
| 429 | TOO_MANY_REQUESTS | 요청 횟수 제한 초과 |
| 500 | INTERNAL_SERVER_ERROR | 서버 내부 오류 |

### 식별자 없음

HTTP 404 Not Found

```json
{
  "code": "IDENTIFIER_NOT_FOUND",
  "message": "조회 대상 정보를 찾을 수 없습니다.",
  "data": null
}
```

위 메시지는 ErrorCode 설정을 확인하여 실제 문자열과 일치하도록 유지해야 한다.

---

# 5. RP-03 식별자 전체 값 비교

## 5.1 API 정보

| 항목 | 내용 |
|---|---|
| 기능 ID | RP-03 |
| Method | POST |
| Endpoint | `/lookup/{identifierId}/compare` |
| 인증 | 불필요 |
| Content-Type | application/json |
| 성공 코드 | 200 OK |
| Request DTO | `LookupCompareRequest` |
| Response DTO | `LookupCompareResponse` |

사용자가 알고 있는 전체 식별자 값을 입력하여 DB에 저장된 식별자 해시와 비교한다.

식별자 값이 일치하는지 여부를 검증하는 API이다.

## 5.2 요청

```http
POST /lookup/308/compare
Content-Type: application/json
```

### Path Parameter

| 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|
| identifierId | Long | O | 비교 대상 식별자 ID |

### Request Body

```json
{
  "value": "신한 111-111-111-111"
}
```

### 요청 필드

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| value | String | O | `@NotBlank` |

`value`가 비어 있거나 공백으로만 구성된 경우 HTTP 400을 반환한다.

값이 존재하더라도 지원하지 않는 식별자 형식이면 HTTP 400을 반환한다.

## 5.3 성공 응답

### 예시 1. 일치

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "matched": true,
    "value": "SHINHAN:111111111111"
  }
}
```

식별자 유형과 해시가 일치하면 다음 값을 반환한다.

- matched: true
- value: 사용자가 입력한 전체 식별자의 정규화 값

### 예시 2. 불일치

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "matched": false,
    "value": null
  }
}
```

식별자 종류가 다르거나 해시가 일치하지 않으면 전체 값을 반환하지 않는다.

**불일치는 오류가 아니므로 HTTP 200을 반환한다.**

## 5.4 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| matched | boolean | 입력값과 저장된 해시의 일치 여부 |
| value | String | 일치 시 정규화된 입력값, 불일치 시 null |

## 5.5 비교 방식

```text
POST /lookup/{identifierId}/compare
              |
              v
identifierId로 식별자 조회
              |
              +-- 없음 → 404
              |
              v
입력값 정규화
              |
              v
식별자 유형 비교
              |
              +-- 다름
              |     |
              |     v
              |  matched=false
              |  value=null
              |
              v
HMAC-SHA256 해시 생성
              |
              v
DB의 value_hash와 비교
              |
              +-- 불일치
              |     |
              |     v
              |  matched=false
              |  value=null
              |
              v
matched=true
value=정규화된 입력값
```

### 해시 비교

해시 문자열을 UTF-8 바이트 배열로 변환하고 `MessageDigest.isEqual()`로 비교한다.

```java
boolean matched = MessageDigest.isEqual(
        identifier.getValueHash().getBytes(
                StandardCharsets.UTF_8
        ),
        inputHash.getBytes(
                StandardCharsets.UTF_8
        )
);
```

### 개인정보 보호

- 비교를 위한 사용자 입력값은 별도로 DB에 저장하지 않는다.
- 입력값을 정규화한 뒤 해시로 비교한다.
- 기존 식별자 암호문을 복호화하지 않는다.
- 일치하지 않으면 전체 식별자 값을 노출하지 않는다.
- 일치하면 사용자가 이미 입력한 값의 정규화 결과만 반환한다.

## 5.6 오류 응답

| HTTP | 오류 코드 | 상황 |
|---|---|---|
| 400 | message 응답 | value 누락 또는 공백 (@NotBlank 검증 실패) |
| 400 | INVALID_REQUEST | 잘못된 식별자 형식 |
| 400 | INVALID_REQUEST | 잘못된 identifierId 형식 |
| 404 | IDENTIFIER_NOT_FOUND | 비교 대상 식별자 없음 |
| 429 | TOO_MANY_REQUESTS | 요청 횟수 제한 초과 |
| 500 | INTERNAL_SERVER_ERROR | 서버 내부 오류 |


### value 누락 또는 공백

HTTP 400 Bad Request

`LookupCompareRequest`의 `@NotBlank` 검증에 실패한 경우이다.

`ApiExceptionHandler`에서 처리하며, 필드의 검증 메시지를 반환한다.

```json
{
  "message": "비교할 값을 입력해 주세요."
}
```

### 잘못된 식별자 형식

HTTP 400 Bad Request

입력값이 비어 있지는 않지만 지원하지 않는 식별자 형식인 경우이다.

`IdentifierNormalizer`에서 `IllegalArgumentException`이 발생하고,
`GlobalExceptionHandler`에서 공통 오류 응답으로 변환한다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "잘못된 요청 값입니다.",
  "data": null
}
```


### 식별자 없음

HTTP 404 Not Found

```json
{
  "code": "IDENTIFIER_NOT_FOUND",
  "message": "조회 대상 정보를 찾을 수 없습니다.",
  "data": null
}
```

---

# 6. 공통 오류 응답

## 6.1 오류 코드 정리

| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | INVALID_REQUEST | 요청값 누락 또는 형식 오류 |
| 404 | IDENTIFIER_NOT_FOUND | 대상 식별자가 없음 |
| 429 | TOO_MANY_REQUESTS | IP별 요청 횟수 제한 초과 |
| 500 | INTERNAL_SERVER_ERROR | 서버 내부 오류 |


### 429 Too Many Requests

RP-01~03 요청 횟수를 합산하여 동일 IP에서 1분에 30회를 초과하면 발생한다.

HTTP 429 Too Many Requests

```json
{
  "code": "TOO_MANY_REQUESTS",
  "message": "조회 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
  "data": null
}
```

요청 제한 정책:

- 동일 IP별 1분 최대 30회
- RP-01, RP-02, RP-03 요청 횟수 합산
- 31번째 요청부터 HTTP 429 반환
- 서버 메모리에 카운터 저장
- 제한 시간 경과 후 새로운 카운터 사용


### 500 Internal Server Error

```json
{
  "code": "INTERNAL_SERVER_ERROR",
  "message": "서버 내부 오류가 발생했습니다.",
  "data": null
}
```

## 6.2 예외 처리 정책

`GlobalExceptionHandler`에서 예외를 공통 응답으로 변환한다.

| 예외 | 처리 |
|---|---|
| BusinessException | ErrorCode에 정의된 상태 및 메시지 |
| IllegalArgumentException | 400 INVALID_REQUEST |
| MissingServletRequestParameterException | 400 INVALID_REQUEST |
| MethodArgumentNotValidException | 400 INVALID_REQUEST |
| MethodArgumentTypeMismatchException | 400 INVALID_REQUEST |
| HttpMessageNotReadableException | 400 INVALID_REQUEST |
| 기타 Exception | 500 INTERNAL_SERVER_ERROR |

---

# 7. 데이터 및 보안 정책

## 7.1 사용 데이터

| 데이터 | 용도 |
|---|---|
| identifiers | 식별자 종류, 해시, 마스킹 값 |
| reports | 신고 종류, 신고자 해시, 상태, 등록 일시 |
| report_identifiers | 신고와 식별자의 연결 |

RP-01~03은 조회 전용이며 신고 또는 식별자 데이터를 변경하지 않는다.

## 7.2 조회 기간

최근 1년간 유효한 신고만 집계한다.

`OffsetDateTime.now().minusYears(1)`을 기준 시각으로 사용한다.

신고의 유효성은 Repository 조회 조건에 따라 결정된다.

## 7.3 숨김 및 비공개 처리

- PRIVATE 상태의 신고는 집계에서 제외한다.
- 연관 식별자는 숨김 처리되지 않은 연결만 포함한다.
- 상세 조회는 저장된 마스킹 값을 반환한다.
- 식별자 비교가 실패하면 전체 값을 반환하지 않는다.

## 7.4 암호화 및 해시

식별자 해시에는 HMAC-SHA256을 사용한다.

별도의 `IdentifierEncryptionService`는 AES-256-GCM 암호화 및 복호화를 제공한다.

하지만 RP-01~03의 현재 조회·비교 서비스에서는 복호화 기능을 호출하지 않는다.

---

# 8. 테스트 항목

## 8.1 RP-01 테스트

- [ ] 은행 계좌 조회 성공
- [ ] 전화번호 조회 성공
- [ ] X 계정 조회 성공
- [ ] 은행명 별칭 정규화 확인
- [ ] 대소문자와 하이픈 정규화 확인
- [ ] 검색 결과 없음 → SAFE
- [ ] 피해 신고자 1명 → DANGER
- [ ] 의심 제보자 1명 → SAFE
- [ ] 의심 제보자 2명 → SUSPICION
- [ ] 동일 신고자 중복 집계 방지
- [ ] PRIVATE 신고 제외
- [ ] 1년 초과 신고 제외
- [ ] 잘못된 식별자 → 400

## 8.2 RP-02 테스트

- [ ] 존재하는 식별자 상세 조회 성공
- [ ] 존재하지 않는 ID → 404
- [ ] 마스킹된 식별자 반환
- [ ] 위험도 및 신고자 수 확인
- [ ] 서로 다른 신고자 2명 이상인 연관 식별자 포함
- [ ] 조건 미달 연관 식별자 제외
- [ ] hidden 식별자 제외
- [ ] 타임라인 최신순 정렬
- [ ] 중복 신고 ID 제거
- [ ] 최근 1년 유효 신고만 포함

## 8.3 RP-03 테스트

- [ ] 전체 계좌번호 일치 → matched=true
- [ ] 전화번호 일치 → matched=true
- [ ] X 계정 일치 → matched=true
- [ ] 식별자 종류 불일치 → matched=false
- [ ] 해시 불일치 → matched=false
- [ ] 불일치 시 value=null
- [ ] 일치 시 정규화 입력값 반환
- [ ] value 누락 → 400
- [ ] 잘못된 형식 → 400
- [ ] 존재하지 않는 identifierId → 404

## 8.4 요청 제한 테스트

- [ ] 동일 IP에서 30회까지 요청 허용
- [ ] 31번째 요청 → 429
- [ ] RP-01~03 요청 횟수 합산
- [ ] 1분 경과 후 요청 허용
- [ ] 서로 다른 IP의 카운터 구분
- [ ] 만료된 IP 카운터 정리

---

# 9. 최종 정리

| 기능 | 주요 역할 | 결과 |
|---|---|---|
| RP-01 | 식별자 신고 조회 및 위험도 계산 | LookupResponse |
| RP-02 | 신고 상세·연관 식별자·타임라인 조회 | LookupDetailResponse |
| RP-03 | 전체 식별자 값 비교 | LookupCompareResponse |

RP-01~03은 공개 조회 API로 로그인 없이 이용할 수 있다.

식별자 비교에는 HMAC-SHA256을 사용하고, 신고 리스트 위험도는 최근 1년간 유효한 신고자 수를 기준으로 계산한다.

모든 조회 API에는 IP별 1분 30회의 공통 요청 제한이 적용된다.

이 문서는 현재 LookupController, LookupService 및 관련 공통 구현 코드를 기준으로 작성되었다.
