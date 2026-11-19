# RP-06·07 내 신고 관리 API

## 본인 신고 전체 식별자 표시 (#84)

현재 목록과 상세 모두 `identifiers`를 반환한다. 로그인 세션의 사용자 ID로 신고 소유권을 확인한 뒤에만 복호화한다. 요청 쿼리나 본문의 userId로 조회 대상을 바꾸지 않는다. 타인 상세는 404, 비로그인은 401이며 성공 응답은 `Cache-Control: no-store`다.

```json
{"identifiers":[
  {"identifierId":1,"type":"ACCOUNT","value":"신한 11022123456","maskedValue":"신한 110-22-******"},
  {"identifierId":2,"type":"PHONE","value":"010-1234-5678","maskedValue":"010-****-5678"},
  {"identifierId":3,"type":"SNS","value":"@seller_test","maskedValue":"@seller_t***"}
]}
```

**내 신고 화면에서는 `identifiers[].value`를 사용한다.** `maskedValue`는 기존 클라이언트 호환용으로 그대로 가린 값이다. 목록은 `data.content[].identifiers`, 상세는 `data.identifiers`다. 이 문서 아래의 이전 응답 예시에 value가 없더라도 현재 계약은 이 절을 따른다.

계좌는 암호화된 정규값을 복호화해 은행명+전체 숫자로 표시한다. 저장 시 제거된 원본 하이픈을 추측하지 않는다. 전화는 전체 숫자를 하이픈으로 묶고 SNS는 @계정을 반환한다. 숨김 연결은 기존처럼 제외한다. 복호화할 수 없는 과거 데이터는 원문을 추측하거나 가린 값을 원문처럼 반환하지 않고 503으로 실패한다. 데이터·키 정합성 확인이 필요하다.

판독 결과·공개 조회는 기존 마스킹을 유지한다. 이 변경은 식별자 텍스트에만 해당하며 원본 캡처·송금 증빙의 가림 정책이나 사진 저장 범위를 바꾸지 않는다. DB 스키마 변경은 필요 없다.



로그인한 사용자가 본인이 등록한 신고·제보 목록과 상세 정보를 조회하고, 기존 신고를 삭제하는 API이다.

RP-06은 내 신고 목록 및 상세 조회를 담당하며, RP-07은 내 신고 삭제를 담당한다.

이의제기가 연결된 신고는 RP-08 관련 알림 정보도 함께 제공한다.

---

## 1. API 개요

| 기능 ID | Method | Endpoint | 설명 | 인증 |
|---|---|---|---|---|
| RP-06-01 | GET | `/me/reports` | 내 신고 목록 조회 | 필요 |
| RP-06-02 | GET | `/me/reports/{reportId}` | 내 신고 상세 조회 | 필요 |
| RP-07 | DELETE | `/me/reports/{reportId}` | 내 신고 삭제 | 필요 |

### 구현 클래스

| 구분 | 클래스 |
|---|---|
| Controller | `ReportController` |
| Service | `ReportService` |
| 목록 DTO | `ReportListResponse` |
| 상세 DTO | `ReportDetailResponse` |
| 식별자 DTO | `ReportIdentifierResponse` |
| 이의제기 알림 DTO | `DisputeNoticeResponse` |

### 공통 사항

- 로그인한 사용자만 이용할 수 있다.
- 서버는 `Authentication.getName()`에서 사용자 ID를 가져온다.
- 모든 성공 응답은 `ApiResponse<T>` 형식을 사용한다.
- 본인이 작성하지 않은 신고의 상세 조회와 삭제는 허용하지 않는다.

