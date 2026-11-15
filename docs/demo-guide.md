# 인증·판독·신고 연동 시연 안내

## 시작 전에

- 최신 develop으로 서버를 실행한다. 로컬 기준 주소는 `http://localhost:8080`이다.
- 팀 DB·전화번호 암호화/해시·식별자 암호화/해시·AI 환경변수는 기존 팀 설정을 사용한다. 키나 비밀번호는 문서에 적지 않는다.
- 시연 인증은 `SPRING_PROFILES_ACTIVE=demo`를 사용한다. 로컬 HTTP에서만 `SESSION_COOKIE_SECURE=false`를 쓴다.
- 실제 AI 판독에는 설정한 공급자의 유효한 키·잔여 한도가 필요하다. OpenAI 호출은 사용량 비용이 발생할 수 있다.
- FRAUD 증빙까지 시연하려면 Private `report-evidence` 버킷과 서버의 Supabase URL·Storage 권한 설정이 필요하다.
- 테스트 계정은 시연 전에 직접 준비한다. 아래 예시의 이메일·비밀번호는 실제 공용 테스트 계정이 아니다. 동일 번호로 반복 가입하지 않는다.
- 배포 설정, 트루샷 AI·CV, 탈퇴 전체 흐름은 이 안내의 점검 범위에서 제외한다.

## 운영단·심사위원에게 설명할 인증 구현 범위

> 문자 비용 및 발신번호 등록 제약으로 SMS 전송을 시연 수신함으로 대체했습니다. 입력 번호에 대해 서버가 매번 무작위 6자리 코드를 생성하고, 해시 저장·3분 만료·60초 재발급 제한·오입력 5회 제한·세션 연결·가입 시 인증 소비를 실제로 처리합니다. demo 응답의 수신함에서 코드를 확인할 수 있으며 실제 문자는 발송하지 않습니다. 휴대폰 소유 여부까지 검증하는 기능은 아닙니다.

`123456` 같은 고정 코드를 넣는 방식이 아니다. 매번 이번 응답의 `demoSms.code`를 확인한다.

## Swagger·Postman 공통 준비

Swagger는 `/swagger-ui/index.html`을 연다. 상단 `accounts`는 인증·계정, `analysis`는 판독·피드백, `all`은 신고까지 포함한 목록이다.

Postman은 요청마다 같은 `localhost` 주소와 쿠키 저장소를 유지한다. `127.0.0.1`과 섞지 않는다. 별도 Bearer/JWT 설정은 필요 없다.

1. `GET /auth/csrf`를 호출한다.
2. 응답의 `token` 값만 복사해 모든 POST·PATCH·DELETE의 헤더에 넣는다.

| Key | Value |
| --- | --- |
| X-CSRF-TOKEN | 방금 받은 token 문자열 전체. 따옴표·JSON 제외 |

가입·로그인·로그아웃 후에는 CSRF를 다시 받는다. Swagger의 Authorize에 JSESSIONID를 직접 넣지 않는다. 브라우저가 세션 쿠키를 유지한다.

## 1. 휴대폰 인증 → 회원가입

아래 전화번호는 형식 예시다. 팀에서 준비한 시연용 번호로 바꾼다.

`POST /auth/phone/send`, Body raw JSON:

```json
{"phone":"01012345678"}
```

200 응답에서 `verificationId`, `demoSms.code`를 확인한다. 이어서 `POST /auth/phone/verify`:

```json
{"phone":"01012345678","verificationId":1,"code":"이번에 받은 6자리 값"}
```

`verificationId: 1`은 예시다. 실제 응답값으로 바꾼다. 성공하면 `POST /auth/signup`:

```json
{"email":"fan@example.invalid","password":"Examplepass123!","phone":"01012345678","verificationId":1,"privacyConsent":true}
```

200·사용자 `id` 반환이 성공 기준이다. 자동 로그인되므로 CSRF를 새로 받고 `GET /me`가 200인지 확인한다. 이미 가입한 계정은 `/auth/login`에 이메일·비밀번호만 보내고 CSRF를 새로 받는다.

## 2. 대화 캡처 판독

`POST /analyze` → Body **form-data**:

| Key | 종류 | 값 |
| --- | --- | --- |
| captures | File | 개인정보 없는 합성 대화 이미지 1~10장 |
| additionalInfo | Text, 선택 | 거래 유형 등 500자 이내 |

Postman에서 직접 넣었던 `Content-Type: application/json`은 해제한다. multipart 경계값은 도구가 자동 지정하게 둔다. Swagger는 Try it out 후 파일 선택, 여러 장은 Add string item으로 추가한다.

