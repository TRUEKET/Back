# RP-04 신고·제보 등록 API

JD 판독 결과를 기반으로 피해 신고(FRAUD) 또는 의심 제보(SUSPICION)를 등록하는 API이다.

사용자가 직접 신고 계좌나 판독 근거를 입력하는 방식이 아니라, 서버에 저장된 JD 판독 결과를 가져와 신고를 생성한다.

사용자가 송금 증빙 사진을 검증한 경우 RP-05에서 발급한 evidenceToken을 함께 제출해 최종 신고에 연결할 수 있다.

## 1. API 개요

| 항목 | 내용 |
|---|---|
| 기능 ID | RP-04 |
| Method | POST |
| Endpoint | `/reports` |
| Content-Type | application/json |
| 인증 | 로그인 필요 |
| CSRF | 필요 |
| 성공 코드 | 201 Created |
| Controller | ReportCreationController |
| Service | ReportCreationService |

### 연동 구조

```text
JD 판독
POST /analyze
    |
    | analysisId 반환
    v
RP-05 송금 증빙 검증 (선택)
POST /reports/evidence
    |
    | evidenceToken 반환
    v
RP-04 신고 등록
POST /reports
    |
    v
신고 DB 저장
    |
    +-- reports
    +-- identifiers
    +-- report_identifiers
    +-- evidence (증빙이 있는 경우)
```

RP-05를 이용하지 않는 경우에는 evidenceToken 없이 신고 등록을 요청할 수 있다.

단, 신고 등록 자체가 가능한지는 JD 판독 결과와 서버 검증 조건에 따라 결정된다.

---

## 2. 인증 및 사전 조건

신고 등록은 로그인한 사용자만 이용할 수 있다.

### 사전 조건

1. JD 판독이 정상적으로 완료되어야 한다.
2. 유효한 analysisId가 존재해야 한다.
3. 판독 결과를 생성한 세션과 신고 등록 세션이 연결되어야 한다.
4. 판독 결과의 소유자와 로그인한 사용자가 일치해야 한다.
5. 사용자의 계정 상태가 ACTIVE여야 한다.
6. 휴대폰 인증이 완료되어 있어야 한다.
7. JD 판독 결과에서 신고 등록이 허용되어야 한다.

### JD 판독 세션

RP-04는 다음 서비스를 사용한다.

```
analysisReportSourceService.load(
    request.analysisId(),
    session
);
```

요청으로 전달된 analysisId를 이용해 서버의 JD 판독 결과를 조회한다.

JD 판독 결과의 원문 식별자와 근거는 서버 내부에서만 사용한다.

클라이언트가 신고 대상 식별자나 판독 근거를 임의로 지정할 수 없다.

JD 판독 세션의 보관 및 만료 정책은 `docs/analyze-api.md`를 따른다.

현재 JD 판독 결과는 서버 메모리에 저장되므로 서버 재시작이나 세션 만료 후에는 이전 판독 결과를 사용할 수 없다.

---

## 3. 요청

### POST /reports

Content-Type: application/json

### 요청 Body

```json
{
  "analysisId": "89ee4f8f-c83c-4038-a2a9-403e155b20e4",
  "kind": "FRAUD",
  "sentDate": "2026-11-15",
  "referenceLinks": [],
  "evidenceToken": null
}
```

위 UUID는 요청 형식을 설명하기 위한 예시이다.

### 요청 필드

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| analysisId | UUID | O | JD 판독 결과 ID |
| kind | Enum | O | 신고 종류 |
| sentDate | LocalDate | X | 송금 날짜 |
| referenceLinks | List<String> | X | 참고 링크 목록 |
| evidenceToken | UUID | X | RP-05 증빙 검증 토큰 |

### 신고 종류

| 값 | 의미 |
|---|---|
| FRAUD | 피해 신고 |
| SUSPICION | 의심 제보 |

kind는 `ReportKind` Enum을 사용한다.

### FRAUD 요청 예시

```json
{
  "analysisId": "89ee4f8f-c83c-4038-a2a9-403e155b20e4",
  "kind": "FRAUD",
  "sentDate": "2026-11-15",
  "referenceLinks": [],
  "evidenceToken": null
}
```

### SUSPICION 요청 예시

```json
{
  "analysisId": "89ee4f8f-c83c-4038-a2a9-403e155b20e4",
  "kind": "SUSPICION",
  "sentDate": null,
  "referenceLinks": [],
  "evidenceToken": null
}
```

### RP-05 증빙을 연결하는 경우

```json
{
  "analysisId": "89ee4f8f-c83c-4038-a2a9-403e155b20e4",
  "kind": "FRAUD",
  "sentDate": "2026-11-15",
  "referenceLinks": [],
  "evidenceToken": "11111111-2222-4333-8444-555555555555"
}
```