### 공통 성공 응답

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {}
}
```

`data`는 API별 응답 DTO에 따라 달라진다.

---

# 2. RP-06-01 내 신고 목록 조회

## 2.1 API 정보

| 항목 | 내용 |
|---|---|
| Method | GET |
| Endpoint | `/me/reports` |
| 인증 | 로그인 필요 |
| 요청 Body | 없음 |
| 성공 코드 | 200 OK |
| 반환 타입 | `ApiResponse<Page<ReportListResponse>>` |

로그인 사용자가 작성한 신고·제보 목록을 페이지 단위로 조회한다.

신고 목록은 생성 일시를 기준으로 최신순으로 조회한다.

각 신고에 연결된 RP-08 이의제기 알림 정보도 함께 반환한다.

## 2.2 요청

```http
GET /me/reports?page=0&size=20
```

### 페이지네이션

| 항목 | 타입 | 설명 |
|---|---|---|
| page | Integer | 페이지 번호, 0부터 시작 |
| size | Integer | 페이지당 조회할 신고 수 |
| sort | String[] | Spring Data Pageable 정렬 옵션 |

Controller에서는 Spring Data의 `Pageable`을 사용하고, Swagger 문서에는 `@ParameterObject`를 적용한다.

```
public ResponseEntity<ApiResponse<Page<ReportListResponse>>> getMyReports(
        Authentication authentication,
        @ParameterObject Pageable pageable
)
```

Swagger UI에서는 `page`, `size`, `sort`가 개별 쿼리 파라미터로 표시되도록 문서화한다.
실제 요청은 `GET /me/reports?page=0&size=20`처럼 전송한다.
정렬 옵션을 보내더라도 서비스의 `findAllByUser_IdOrderByCreatedAtDesc` 메서드는 생성 일시 내림차순을 명시한다.

## 2.3 성공 응답

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "content": [
      {
        "reportId": 365,
        "kind": "FRAUD",
        "status": "PUBLISHED",
        "tradeType": "TRANSFER",
        "createdAt": "2026-11-15T20:00:00+09:00",
        "disputes": []
      },
      {
        "reportId": 364,
        "kind": "SUSPICION",
        "status": "PUBLISHED",
        "tradeType": "AOM",
        "createdAt": "2026-11-14T18:30:00+09:00",
        "disputes": []
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "offset": 0,
      "paged": true,
      "unpaged": false,
      "sort": {
        "empty": true,
        "sorted": false,
        "unsorted": true
      }
    },
    "totalElements": 2,
    "totalPages": 1,
    "last": true,
    "first": true,
    "size": 20,
    "number": 0,
    "numberOfElements": 2,
    "empty": false,
    "sort": {
      "empty": true,
      "sorted": false,
      "unsorted": true
    }
  }
}
```

위 응답은 Swagger에 등록된 예시이며, 실제 DB 조회 결과는 사용자와 페이지 조건에 따라 달라진다.

### 목록 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| reportId | Long | 신고 ID |
| kind | ReportKind | 신고 종류 |
| status | ReportStatus | 신고 상태 |
| tradeType | TradeType | 거래 유형 |
| createdAt | OffsetDateTime | 신고 등록 일시 |
| disputes | List | 연결된 RP-08 이의제기 알림 목록 |

### 페이지네이션 응답

| 필드 | 설명 |
|---|---|
| content | 현재 페이지의 신고 목록 |
| totalElements | 전체 신고 수 |
| totalPages | 전체 페이지 수 |
| number | 현재 페이지 번호 |
| size | 페이지 크기 |
| numberOfElements | 현재 페이지 항목 수 |
| first | 첫 페이지 여부 |
| last | 마지막 페이지 여부 |
| empty | 현재 페이지가 비어 있는지 |

실제 `Page` 응답 구조는 Spring Data 및 Jackson 설정에 따라 달라질 수 있다.

## 2.4 처리 흐름

```text
GET /me/reports
    |
    v
로그인 사용자 ID 확인
    |
    v
사용자의 신고 목록 조회
    |
    v
생성 일시 기준 최신순 정렬
    |
    v
페이지에 포함된 신고 ID 수집
    |
    v
해당 신고들의 이의제기 항목 조회
    |
    v
신고별 이의제기 알림 연결
    |
    v
Page<ReportListResponse> 반환
```

### 주요 서비스 코드

```java
Page<Report> reports = reportRepository
        .findAllByUser_IdOrderByCreatedAtDesc(
                userId,
                pageable
        );
```

이의제기 알림은 `DisputeItemRepository`에서 조회하여 각 신고에 연결한다.

이의제기가 없는 신고는 `disputes`에 빈 배열을 반환한다.

---

# 3. RP-06-02 내 신고 상세 조회

## 3.1 API 정보

| 항목 | 내용 |
|---|---|
| Method | GET |
| Endpoint | `/me/reports/{reportId}` |
| 인증 | 로그인 필요 |
| 성공 코드 | 200 OK |
| 반환 타입 | `ApiResponse<ReportDetailResponse>` |

로그인 사용자가 작성한 특정 신고의 상세 정보를 조회한다.

신고의 기본 정보, JD 판독 근거, 참고 링크, 신고 대상 식별자, 이의제기 정보를 반환한다.