권장 합성 대화: 판매자가 가상 은행·전체 계좌를 알려 주고 티켓 사이트 로그인 인증번호를 요구하는 내용. 송금 검증까지 할 때는 이미지 속 가상 수취 은행·계좌가 송금 완료 합성 이미지와 정확히 같아야 한다. 실제 송금은 하지 않는다. 은행 코드·계좌 원문을 응답에 노출시키려고 수정하지 않는다.

확인할 항목:

- `readable:true`, `analyzeId`, `assessment.finalStage`, 사진별 `conversations`.
- 위험 신호가 있는 캡처에서 `showReportButton`, `showSuspicionReport` 확인. 모델 출력에 따라 결과는 달라질 수 있다.
- `identifiers[].value`의 `***1111`은 표시용 마스킹이다. 계좌 불일치의 증거가 아니다.
- `readable:false`이면 결과를 안전으로 표시하지 않고 재촬영 안내를 표시한다. 이번 응답에는 analyzeId가 없다.

## 3. 판독 피드백

같은 세션에서 `POST /analyze/{analyzeId}/feedback`, raw JSON:

```json
{"kind":"HELPFUL"}
```

또는 `INCORRECT`를 사용한다. 첫 제출 201, 같은 판독 재제출 409가 정상이다. 다른 세션·만료·삭제·새 판독으로 교체된 ID는 404다.

## 4. 송금 사진 검증 → FRAUD 신고

로그인 상태이며 판독에 신고 가능한 계좌가 있어야 한다. `POST /reports/evidence`, form-data:

| Key | 종류 | 값 |
| --- | --- | --- |
| analysisId | Text | 판독 응답의 **analyzeId** |
| accountIndex | Text | ACCOUNT 항목만 센 0부터 시작하는 순번. 첫 계좌는 0 |
| file | File | 동일 가상 은행·계좌의 송금 완료 합성 이미지 |

`account`에 `***1111`을 넣지 않는다. `account`와 `accountIndex`도 함께 보내지 않는다. `verified:true`와 `data.evidenceToken`을 받은 뒤 `POST /reports`, raw JSON:

```json
{"analysisId":"판독 응답의 analyzeId","kind":"FRAUD","evidenceToken":"증빙 응답의 data.evidenceToken"}
```

201·`data.kind:FRAUD`·`data.status:PUBLISHED`·`data.reportId`를 확인한다. 캡처별 신고 가능 조건에 따라 등록이 거부될 수도 있다. 새 판독을 하면 이전 판독이 교체되므로 이 흐름 중간에는 `/analyze`를 다시 호출하지 않는다.

시연 후 공유 조회에 테스트 신고를 남기지 않으려면 본인의 정확한 reportId만 확인하고 `DELETE /me/reports/{reportId}`로 정리한다. 타인 신고·공유 식별자는 정리 대상으로 잡지 않는다. 탈퇴 전체 검증은 별도 진행한다.

## 막혔을 때

| 상황 | 할 일 |
| --- | --- |
| 403 | 같은 쿠키로 `/auth/csrf` 재발급 후 헤더 값만 교체. CSRF 검사를 끄지 않는다 |
| 401 | 로그인 상태·쿠키 확인 후 로그인하고 CSRF 재발급 |
| 인증번호 400 | 이번 ID·코드·번호·만료 확인. 5회 초과면 60초 제한 뒤 재발급 |
| 판독 429 | AI 한도 확인 후 기다린다. 인증 발급의 429는 60초 재발급 제한이다 |
| 판독 502·503·504 | AI 응답·설정·시간 초과 구분. 실패를 SAFE로 표시하지 않는다 |
| 404 판독 없음 | 같은 세션인지 확인. 최신 판독 ID로 전체 흐름 재시작 |
| 계좌 선택 400 | ACCOUNT 순번과 마스킹 값 사용 여부 확인 |
| 송금 계좌 422 | 합성 영수증의 전체 은행·수취 계좌·송금 완료 표시 확인 |
| 신고 503 | Storage 설정·권한·로그 확인. 계좌 검증 성공과 파일 저장 성공을 구분 |
| 500 또는 약 30초 지연 | 서버 로그의 해당 시각 ERROR·Caused by 확인. DB 연결 닫힘·풀 대기 등 확인 후 재시작하여 복구 여부 확인. 토큰 오류로 단정하지 않는다 |

오류 공유 시 경로·상태 코드·시각·재현 순서를 함께 전달하고 키·비밀번호·전체 계좌·쿠키·토큰은 가린다. 자동 점검의 구체적인 범위는 [실패 상황 점검 기록](failure-checks.md)을 참고한다.