evidenceToken은 실제 RP-05 응답에서 받은 값을 사용한다.

임의로 생성한 UUID는 사용할 수 없다.

---

## 4. 신고 가능 조건

신고 가능 여부는 서버에 저장된 JD 판독 결과를 기준으로 확인한다.

### 4.1 공통 조건

모든 신고는 다음 조건을 충족해야 한다.

- JD 판독 결과가 존재할 것
- 판독 세션 소유자와 로그인 사용자가 일치할 것
- JD 판독 결과에 식별자 정보가 존재할 것
- JD 판독 근거 데이터가 존재할 것
- `showReportButton=true`일 것
- 최소 1개 이상의 유효한 식별자가 존재할 것

### 4.2 FRAUD

FRAUD는 피해 신고를 의미한다.

서버는 다음 조건을 검사한다.

```
source.showReportButton()
```

true일 때 신고 등록을 허용한다.

단, 동일한 휴대폰 인증 사용자가 동일 계좌에 대해 기존 FRAUD 신고를 등록한 경우 중복 신고로 차단한다.

### 4.3 SUSPICION

SUSPICION은 의심 제보를 의미한다.

다음 두 조건을 모두 충족해야 한다.

```
source.showReportButton()
source.showSuspicionReport()
```

즉, 신고 버튼이 표시되더라도 의심 제보가 항상 허용되는 것은 아니다.

대화 자체에 신고 가능한 의심 근거가 있어야 한다.

기존 신고 집계만으로 위험 단계가 상승한 경우에는 의심 제보가 허용되지 않을 수 있다.

### 4.4 중복 신고 검사

FRAUD에 대해서는 다음 조건을 기준으로 중복 여부를 확인한다.

- 동일한 휴대폰 해시
- 동일한 계좌 식별자 해시
- 신고 종류 FRAUD

중복 신고가 존재하면 `DUPLICATE_REPORT` 예외를 발생시킨다.

SUSPICION에 대해서는 위 FRAUD 전용 중복 검사 로직을 실행하지 않는다.

다른 중복 제약이 존재하는지는 별도 확인이 필요하다.

---

## 5. JD 판독 결과 처리

### 5.1 식별자 추출

RP-04는 JD 판독 결과에서 다음 식별자를 가져온다.

| JD 식별자 | 저장 유형 |
|---|---|
| accounts | ACCOUNT |
| phones | PHONE |
| xAccounts | SNS |

JD가 추출한 links는 현재 ReportCreationService에서 신고 식별자로 등록하지 않는다.

### 5.2 식별자 정규화

모든 식별자는 공통 IdentifierNormalizer를 이용해 정규화한다.

```
IdentifierNormalizer.normalize(raw)
```

예를 들어 계좌번호는 은행명과 전체 번호가 있어야 한다.

정규화 결과의 유형이 기대한 유형과 다르면 요청을 거절한다.

정규화된 식별자는 종류와 해시를 기준으로 중복 제거한다.

### 5.3 식별자 저장

서버는 다음 순서로 처리한다.

1. 식별자 정규화
2. 식별자 해시 생성
3. 기존 identifiers 조회
4. 존재하지 않으면 신규 생성
5. 신고와 식별자 연결

식별자는 다음 정보로 저장된다.

- type
- value_hash
- value_masked
- value_enc

전체 식별자 원문은 공개 응답에 반환하지 않는다.

식별자 저장에는 IdentifierHashService, IdentifierMasker,
IdentifierEncryptionService를 사용한다.

### 5.4 JD 근거 저장

JD에서 채택한 근거 문장만 가져온다.

```
for (var evidence : source.evidence()) {
    evidenceQuotesJson.add(evidence.quote());
}
```

따라서 신고 DB에는 JD가 전달한 가려진 근거 문장을 저장한다.

전체 대화 원문이나 캡처 이미지를 RP-04에서 직접 복사하지 않는다.

### 5.5 거래 유형

거래 유형은 요청 Body에서 받지 않는다.

JD 판독 결과의 tradeType을 사용한다.

지원하는 거래 유형은 다음과 같다.

| 값 | 의미 |
|---|---|
| TRANSFER | 양도 |
| AOM | 아옮 |
| PROXY | 대리티켓팅 |
| CANCEL | 취켓 대행 |
| OTHER | 기타 |

---

## 6. 신고 저장 처리 흐름

ReportCreationService는 하나의 DB 트랜잭션으로 신고를 생성한다.

