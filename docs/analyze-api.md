# 캡처 판독 API (Gemini / OpenAI)

## Swagger에서 확인하기

서버 실행 후 `http://localhost:8080/swagger-ui/index.html`을 열고 상단 정의에서 `analysis`를 선택한다.
OpenAPI JSON은 `/v3/api-docs/analysis`이다. 기존 `all`·`trueshot` 정의도 유지한다.

1. `accounts`의 `GET /auth/csrf` → **Try it out → Execute**로 토큰을 받는다.
2. 같은 브라우저를 유지하며 변경 요청의 `X-CSRF-TOKEN` 칸에 응답의 `token` 값만 넣는다.
3. 회원가입·로그인·로그아웃 후 CSRF를 다시 발급한다. 세션 쿠키는 브라우저가 관리하므로 Authorize에 JSESSIONID를 직접 넣지 않는다.
4. 판독은 `analysis`의 `POST /analyze`에서 `captures` 파일을 선택한다. 여러 장은 **Add string item**으로 추가하고 `additionalInfo`는 필요할 때 입력한다.

요청 예시는 가상 값이다. 오류 응답은 상태 코드와 설명을 확인한다. API별 오류 포맷이 달라 성공 DTO를 오류 스키마로 재사용하지 않는다.


AI는 거래 유형·판매자 원문·신호 후보를 추출한다. 단계 결정과 중복 제거는 기존 서버 규칙(`analysis-rules.md`)을 사용한다. 이미지와 추가 정보 안의 지시는 신뢰하지 않는다.

## 환경변수

`AI_PROVIDER=gemini`(기본값) 또는 `AI_PROVIDER=openai`로 하나를 선택한다. OpenAI 키는 GEMINI_API_KEY에 넣지 않는다. OpenAI 실행 설정·오류 처리·실제 검증 결과는 [OpenAI 판독 연결](openai-api.md)을 참고한다. 아래 Gemini 설정은 Gemini 선택 시 적용한다.

IntelliJ BackendApplication의 환경 변수에 `GEMINI_API_KEY`를 추가한다. 키는 Google AI Studio에서 발급받아 로컬에만 저장하고 Git·채팅에 넣지 않는다. 선택적으로 `GEMINI_MODEL`을 지정할 수 있으며 기본값은 `gemini-3.5-flash-lite`다. 키가 없어도 서버는 기동하며 판독 요청은 503을 반환한다.

2026-11-13 개인정보 없는 합성 캡처로 실제 Google 호출과 서버 판정 연결을 확인했다(아래 검증 기록). 자동 테스트는 로컬 HTTP 모의 서버를 사용하며 실제 Google 호출·할당량·일반적인 모델 품질을 검증하지 않는다.

## 요청 및 응답

1. `GET /auth/csrf`에서 CSRF 토큰을 받고 쿠키를 유지한다. 비로그인 판독도 CSRF가 필요하다.
2. `POST /analyze`, `multipart/form-data`: `captures`에 캡처 1~10장, `additionalInfo`에 추가 정보(선택, 최대 500 코드 포인트)를 보낸다. CSRF 응답의 headerName/token을 요청 헤더에 넣는다.
3. 응답은 `analyzeId`, `readable`, `detectedType`, `assessment`, 가린 `identifiers`, `otherSuspicious`, `message`다. `assessment`는 기존 서버 최종 판정 구조이며 응답은 `Cache-Control: no-store`다.
4. 결과 화면을 떠날 때 같은 쿠키와 CSRF 토큰으로 `DELETE /analyze/{analyzeId}`를 호출한다(204). 다른 세션의 ID는 404다.

업로드는 한 장당 10MiB 이하다. PNG/JPG를 권장한다. JDK ImageIO 디코더로 읽을 수 없는 HEIC/WebP 등은 400이다. 실제 이미지 디코딩을 확인하고 축소·PNG 변환한 뒤 전송한다. 1억 픽셀 초과 이미지는 거절한다.