## 3.2 요청

```http
GET /me/reports/365
```

### Path Parameter

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| reportId | Long | O | 상세 조회할 신고 ID |

`365`는 예시 신고 ID이다.

실제 호출 시에는 DB에 존재하는 본인 신고 ID를 사용해야 한다.

## 3.3 성공 응답

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": {
    "reportId": 365,
    "kind": "FRAUD",
    "status": "PUBLISHED",
    "tradeType": "TRANSFER",
    "evidenceQuotes": [
      "지금 바로 입금해야 해요."
    ],
    "referenceLinks": [
      "https://example.com/reference"
    ],
    "sentDate": "2026-11-15",
    "createdAt": "2026-11-15T20:00:00+09:00",
    "identifiers": [
      {
        "identifierId": 308,
        "type": "ACCOUNT",
        "maskedValue": "신한 111-111-******"
      }
    ],
    "disputes": []
  }
}
```

위 JSON은 Swagger에 등록된 설명용 예시이다.

### 상세 응답 필드

| 필드 | 타입 | 설명 |
|---|---|---|
| reportId | Long | 신고 ID |
| kind | ReportKind | 신고 종류 |
| status | ReportStatus | 신고 상태 |
| tradeType | TradeType | 거래 유형 |
| evidenceQuotes | Object | JD 판독 근거 문장 |
| referenceLinks | Object | 참고 링크 |
| sentDate | LocalDate | 송금 날짜 |
| createdAt | OffsetDateTime | 신고 등록 일시 |
| identifiers | List | 신고에 연결된 식별자 목록 |
| disputes | List | RP-08 이의제기 알림 목록 |

### 신고 종류

| 값 | 의미 |
|---|---|
| FRAUD | 피해 신고 |
| SUSPICION | 의심 제보 |

### 신고 상태

| 값 | 의미 |
|---|---|
| PUBLISHED | 게시 중 |
| RESPONDING | 이의제기 응답 대기 |
| REVIEWING | 이의제기 검토 중 |
| PRIVATE | 비공개 |

### 거래 유형

| 값 | 의미 |
|---|---|
| TRANSFER | 양도 |
| AOM | 아옮 |
| PROXY | 대리티켓팅 |
| CANCEL | 취켓 대행 |
| OTHER | 기타 |

## 3.4 식별자 응답

신고 대상 식별자는 다음 구조로 반환한다.

```json
{
  "identifierId": 308,
  "type": "ACCOUNT",
  "maskedValue": "신한 111-111-******"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| identifierId | Long | 식별자 ID |
| type | String | 식별자 종류 |
| maskedValue | String | 마스킹된 식별자 |

### 식별자 종류

- ACCOUNT: 은행 계좌
- PHONE: 전화번호
- SNS: SNS 계정

### 개인정보 보호

서버는 `ReportIdentifier` 연결 정보 중 `hidden=false`인 식별자만 반환한다.

```
reportIdentifiers.stream()
        .filter(ri -> !ri.isHidden())
```

식별자 응답 생성 시 다음 필드를 사용한다.

```
identifier.getValueMasked()
```

따라서 전체 계좌번호 등의 원문은 상세 조회 응답에 포함하지 않는다.

## 3.5 JD 근거 및 참고 링크

`evidenceQuotes`에는 신고 생성 시 저장한 JD 판독 근거가 포함된다.

`referenceLinks`에는 신고 등록 당시 입력한 참고 링크가 포함된다.

서버는 DB의 JsonNode를 JSON 값으로 변환하여 반환한다.

JSON 배열인 경우 배열로 반환하며, null인 경우 빈 배열로 처리한다.

## 3.6 이의제기 알림 연동

RP-08 이의제기가 연결된 경우 다음 정보를 `disputes`에 포함한다.

`DisputeNoticeResponse`의 필드:

- disputeId
- reason
- status
- deadline
- canRespond
- canRepublish

이 정보는 신고 작성자가 이의제기에 응답하거나 비공개 신고를 재게시할 수 있는지 판단하는 데 사용한다.

실제 이의제기 유지·삭제 및 재게시 요청은 RP-08 API에서 처리한다.

## 3.7 처리 흐름

```text
GET /me/reports/{reportId}
    |
    v
로그인 사용자 ID 확인
    |
    v
신고 ID + 사용자 ID로 조회
    |
    +-- 조회 실패 → REPORT_NOT_FOUND
    |
    v
신고에 연결된 식별자 조회
    |
    v
숨김 처리된 식별자 제외
    |
    v
식별자에 저장된 마스킹 값 사용
    |
    v
이의제기 항목 조회
    |
    v
JD 근거 및 참고 링크 변환
    |
    v
ReportDetailResponse 반환
```

### 소유권 확인

```java
Report report = reportRepository
        .findByIdAndUser_Id(reportId, userId)
        .orElseThrow(() ->
                new BusinessException(
                        ErrorCode.REPORT_NOT_FOUND
                )
        );
```

본인 신고가 아니거나 신고가 존재하지 않으면 동일하게 404를 반환한다.

다른 사용자의 신고 존재 여부를 상세하게 노출하지 않는다.

---

# 4. RP-07 내 신고 삭제

## 4.1 API 정보

| 항목 | 내용 |
|---|---|
| Method | DELETE |
| Endpoint | `/me/reports/{reportId}` |
| 인증 | 로그인 필요 |
| 성공 코드 | 200 OK |
| 반환 타입 | `ApiResponse<Void>` |
| 처리 방식 | DB 트랜잭션 및 Supabase Storage 외부 삭제 요청 |

로그인 사용자가 본인이 작성한 신고 또는 의심 제보를 삭제한다.

신고와 연결된 DB 데이터 및 증빙 이미지를 삭제하고, 이의제기 관련 삭제 이력을 보존한다.

**구현 적용 조건:** 아래 Storage 삭제 과정은 `ReportService.deleteMyReport()`에 `SupabaseReportEvidenceStore.deleteReport()` 호출을 추가한 수정본이 실제로 반영된 경우에만 해당한다. 이전 `ReportService` 코드에는 이 호출이 없었다.

## 4.2 요청

```http
DELETE /me/reports/365
```

### Path Parameter

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| reportId | Long | O | 삭제할 신고 ID |

요청 Body는 사용하지 않는다.

## 4.3 성공 응답

HTTP 200 OK

```json
{
  "code": "SUCCESS",
  "message": "요청이 성공했습니다.",
  "data": null
}
```

삭제 성공 시 별도의 DTO를 반환하지 않는다.

HTTP 204가 아니라 HTTP 200을 사용한다.

## 4.4 삭제 처리 흐름

```text
DELETE /me/reports/{reportId}
    |
    v
로그인 사용자 확인
    |
    v
신고 ID + 사용자 ID로 조회
    |
    +-- 조회 실패 → REPORT_NOT_FOUND (404)
    |
    v
증빙 목록 및 storageKey 조회
    |
    v
이의제기 연결 항목 조회
    |
    v
이의제기 삭제 이력 보존
    |
    v
Supabase Storage 증빙 이미지 삭제 요청
    |
    +-- 실패 → HTTP 503 Service Unavailable
    |
    v
이의제기 연결 데이터 삭제
    |
    v
증빙 DB 데이터 삭제
    |
    v
신고-식별자 연결 데이터 삭제
    |
    v
신고 원본 삭제
    |
    v
DB 커밋 후 HTTP 200 응답
```

## 4.5 실제 삭제 순서

`ReportService.deleteMyReport()`는 다음 순서로 처리한다.

1. 신고 소유권 확인
2. `EvidenceRepository`에서 증빙 목록 및 Storage 경로 조회
3. 이의제기 연결 항목 조회
4. `dispute_deleted_records`에 삭제 이력 보존
5. `SupabaseReportEvidenceStore.deleteReport()`로 증빙 이미지 삭제 요청
6. `dispute_items` 연결 데이터 삭제
7. `evidence` DB 데이터 삭제
8. `report_identifiers` 연결 데이터 삭제
9. `reports` 원본 삭제

### Supabase Storage 증빙 삭제

신고에 연결된 `Evidence` 엔티티의 `storageKey`를 조회한다.

```
List<Evidence> evidences = evidenceRepository
        .findAllByReport_IdOrderByCreatedAtAsc(reportId);

for (Evidence evidence : evidences) {
    supabaseReportEvidenceStore.deleteReport(
            evidence.getStorageKey()
    );
}
```


`SupabaseReportEvidenceStore.deleteReport()`에서 Storage 삭제에 실패하면
`ApiException(HttpStatus.SERVICE_UNAVAILABLE, ...)`이 발생한다.

`ApiExceptionHandler`에 `@Order(Ordered.HIGHEST_PRECEDENCE)`가
설정되어 있으므로 해당 예외는 HTTP 503 응답으로 처리하도록 구성되어 있다.

Storage 삭제와 DB 트랜잭션은 원자적으로 처리되지 않으므로,
Storage 삭제 성공 후 DB 작업에 실패하는 경우 부분 삭제가 발생할 수 있다.


## 4.6 삭제 정책

- 신고 원본은 DB에서 삭제한다.
- 삭제된 신고는 내 신고 조회 결과에서 제외된다.
- 삭제된 신고의 연결 관계가 제거되어 신고 리스트 집계에서 제외된다.
- 식별자 자체는 삭제하지 않는다.
- 이의제기 삭제 이력은 별도 테이블에 보존한다.
- 신고 증빙 DB 레코드는 삭제한다.
- 수정된 `ReportService`가 적용된 경우 신고 증빙 이미지의 Storage 파일 삭제를 요청한다.
- Storage 삭제와 DB 삭제는 단일 원자적 트랜잭션이 아니므로 일부 파일 삭제 후 실패할 가능성이 있다.


---

# 5. 오류 응답

## 5.1 RP-06 목록 조회

| HTTP | 설명 |
|---|---|
| 200 | 목록 조회 성공 |
| 400 | 잘못된 페이지네이션 요청 |
| 401 | 로그인 필요 |

### 400 Bad Request

```json
{
  "code": "INVALID_REQUEST",
  "message": "잘못된 요청입니다.",
  "data": null
}
```

### 401 Unauthorized

```json
{
  "code": "UNAUTHORIZED",
  "message": "인증이 필요합니다.",
  "data": null
}
```

## 5.2 RP-06 상세 조회

| HTTP | 설명 |
|---|---|
| 200 | 상세 조회 성공 |
| 401 | 로그인 필요 |
| 404 | 신고가 존재하지 않거나 본인 신고가 아님 |

### 404 Not Found

```json
{
  "code": "REPORT_NOT_FOUND",
  "message": "신고 기록을 찾을 수 없습니다.",
  "data": null
}
```

## 5.3 RP-07 신고 삭제

| HTTP | 설명 |
|---|---|
| 200 | 삭제 성공 |
| 401 | 로그인 필요 |
| 404 | 신고가 존재하지 않거나 본인 신고가 아님 |
| 500 | 서버 내부 오류 |
| 503 | Supabase Storage 증빙 파일 삭제 실패 |

### 404 Not Found

```json
{
  "code": "REPORT_NOT_FOUND",
  "message": "신고 기록을 찾을 수 없습니다.",
  "data": null
}
```

### 500 Internal Server Error

```json
{
  "code": "INTERNAL_SERVER_ERROR",
  "message": "서버 내부 오류가 발생했습니다.",
  "data": null
}
```



### 503 Service Unavailable

Supabase Storage에서 신고 증빙 파일 삭제에 실패하면
`SupabaseReportEvidenceStore.deleteReport()`에서
`ApiException(HttpStatus.SERVICE_UNAVAILABLE, ...)`이 발생한다.

`ApiExceptionHandler`에는
`@Order(Ordered.HIGHEST_PRECEDENCE)`가 적용되어 있으며,
해당 예외를 HTTP 503으로 반환하도록 구성되어 있다.

```json
{
  "message": "신고 증빙 사진을 저장하지 못했어요. 잠시 후 다시 시도해 주세요."
}
```

현재 Storage 서비스는 파일 삭제 실패에도 공통 저장 오류 메시지를 사용한다.

실제 외부 Storage 장애 상황에서 HTTP 503이 반환되는지와
DB 트랜잭션이 롤백되는지는 통합 테스트로 최종 확인해야 한다.


---

# 6. 데이터 및 개인정보 처리

## 6.1 조회 대상 데이터

RP-06에서 사용하는 주요 데이터:

| 테이블 | 용도 |
|---|---|
| reports | 신고 기본 정보 |
| report_identifiers | 신고와 식별자의 연결 |
| identifiers | 마스킹된 식별자 정보 |
| dispute_items | 이의제기 알림 정보 |

## 6.2 삭제 대상 데이터

RP-07에서 사용하는 주요 데이터:

| 테이블 | 처리 |
|---|---|
| reports | 신고 원본 삭제 |
| report_identifiers | 연결 데이터 삭제 |
| evidence | 증빙 DB 데이터 삭제 |
| dispute_items | 연결 데이터 삭제 |
| dispute_deleted_records | 삭제 이력 보존 |
| Supabase Storage `report-evidence` | 연결된 신고 증빙 PNG 삭제 요청 (수정 Service 적용 시) |

## 6.3 개인정보 보호

- 본인 신고만 조회하거나 삭제할 수 있다.
- 상세 조회 시 숨김 식별자는 반환하지 않는다.
- 식별자 원문 대신 마스킹된 값을 반환한다.
- 다른 사람의 신고 접근 시 404를 반환한다.
- 삭제 후에도 이의제기 처리를 위한 최소 삭제 이력은 별도 보존한다.

---

# 7. Swagger UI 확인 결과

확인 기준: 2026-11-15

## RP-06 목록 조회

| 확인 항목 | 결과 |
|---|---|
| GET /me/reports 표시 | 정상 |
| 페이지네이션 파라미터 표시 | 정상 |
| 신고 목록 응답 예시 | 정상 |
| RP-08 disputes 필드 표시 | 정상 |
| 400 오류 예시 | 정상 |
| 401 오류 예시 | 정상 |

## RP-06 상세 조회

| 확인 항목 | 결과 |
|---|---|
| GET /me/reports/{reportId} 표시 | 정상 |
| reportId 경로 파라미터 표시 | 정상 |
| 신고 상세 응답 예시 | 정상 |
| 식별자 마스킹 필드 표시 | 정상 |
| 이의제기 알림 필드 표시 | 정상 |
| 401 오류 예시 | 정상 |
| 404 오류 예시 | 정상 |

## RP-07 신고 삭제

| 확인 항목 | 결과 |
|---|---|
| DELETE /me/reports/{reportId} 표시 | 정상 |
| reportId 경로 파라미터 표시 | 정상 |
| HTTP 200 삭제 응답 예시 | 정상 |
| 401 오류 예시 | 정상 |
| 404 오류 예시 | 정상 |
| 500 오류 예시 | 정상 |
| 503 오류 예시 | 첨부된 Swagger 출력에서 표시 확인 |

위 결과는 Swagger UI에서 명세가 정상적으로 표시되는 것을 확인한 것이다. 503의 실제 반환 여부까지 검증한 것은 아니다.

실제 서버 호출 및 DB 변경에 대한 통합 테스트 완료를 의미하지는 않는다.

---

# 8. 추가 점검 사항

1. 로그인 세션에서 RP-06 목록 조회 정상 동작 확인
2. 다른 사용자의 신고 상세 조회 차단 확인
3. 삭제 후 목록 및 상세 조회 결과 확인
4. 삭제 후 RP-01 신고 리스트 집계 반영 확인
5. 이의제기 연결 신고 삭제 시 삭제 이력 보존 확인
6. 신고 삭제 시 Supabase Storage 증빙 파일 실제 제거 여부 확인
7. Storage 삭제 실패 시 실제 HTTP 503/500 응답과 DB 롤백 여부 확인
8. Storage 일부 삭제 성공 후 실패 및 DB 커밋 실패 시 복구·재시도 전략 확인
9. 페이지네이션 정렬 및 마지막 페이지 경계 조건 확인
10. Spring Security의 실제 401 응답 확인

---

# 9. 최종 정리

RP-06은 로그인한 사용자의 신고 목록과 상세 정보를 조회하는 API이다.

신고 상세 조회에서는 숨김 처리된 식별자를 제외하고, 마스킹된 식별자만 반환한다.

신고에 이의제기가 연결되어 있다면 RP-08 알림 정보를 함께 제공한다.

RP-07은 본인이 작성한 신고를 삭제하는 API이다.

수정된 `ReportService`가 적용된 경우 신고 원본과 연결된 DB 데이터뿐 아니라 Supabase Storage의 신고 증빙 이미지 삭제도 요청한다. 이의제기 관련 삭제 이력은 별도로 보존한다.

Storage 삭제의 부분 실패와 외부 삭제 작업의 비원자성은 운영 전 테스트 및 보완이 필요하다.

RP-06과 RP-07의 API 명세는 공유된 Controller, Service, DTO 구조 및 Swagger 표시 결과를 바탕으로 작성되었다. 실제 HTTP 응답과 삭제 동작은 통합 테스트로 최종 확인해야 한다.