```text
POST /reports
    |
    v
JD 판독 세션 조회
    |
    v
판독 결과 소유권 확인
    |
    v
FRAUD / SUSPICION 신고 가능 여부 확인
    |
    v
사용자 ACTIVE 및 휴대폰 인증 확인
    |
    v
JD 식별자 정규화
    |
    v
식별자 중복 제거
    |
    v
FRAUD 중복 신고 검사
    |
    v
JD 근거·참고 링크 준비
    |
    v
reports 생성
    |
    v
identifiers 조회 또는 생성
    |
    v
report_identifiers 연결
    |
    v
evidenceToken 존재 여부 확인
    |
    +-- 없음 → 신고 생성 완료
    |
    +-- 있음
          |
          v
       임시 증빙 토큰 검증
          |
          v
       최종 Storage 경로 생성
          |
          v
       임시 증빙 파일 복사
          |
          v
       evidence 생성
          |
          v
       트랜잭션 완료
```

---

## 7. RP-05 송금 증빙 연결

RP-05는 별도의 API이다.

```http
POST /reports/evidence
```

RP-05에서 송금 내역 검증이 성공하면 evidenceToken을 반환한다.

RP-04는 전달받은 evidenceToken을 사용해 최종 증빙을 연결한다.

### 7.1 증빙 토큰 검증

RP-04에서는 다음 조건을 검증한다.

- evidenceToken 유효 여부
- 로그인 사용자 ID 일치 여부
- analysisId 일치 여부
- JD 판독 결과의 정규화 계좌와 증빙 계좌 일치 여부

검증에는 다음 서비스를 사용한다.

```
pendingReportEvidenceService.consume(
    request.evidenceToken(),
    authenticatedUserId,
    request.analysisId(),
    normalizedAccounts
);
```

### 7.2 Storage 저장

증빙은 Supabase Storage에 저장한다.

기본 버킷:

```text
report-evidence
```

임시 증빙 경로:

```text
pending/{userId}/{uuid}.png
```

최종 증빙 경로:

```text
reports/{userId}/{uuid}.png
```

임시 증빙 파일을 최종 경로로 복사한다.

### 7.3 트랜잭션 완료 후 정리

신고 저장 성공:

```text
DB COMMIT
    ↓
임시 증빙 파일 삭제
```

신고 저장 실패:

```text
DB ROLLBACK
    ↓
최종 복사된 증빙 파일 삭제
```

Storage 정리 중 오류가 발생하면 로그에 기록한다.

### 7.4 증빙 DB 저장

최종 증빙은 Evidence 엔티티로 저장한다.

주요 저장 정보:

| 항목 | 내용 |
|---|---|
| report | 연결된 신고 |
| kind | NUMBER_FRAGMENT |
| storageKey | 최종 Storage 경로 |
| accountHash | 증빙 계좌 해시 |
| expiresAt | 증빙 보관 만료 시각 |

### 7.5 증빙 보관 기간

ReportEvidenceRetentionPolicy를 사용한다.

```
return createdAt.plusYears(1);
```

최종 신고 증빙의 만료 시각은 생성 시각으로부터 1년이다.

단, 실제 자동 삭제 스케줄러 동작은 별도로 확인해야 한다.

---

## 8. 성공 응답

HTTP 201 Created

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "reportId": 365,
    "kind": "FRAUD",
    "status": "PUBLISHED",
    "identifierIds": [308]
  }
}
```

위 reportId와 identifierIds는 응답 형식을 설명하기 위한 예시이다.

### 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| code | String | SUCCESS |
| message | String | 요청 성공 안내 |
| data.reportId | Long | 생성된 신고 ID |
| data.kind | Enum | 신고 종류 |
| data.status | Enum | 신고 상태 |
| data.identifierIds | List<Long> | 연결된 식별자 ID 목록 |

응답에는 전체 계좌번호, 전화번호, SNS 계정 원문이 포함되지 않는다.

신고의 실제 최초 상태는 Report.create() 구현을 따른다.

---

## 9. 오류 처리

### 9.1 요청 검증 오류

다음 상황에서는 신고 등록이 거부될 수 있다.

- analysisId 누락
- kind 누락
- 잘못된 Enum 값
- 유효하지 않은 판독 세션
- 판독 세션 소유자 불일치
- 신고가 허용되지 않는 JD 판독 결과
- 유효한 식별자가 없음
- 식별자 정규화 실패
- 계정 비활성화
- 휴대폰 인증 미완료
- 동일 계좌 FRAUD 중복 신고
- 유효하지 않은 evidenceToken
- Storage 처리 실패

### 9.2 오류 코드

ReportCreationService에서 사용하는 BusinessException 종류:

| ErrorCode | 발생 조건 |
|---|---|
| UNAUTHORIZED | 판독 결과 소유자 불일치 또는 사용자 없음 |
| FORBIDDEN | ACTIVE 계정 또는 휴대폰 인증 조건 미충족 |
| INVALID_REQUEST | 유효한 식별자 없음 또는 정규화 실패 |
| REPORT_NOT_ALLOWED | JD 결과상 신고 등록 불가 |
| DUPLICATE_REPORT | 동일 휴대폰 사용자의 동일 계좌 FRAUD 중복 신고 |

각 ErrorCode의 정확한 HTTP 상태와 한국어 메시지는
`global.exception.ErrorCode` 정의를 따른다.

### 9.3 공통 오류 응답

BusinessException은 GlobalExceptionHandler에서 처리한다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "잘못된 요청 값입니다.",
  "data": null
}
```