읽기 실패는 200 + `readable:false`이며 판정과 세션 ID가 없다. 잘못된 입력은 400, 업로드 용량 초과는 413, 공급자 할당량 초과는 429, 키 누락·인증 오류는 503, 시간 초과는 504, 기타 AI 응답 오류는 502다. 시간 초과·일시 오류·잘못된 JSON은 한 번만 재시도한다. 기본 30초 요청 제한이므로 두 번 합산 약 60초와 연결·처리 시간이 걸릴 수 있다. `ANALYSIS_MODEL_TIMEOUT`으로 요청당 제한을 변경할 수 있다.

## 보관과 미연결 범위

원문과 정규화 이미지는 후속 신고 흐름을 위한 서버 메모리 스냅샷에만 둔다. 사용자 세션당 최신 1개, 전체 최대 16개, 이미지 문자열 합계 약 64MiB 한도이며 한도 초과 시 오래된 결과부터 제거한다. 15분 후 만료되고 1분 주기로 정리한다. DB나 파일에는 저장하지 않으며 서버 재시작 시 소멸한다. 여러 서버 간 공유 저장소는 아직 없다. 화면 이탈 시 즉시 삭제하려면 프런트의 DELETE 연동이 필요하다.

`AnalysisReportLookup`은 공통 정규화·해시로 추출 식별자를 정확 매칭하고 기존 신고 인원을 최종 단계에 합산한다 (#37). 형식 불명확·유형 불일치·DB 조회 실패는 lookupWarning을 반환한다. 확인된 위험 신고는 유지한다.

모델이 반환한 개인정보 목록과 식별자를 근거·사유에서 가리지만 모델의 탐지 누락까지 보장하지 않는다. API의 `store:false`는 Interactions 저장을 비활성화하는 설정이며, 무료 등급의 데이터 이용 약관을 대체하지 않는다. 실 사용자 대화를 무료 API에 넣기 전 팀에서 데이터 처리 조건을 확인해야 한다. 개발 검증에는 개인정보가 없는 합성 캡처를 사용한다.

## 공급자 문서

- https://ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026
- https://ai.google.dev/gemini-api/docs/structured-output
- https://ai.google.dev/gemini-api/terms

## JD-04 판독 결과 피드백

`POST /analyze/{analyzeId}/feedback`에 JSON `{"kind":"HELPFUL"}`(도움이 되었어요) 또는 `{"kind":"INCORRECT"}`(결과가 이상해요)를 보낸다. 로그인은 필요 없지만 판독을 수행한 브라우저의 쿠키와 `/auth/csrf`에서 받은 CSRF 헤더가 필요하다. 클라이언트는 `/analyze` 응답의 analyzeId를 그대로 사용한다.

- 성공: 201, `Cache-Control: no-store`, `{"message":"소중한 의견 감사합니다"}`. 누른 버튼을 선택 상태로 표시하고 두 피드백 버튼을 비활성화한다.
- 같은 판독의 두 번째 제출(종류 변경 포함): 409. 한 판독당 한 번이며 수정 API는 없다.
- 다른 브라우저, 알 수 없는 ID, 삭제·교체·15분 만료된 판독: 404. 읽기 실패 결과에는 analyzeId가 없으므로 제출할 수 없다.
- 종류 누락·잘못된 종류: 400. CSRF 누락: 403.

기존 `public.feedback`에 id(bigint), analyze_id(uuid), kind(varchar), created_at(timestamptz)만 저장한다. 사용자 ID·전화번호·이메일·대화·캡처·모델 응답은 저장하지 않는다. 판독 세션을 삭제해도 이미 저장된 익명 피드백은 유지한다. 실제 팀 테이블의 컬럼과 제약은 읽기 전용으로 확인했으며 이번 기능은 DB 변경을 요구하지 않는다.

중복 제출 검사는 현재 메모리 판독 세션 저장소의 잠금 안에서 DB 트랜잭션 커밋까지 수행한다. 같은 프로세스의 동시 요청은 하나만 저장된다. 현재 구조에서는 다른 프로세스가 해당 판독 세션을 갖고 있지 않아 제출할 수 없다. 추후 판독 세션을 여러 서버가 공유하도록 바꾸면 DB의 analyze_id 고유 제약과 분산 동시성 처리도 함께 추가해야 한다.

### 팀 검토 목록

`INCORRECT` 행이 팀 검토 대상이다. 서버 내부에서는 `FeedbackRepository.findByKindOrderByCreatedAtDescIdDesc(INCORRECT, pageable)`로 페이지 단위 조회한다. 공개 검토 목록 API는 제공하지 않는다. 팀 Supabase SQL Editor에서 다음 읽기 전용 쿼리로 확인할 수 있다.

```sql
SELECT id, analyze_id, kind, created_at
FROM public.feedback
WHERE kind = 'INCORRECT'
ORDER BY created_at DESC, id DESC
LIMIT 100;
```

대화는 저장하지 않으므로 이 목록만으로 원래 대화를 재현할 수 없다. 팀이 별도로 수집한 합성 테스트 사례로 판정 규칙을 검토한다.

## 사진별 주요 대화·근거 응답 (#18)

`POST /analyze` 응답의 기존 필드는 유지하고 `conversations`를 추가한다.

```json
{
  "conversations": {
    "imageCount": 3,
    "suspiciousImageCount": 1,
    "images": [
      {
        "imageNumber": 3,
        "countedSignals": [
          {
            "id": "S-A2",
            "criterion": "A",
            "strength": "STRONG",
            "sentenceId": "s1",
            "quote": "인증번호를 보내 주세요",
            "reason": "인증번호를 요구하는 문장입니다"
          }
        ],
        "informationalSignals": []
      }
    ]
  }
}
```

- `imageCount`: 실제 요청에 포함된 전체 캡처 수. AI가 보고한 숫자를 사용하지 않는다.
- `suspiciousImageCount`: 서버 판정 규칙에서 채택한 countedSignals가 있는 서로 다른 사진 수. 같은 사진에 신호가 여럿이어도 한 장으로 센다.
- `images`: 서버가 채택한 판정 근거 또는 참고용 근거가 있는 사진만 포함하며 imageNumber 오름차순이다. 신호가 없는 사진은 목록에서 빠져도 전체 imageCount에는 포함된다.
- `imageNumber`: 업로드 순서 기준 1부터 시작한다. 프런트에서 원본 미리보기 배열의 `imageNumber - 1`에 연결한다.
- `countedSignals`: 기존 assessment.chat.countedSignals와 같은 검증·개인정보 가림이 적용된 근거다. 강한 신호 STRONG은 빨강, 주의 CAUTION은 노랑으로 표시한다. 같은 신호의 최대 합산 횟수와 A1/F1 예외 등 기존 서버 규칙을 그대로 따른다.
- `informationalSignals`: 아옮·대리티켓팅·취켓 대행에서 합산하지 않는 A1 등의 참고 표시다. 표시하되 판정·의심 사진 수에 더하지 않는다. 따라서 이 항목만 있는 사진은 images에 있으면서 suspiciousImageCount는 0일 수 있다.

AI가 임의로 제안한 신호, 구매자 문장, 원문에 없는 인용문은 사진별 근거에도 노출하지 않는다. 근거 문장과 이유는 가린 값만 반환하며 전체 대화 텍스트나 이미지 바이트·Storage URL을 이 응답에 추가하지 않는다.

프런트는 업로드할 때 캡처 순서와 로컬 미리보기를 결과 화면까지 유지한다. 결과의 ‘원본 사진 보기’는 해당 로컬 파일을 보여준다. `conversations`는 사진을 다시 다운로드하는 API가 아니다. 결과 화면 이탈 시 미리보기 URL을 해제하고 판독 DELETE를 호출한다.

화면의 ‘사진 N장 중 M장에서 사기 의심 정황을 찾았어요’는 imageCount와 suspiciousImageCount를 사용한다. 참고용 A1은 별도 계정 위험 안내와 연결한다. 최종 단계가 신고 기록 때문에 올라가더라도 사진별 대화 근거는 변경하지 않는다.

읽기 실패(`readable:false`)는 실제 imageCount와 0·빈 images를 반환하며 analyzeId는 없다. 읽을 수 있지만 채택된 근거가 없는 결과도 빈 images다. 화면에서는 readable을 먼저 확인한 뒤 안전 결과와 읽기 실패를 구분해야 한다.


## 실제 Gemini 호출 검증 (#20, 2026-11-13)

IntelliJ 로컬 설정의 키를 환경변수로만 전달했다. 키를 소스·문서·로그에 출력하지 않았고 공유 Supabase/Storage는 사용하지 않았다. Java 이미지 생성 과정에서 Dock에 표시됐던 최초 검증 프로세스는 종료했으며 이후에는 headless 모드로 실행했다.

| 검증 | 결과 |
| --- | --- |
| Java/Python의 3.8 Flash 모델 메타데이터 GET | 200, 약 0.2~0.3초 |
| 3.8 Flash 캡처 판독 | 15초·30초 요청 제한에서 각각 시간 초과. 기본 모델에서 제외 |
| 3.5 Flash-Lite 최소 생성 | 200, 약 1.5초 |
| OTP 요구 합성 캡처 → 실제 모델 → 서버 규칙 | readable=true, S-A2 2개, DANGER, 약 3.1초 |
| 공식 재판매 앱 내 결제 합성 캡처 → 실제 모델 → 서버 규칙 | readable=true, 근거 없음, SAFE, 약 9.6초 |
| 임시 H2 서버에 CSRF/쿠키를 포함한 POST /analyze | 200·no-store, S-A2·DANGER, 사진 1장, 약 5.4초 |
| HTTP 판독 세션 DELETE | 204 |

이 값은 소수 합성 사례의 당시 실측이며 응답 시간 보장이나 판독 정확도 평가가 아니다. 두 사례 모두 실제 사람·전화번호·계좌 없이 생성했다. 신고 조회 집계와 실제 사용자 대화 품질을 검증한 것은 아니다.

초기 실호출에서 모델이 signals[].id를 S-A2 대신 sig1로 출력해 서버가 채택하지 않는 문제를 확인했다. 프롬프트에 신호 ID 의미를 명시하고, 전송 JSON 스키마의 해당 필드를 현재 활성 신호 ID enum으로 제한했다. enum은 신호 사전에서 동적으로 구성해 비활성 신호가 포함되지 않게 했다.

추론 수준은 `GEMINI_THINKING_LEVEL`(기본 low)로 지정한다. 지원 수준은 모델마다 다르므로 변경 시 공식 문서를 확인한다. 무료 등급 프로젝트와 무료 제공 한도 내 사용을 전제로 하며, 유료 모델로 자동 전환하거나 결제 설정을 변경하지 않는다. 무료 한도 초과 시 429로 실패하고 유료 우회는 하지 않는다. API 키가 속한 프로젝트의 결제 연결 여부는 AI Studio에서 확인해야 한다.

- [공식 모델 요금표](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.5-flash-lite)
- [공식 추론 수준 설정](https://ai.google.dev/gemini-api/docs/thinking)

## 공개 신고 집계와 판독 연결 (#26·#37)

`PublicReportCounts.count(identifierIds, asOf)`는 서버에서 정확히 매칭한 식별자 ID를 입력받는 내부 집계 서비스다. 외부 API가 아니며 클라이언트가 보낸 ID로 판독 결과를 바꾸지 않는다.

- `created_at` 기준 최근 1년(경계 포함)부터 `asOf`까지의 `PUBLISHED`, `RESPONDING`, `REVIEWING` 신고를 포함한다. 팀원 공개 조회의 활성 상태와 동일하며 PRIVATE는 제외한다.
- `report_identifiers.hidden=true` 연결은 제외한다.
- 식별자·신고 종류별 서로 다른 `reporter_hash` 수를 센다. 다른 식별자의 인원을 합치지 않는다.
- 실제 존재하지만 신고가 없는 식별자는 0명, 존재하지 않는 ID는 결과에서 제외한다.
- 동일 신고자가 피해 신고와 의심 제보를 모두 남기면 각 종류에서 한 번씩 센다.

AnalysisReportLookup이 정규화 → 유형 확인 → HMAC → findByTypeAndValueHash → PublicReportCounts를 호출한다. DB 스키마 변경과 식별자 생성·수정은 없다.

## 신고 등록에 판독 결과 전달 (#28)

`AnalysisReportSourceService.load(analysisId, request.getSession(false))`를 신고 서비스에서 호출한다. 외부 HTTP 조회 API를 추가하지 않았다. 요청 본문에서는 `analysisId`만 받고 `analysisOwner`나 사용자 ID를 받지 않는다.

- 서버 세션의 Spring Security 인증과 ACTIVE 계정을 확인한다(실패 401).
- 세션의 `analysisOwner`와 결과 소유권을 비교한다. 만료·삭제·다른 세션·신규 판독으로 대체된 결과는 모두 404다.
- 같은 브라우저의 익명 판독 뒤 로그인은 기존 세션 속성이 유지되므로 사용할 수 있다. 다른 브라우저/서버 재시작 후에는 다시 판독해야 한다. 로그인 상태에서 다른 계정으로 전환하면 이전 판독 결과는 삭제된다 (#30).
- 반환값: 판독 ID, 로그인 사용자 ID, 추정 거래 유형, 원문 계좌·전화·SNS 목록, 서버 규칙으로 채택하고 민감정보를 가린 근거 목록, 최초 판독에서 저장한 showReportButton·showSuspicionReport다. 타입이 OTHER이면 신고 흐름에서 사용자의 거래 유형 확인이 필요하다.
- 원문 식별자는 신고 담당자의 정규화·해시·암호화 처리에만 사용한다. 이 DTO를 HTTP 응답·공개 근거·로그로 출력하지 않는다. 모델 추출값은 검증된 판매자 신원이나 사실 확정이 아니다.
- 원본 사진, 전체 대화, 모델이 임의로 생성한 신호는 넘기지 않는다. 사진 증빙이 필요하면 신고 단계에서 별도로 업로드·가림 처리한다.
- 이 서비스는 결과를 조회하는 시점에 유효성을 확인하고 불변 목록으로 복사한다. 신고 서비스는 반환된 값으로 같은 요청 안에서 신고 DB를 저장해야 한다. 저장 뒤에는 임시 판독 세션이 아니라 신고 DB를 사용한다. 중복 신고 정책·신고 가능 조건·트랜잭션·증빙 저장은 신고 모듈 책임이다.

연결 예시(신고 담당자 코드):

```java
var source = analysisReportSourceService.load(input.analysisId(), request.getSession(false));
// source.userId()를 작성자로 사용하고, source.identifiers()를 팀 식별자 규칙으로 처리한다.
// source.evidence()의 가려진 근거를 신고 DB에 복사한다. 원문 식별자를 응답에 넣지 않는다.
```

현재 서버 메모리 결과는 15분, 세션별 최신 1건, 전체 최대 16건/이미지 메모리 용량 제한에 따라 더 일찍 제거될 수 있다. 이 변경은 신고 담당자가 호출할 경계까지이며 신고 등록 API에 실제 연결된 상태는 아니다.

## 식별자 매칭 계약 (#37)

공통 IdentifierNormalizer와 IdentifierHashService는 feat/#36의 커밋 1c926963c84248f13548f538303891d12b80283e에서 변경 없이 가져왔다. 팀원 브랜치 전체, 증빙 업로드, 암호화 서비스는 이 작업에 포함하지 않는다.

- IDENTIFIER_HASH_SECRET은 신고 담당자와 동일한 비밀값으로 환경변수에 설정한다. UTF-8 문자열 그대로 HMAC 키로 쓰며 Base64 디코딩하지 않는다. HMAC-SHA256 입력은 type.name() + ":" + normalizedValue이고 결과는 소문자 hex다. PHONE_HASH_KEY와 별개다.
- 전화는 공백·하이픈 제거, X 계정은 @ 유무를 수용한 뒤 공통 클래스에서 소문자화한다. X URL은 임의로 계정명으로 추측하지 않는다.
- 계좌는 은행명과 전체 번호가 있어야 한다. 지원 표기는 공통 클래스 그대로 사용하며 은행 누락·미지원 표기는 조회 미완료로 처리한다. 숫자 뒷자리만으로 매칭하지 않는다.
- available=false는 일부 또는 전체 조회가 불완전하다는 뜻이다. targets에는 확인된 신고 집계가 포함될 수 있다. 경고를 표시하되 확인된 위험도를 낮추지 않는다. DB 오류로 집계를 얻지 못하면 빈 targets와 경고를 반환한다.
- 정상 조회에서 매칭이 없으면 신고 0건이다. 거래 안전을 보증하는 것은 아니다.
- 판독 조회에서는 식별자를 저장·암호화하지 않는다. IDENTIFIER_ENCRYPTION_KEY는 신고 저장 모듈에 필요하며 이 조회에는 사용하지 않는다.

H2와 합성 모델 응답으로 판독→정규화/해시→신고 집계→최종 단계 흐름을 검증했다. 실제 신고 등록과 이어지는 실환경 검증은 신고 모듈 병합 후 진행한다. 비밀값은 Git이나 로그에 넣지 않는다.

## 최초 신고 가능 조건 전달 (#39)

AnalyzeService는 최초 FinalAssessment를 화면 응답으로 보내기 전에 동일한 showReportButton·showSuspicionReport를 AnalysisSessionStore.Snapshot에 함께 저장한다. load()는 두 조건을 다시 계산하거나 클라이언트 요청에서 받지 않고 해당 스냅샷의 값을 반환한다. 이후 신고 집계나 신호 사전이 바뀌어도 같은 analysisId의 두 조건은 최초 값으로 유지된다. 신규 판독은 기존 정책대로 이전 결과를 대체한다.

신고 담당자는 기존 호출 뒤 아래 접근자를 사용해 신고 종류별 허용 조건을 검증한다. 두 값이 같다고 가정하지 않는다. 대화는 안전하고 기존 신고로만 위험도가 올라가면 showReportButton=true, showSuspicionReport=false가 될 수 있다.

```java
var source = analysisReportSourceService.load(input.analysisId(), request.getSession(false));
boolean reportAllowed = source.showReportButton();
boolean suspicionAllowed = source.showSuspicionReport();
// 요청한 신고 종류에 맞는 조건을 검증한 뒤 저장한다.
```

원본 조건이 없는 내부/구형 2인자 Snapshot 생성은 두 값을 false로 취급한다. 사용자에게 다시 판독하도록 안내하며, 근거만 보고 신고 권한을 추정하지 않는다. 일반 판독 경로는 반드시 최초 계산값을 포함한 4인자 생성자를 사용한다. 현재 메모리 저장 방식이라 서버 재시작 전 결과는 복원되지 않는다. 이 두 값은 서버 내부 전달 DTO에 추가됐으며 신고 등록 API의 실제 거부 처리는 RP 담당자 코드에서 적용해야 한다.

#43: 로그아웃 성공 시 해당 브라우저의 임시 판독 스냅샷(원문·이미지·신고 조건)을 즉시 제거한다. CSRF 실패와 다른 브라우저 결과는 유지한다. 브라우저 종료만으로 즉시 삭제되는 것은 아니며 요청이 없으면 기존 만료 정책을 따른다.

## 계좌 추출 형식과 은행 정식명 호환 (#45)

수정 전 develop 정규화는 `국민 123456789012`와 `카카오 3333121234567`은 허용하지만 같은 번호의 `국민은행`, `카카오뱅크` 표기는 거부했다. 따라서 AI의 OCR 결과가 정확해도 판독 신고 조회가 불완전해지거나 RP 정규화에서 거부될 수 있었다. 이는 코드로 재현한 오류이며, 제보된 실제 캡처의 원인과 동일한지는 아직 확인하지 않았다.

- feat/#36 커밋 69ccfd4의 IdentifierNormalizer를 그대로 재사용해 정식 은행명·기존 별칭을 같은 코드로 정규화한다. 긴 은행명부터 비교한다. 기존 정규화 결과와 해시 입력은 유지하며 DB 재해시·데이터 변경은 없다.
- 프롬프트와 accounts 항목 설명에 `은행명 공백 전체번호` 형식을 명시했다. 예금주·JSON 문자열·콜론·설명을 붙이지 않고, 은행 또는 숫자가 불확실하면 추측하지 않도록 했다.
- 숫자는 순서와 선행 0을 보존한다. 같은 은행의 별칭만 바꾸며 은행 없는 번호·가림 번호·구매자 계좌를 판매자 계좌로 추출하지 않도록 지시한다. sensitive_values에는 실제 원문 표기도 포함하도록 지시했다.
- 정규화된 `KB:번호`는 내부 해시용 표현이며 모델의 accounts 출력 형식은 아니다. 미지원 은행·잘못된 형식은 기존처럼 조회 경고/등록 검증 대상이며 자동 추측하지 않는다.

실제 OpenAI(gpt-6-luna) 호출에서 개인정보 없는 합성 캡처 두 장을 검증했다. 전체 계좌 사례는 국민·카카오 계좌 2개가 예상 번호와 정확히 일치했고 신고 공통 정규화에 통과했다(약 7.8초). 가림 계좌 및 은행 없는 번호 사례는 accounts가 비어 있었다(약 4.4초). 외부 DB·Storage·실제 송금 사진을 사용하지 않았다. 이 두 사례가 실제 사용자 사진의 OCR 정확도나 FRAUD/송금 증빙 전체 통합을 보장하지 않는다.

## 화면용 마스킹과 RP-05 계좌 선택 (#55)

판독 응답 identifiers의 ACCOUNT 값 `***1111`은 의도된 화면용 표시다. 서버 스냅샷 및 AnalysisReportSource.identifiers.accounts에는 전체 은행·계좌가 유지된다. 표시값으로 원문 비교를 하거나 뒷자리만으로 계좌를 매칭하지 않는다.

POST /reports/evidence의 multipart 입력:

- analysisId: 현재 브라우저에서 판독한 ID
- accountIndex: 판독 응답 identifiers 중 type=ACCOUNT인 항목만 순서대로 나열했을 때의 0부터 시작하는 순번. 첫 계좌는 0. 서버가 소유권 확인한 스냅샷에서 원문을 선택한다.
- file: 송금 증빙 사진
- 세션 쿠키·CSRF 헤더 유지

accountIndex와 account는 함께 보내지 않는다. 기존 전체 은행·계좌 문자열 account 방식은 호환 유지한다. account에 ***1111 같은 마스킹을 보내면 400과 선택 안내를 반환한다. 둘 다 생략하고 추출 계좌가 정확히 하나면 자동 선택하며, 0개 또는 여러 개면 선택을 요구한다. 잘못된 순번은 400, 송금 사진의 실제 수취 계좌 불일치는 기존 422다. 정상 선택 뒤에도 전체 은행+번호 일치와 송금 완료·가림 검증은 생략하지 않는다.

RP-05의 evidenceToken을 받은 뒤 POST /reports에 analysisId, kind=FRAUD, evidenceToken을 전달한다. RP-04는 같은 스냅샷의 원문과 토큰의 계좌 해시를 사용한다. 계좌 원문을 판독 HTTP 응답에 추가하지 않았다.

신한 111-111-1-111111 합성 캡처의 실제 AI 호출에서 문장에는 숫자를 읽고도 accounts가 비는 사례를 관찰했다. 반복/테스트 번호를 존재 여부 추측으로 누락하지 않도록 추출 지침을 추가한 뒤 같은 사례의 전체 번호 추출과 ***1111 마스킹을 확인했다. 별도 합성 송금 화면에서도 계좌는 일치했으나 실제 이체 아님 표기가 있는 화면은 transferCompleted=false였다. 이를 실제 송금 사진 검증 성공으로 간주하지 않는다.

AnalysisFraudEvidenceFlowTests는 실제 HTTP 분석→증빙→FRAUD 생성, 가려진 표시값 거부, 잘못된 순번·계좌 거부, 재등록 거부를 검증한다. H2를 사용하고 AI·Storage·가림 개인정보 판정은 대체하며 이미지 검증·자르기와 계좌 비교·토큰·DB 저장은 실제 코드다. SMTP는 비활성화했다.

## 실제 AI·Storage·DB 전체 흐름 검증 (#57, 2026-11-15)

대체 모델이 아닌 설정된 OpenAI, 팀 Supabase DB 및 Private report-evidence 버킷으로 실제 HTTP 호출을 수행했다. 임시 서버는 127.0.0.1:18089에만 바인딩하고 스케줄러와 SMTP를 비활성화했다. 기존 계정/사진은 사용하지 않고 새 demo 계정과 합성 대화·송금 완료 PNG만 생성했다. 실제 금융 이체는 하지 않았다. 임시 진단 경로로 본인 판독 원문을 확인했으며 해당 경로는 저장소 코드나 일반 서버에 추가하지 않았다.

첫 실행:

- 가입·세션·CSRF: 정상.
- POST /analyze: 200, 약 9.8초. 표시값 ***1111, 서버 원문 신한 1111111111111, DANGER. 신고 가능 조건 true/true.
- account=***1111 요청: 400. 의도된 거부이며 AI 계좌 원문이 사라진 것이 아니다.
- accountIndex=0으로 POST /reports/evidence: 200, 약 5.4초. 실제 AI의 송금 완료 확인·전체 계좌 비교·영역 자르기·개인정보 검사·Storage 업로드·증빙 토큰 저장까지 통과했다.
- POST /reports(FRAUD): 503. 계좌 비교는 통과했지만 Storage 최종 복사 단계가 실패했다.

발견한 오류: SupabaseReportEvidenceStore가 object/copy의 bucketId 대신 sourceBucket을 보내고, 삭제도 공식 버킷+prefixes 배열 경로 대신 파일별 DELETE 경로를 사용했다. 공식 Storage SDK 계약에 맞춰 수정했다. 기존 단위 테스트는 상태 코드만 대체하고 요청 형식을 검증하지 않아 이를 놓쳤다. ReportEvidenceStorageContractTests가 필드명·경로·DELETE 본문을 검사하도록 추가했다.

수정 후 같은 테스트 계정으로 전체 재실행:

| 단계 | 실제 결과 |
| --- | --- |
| 판독 | 200, 약 8.0초, 원문/표시값 구분 정상 |
| 송금 사진 검증·임시 Storage | 200, 약 7.6초, 실제 증빙 토큰 발급 |
| FRAUD 신고 저장·증빙 복사 | 201, 약 1.7초 |
| DB 확인 | 신고 1건, 증빙 1건, 소비된 토큰 1건. 암호화된 식별자 복원 결과가 SHINHAN:1111111111111과 일치 |
| Storage 확인 | 최종 증빙 실제 읽기 200, 성공한 임시 파일은 삭제됨 |

참고: 공식 요청 계약 https://github.com/supabase/storage-js/blob/main/src/packages/StorageFileApi.ts (copy/remove).

테스트 전용 계정의 정확한 ID·이메일로 범위를 확인하고 해당 계정의 파일·임시 증빙 메타데이터만 제거한 뒤 DELETE /me=204로 계정과 신고를 정리했다. 파일 부재 및 users/reports/pending_report_evidence의 해당 사용자 행 0건을 확인하고 임시 서버를 종료했다. 공유 identifiers는 일반 탈퇴 정책대로 보존했다. 메일 발송·기존 데이터 변경·배포·트루샷 AI/CV 변경은 없다. 합성 사례 한 종류의 성공이며 모든 실사용 사진의 인식 정확도를 보장하지 않는다.
