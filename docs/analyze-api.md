# 캡처 판독 API (Gemini)

AI는 거래 유형·판매자 원문·신호 후보를 추출한다. 단계 결정과 중복 제거는 기존 서버 규칙(`analysis-rules.md`)을 사용한다. 이미지와 추가 정보 안의 지시는 신뢰하지 않는다.

## 환경변수

IntelliJ BackendApplication의 환경 변수에 `GEMINI_API_KEY`를 추가한다. 키는 Google AI Studio에서 발급받아 로컬에만 저장하고 Git·채팅에 넣지 않는다. 선택적으로 `GEMINI_MODEL`을 지정할 수 있으며 기본값은 `gemini-3.8-flash`다. 키가 없어도 서버는 기동하며 판독 요청은 503을 반환한다.

실제 Google 호출은 키가 준비된 뒤 합성 캡처로 별도 확인해야 한다. 자동 테스트는 로컬 HTTP 모의 서버를 사용하며 실제 Google 호출·할당량·모델 품질을 검증하지 않는다.

## 요청 및 응답

1. `GET /auth/csrf`에서 CSRF 토큰을 받고 쿠키를 유지한다. 비로그인 판독도 CSRF가 필요하다.
2. `POST /analyze`, `multipart/form-data`: `captures`에 캡처 1~10장, `additionalInfo`에 추가 정보(선택, 최대 500 코드 포인트)를 보낸다. CSRF 응답의 headerName/token을 요청 헤더에 넣는다.
3. 응답은 `analyzeId`, `readable`, `detectedType`, `assessment`, 가린 `identifiers`, `otherSuspicious`, `message`다. `assessment`는 기존 서버 최종 판정 구조이며 응답은 `Cache-Control: no-store`다.
4. 결과 화면을 떠날 때 같은 쿠키와 CSRF 토큰으로 `DELETE /analyze/{analyzeId}`를 호출한다(204). 다른 세션의 ID는 404다.

업로드는 한 장당 10MiB 이하다. PNG/JPG를 권장한다. JDK ImageIO 디코더로 읽을 수 없는 HEIC/WebP 등은 400이다. 실제 이미지 디코딩을 확인하고 축소·PNG 변환한 뒤 전송한다. 1억 픽셀 초과 이미지는 거절한다.

읽기 실패는 200 + `readable:false`이며 판정과 세션 ID가 없다. 잘못된 입력은 400, 업로드 용량 초과는 413, 공급자 할당량 초과는 429, 키 누락·인증 오류는 503, 시간 초과는 504, 기타 AI 응답 오류는 502다. 시간 초과·일시 오류·잘못된 JSON은 한 번만 재시도한다. 15초 요청 제한이므로 두 번 합산 약 30초와 연결·처리 시간이 걸릴 수 있다.

## 보관과 미연결 범위

원문과 정규화 이미지는 후속 신고 흐름을 위한 서버 메모리 스냅샷에만 둔다. 사용자 세션당 최신 1개, 전체 최대 16개, 이미지 문자열 합계 약 64MiB 한도이며 한도 초과 시 오래된 결과부터 제거한다. 15분 후 만료되고 1분 주기로 정리한다. DB나 파일에는 저장하지 않으며 서버 재시작 시 소멸한다. 여러 서버 간 공유 저장소는 아직 없다. 화면 이탈 시 즉시 삭제하려면 프런트의 DELETE 연동이 필요하다.

`AnalysisReportLookup`은 신고 담당자의 식별자 정규화·해시 계약을 연결할 경계다. 현재 식별자가 있으면 조회 불가로 처리해 `lookupWarning`을 반환한다. 실제 신고 집계 조회는 구현하지 않았으므로 통합 완료로 간주하면 안 된다. 식별자가 없을 때만 빈 조회 결과가 정상이다.

모델이 반환한 개인정보 목록과 식별자를 근거·사유에서 가리지만 모델의 탐지 누락까지 보장하지 않는다. API의 `store:false`는 Interactions 저장을 비활성화하는 설정이며, 무료 등급의 데이터 이용 약관을 대체하지 않는다. 실 사용자 대화를 무료 API에 넣기 전 팀에서 데이터 처리 조건을 확인해야 한다. 개발 검증에는 개인정보가 없는 합성 캡처를 사용한다.

## 공급자 문서

- https://ai.google.dev/gemini-api/docs/interactions-breaking-changes-may-2026
- https://ai.google.dev/gemini-api/docs/structured-output
- https://ai.google.dev/gemini-api/terms