위 JSON은 공통 응답 구조 예시이며, 실제 메시지는 해당 ErrorCode 정의에 따라 달라진다.

ApiException은 별도의 ApiExceptionHandler에서 처리한다.

```json
{
  "message": "요청을 처리하지 못했습니다."
}
```

ApiException과 BusinessException은 서로 다른 응답 형식을 사용하므로 클라이언트는 두 형식을 구분해야 한다.

---

## 10. 데이터 저장 정책

### reports

신고 원본을 저장한다.

주요 데이터:

- user_id
- reporter_hash
- kind
- status
- trade_type
- evidence_quotes
- reference_links
- sent_date
- created_at

### identifiers

정규화된 신고 대상 식별자를 저장한다.

동일한 식별자가 존재하면 재사용한다.

### report_identifiers

신고와 식별자의 연결을 저장한다.

### evidence

RP-05에서 검증된 증빙을 연결한 경우 저장한다.

증빙이 없는 신고라면 해당 단계에서 Evidence를 생성하지 않는다.

---

## 11. 개인정보 보호

RP-04는 JD 판독 결과를 서버 내부에서 가져온다.

- 클라이언트가 식별자 원문을 직접 제출하지 않는다.
- 원문 식별자는 정규화 후 해시·마스킹·암호화 처리한다.
- 신고 근거에는 JD가 전달한 가려진 문장을 사용한다.
- 판독 캡처 원본을 RP-04에서 직접 저장하지 않는다.
- 최종 증빙은 RP-05에서 검증한 계좌번호 조각을 저장한다.
- 신고 성공 응답에는 식별자 원문을 반환하지 않는다.

JD의 개인정보 탐지 및 마스킹 누락 가능성은 별도 검토가 필요하다.

---

## 12. 실제 연동 검증 현황

기준일: 2026-11-15

### 확인된 구현

| 항목 | 상태 |
|---|---|
| JD 판독 세션 조회 | 구현 확인 |
| 판독 결과 소유권 검증 | 구현 확인 |
| FRAUD·SUSPICION 신고 조건 | 구현 확인 |
| 휴대폰 인증 사용자 검증 | 구현 확인 |
| 식별자 정규화 및 중복 제거 | 구현 확인 |
| FRAUD 중복 신고 차단 | 구현 확인 |
| JD 근거 저장 | 구현 확인 |
| 신고 및 식별자 DB 연결 | 구현 확인 |
| RP-05 증빙 토큰 연결 | 구현 확인 |
| Supabase 최종 증빙 저장 | 구현 확인 |
| 트랜잭션별 Storage 정리 | 구현 확인 |

### JD → RP-04 → RP-05 연동

JD의 POST /analyze 요청에서 다음 결과를 확인했다.

```text
readable = true
detectedType = TRANSFER
finalStage = DANGER
showReportButton = true
```

하지만 RP-05 송금 사진 검증에서는 다음 오류가 발생한 이력이 있다.

```text
RP-05 실패: JD 판독 결과에 신고 계좌 없음
```

따라서 JD 판독 성공만으로 RP-04 및 RP-05 전체 연동 성공을 확정할 수 없다.

현재 확인된 것은 각 서비스의 구현이며,
최신 코드 기준 전체 연결 성공 여부는 별도 테스트가 필요하다.

---

## 13. 추가 점검 사항

1. ErrorCode의 정확한 HTTP 상태와 메시지 확인
2. JD 내부 계좌와 RP-05 제출 계좌의 정규화 일치 여부 검증
3. FRAUD 중복 신고의 실제 DB 제약 및 예외 응답 확인
4. evidenceToken 만료 및 재사용 방지 정책 확인
5. 증빙 보관 기간 경과 시 자동 삭제 스케줄러 확인
6. JD → RP-05 → RP-04 실제 통합 테스트 완료
7. 개인정보 마스킹 결과 검증

RP-04는 JD 판독 결과를 기반으로 신고를 생성하며,
RP-05에서 검증한 송금 증빙을 선택적으로 연결하는 구조이다.