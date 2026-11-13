# OpenAI 판독 연결 (#22)

판독의 외부 AI 호출을 `AI_PROVIDER`로 선택한다. 기본값은 기존 팀 환경 호환을 위해 gemini이며, OpenAI 사용 시 openai를 명시한다. 키 유무로 공급자를 자동 선택하거나 실패한 공급자에서 다른 공급자로 우회하지 않는다.

## 실행 설정

IntelliJ BackendApplication의 환경 변수에 다음 값을 설정한다. 실제 키는 Git에 포함되지 않는 로컬 설정에만 둔다.

| 환경변수 | 값/기본값 |
| --- | --- |
| AI_PROVIDER | openai |
| OPENAI_API_KEY | 사용할 OpenAI 프로젝트 API 키 |
| OPENAI_MODEL | gpt-6-luna |
| OPENAI_REASONING_EFFORT | low |
| OPENAI_MAX_OUTPUT_TOKENS | 8192 |
| ANALYSIS_MODEL_TIMEOUT | 30s (요청 1회당) |

2026-11-13 수빈 로컬의 BackendApplication 설정에는 OPENAI_API_KEY와 AI_PROVIDER=openai를 반영했다. 실행 중인 서버는 재시작해야 적용된다. IDE가 기존 설정을 메모리에 보관하고 있으면 실행 설정의 환경 변수에서 두 항목이 보이는지 확인하고 저장한다. 배포 서버는 별도로 같은 환경변수를 설정해야 한다.

Gemini로 명시 전환할 경우 AI_PROVIDER=gemini 및 GEMINI_API_KEY를 사용한다. 양쪽 키가 있더라도 선택된 클라이언트 하나만 등록한다. 사용자 입력으로 공급자·모델·키를 지정하는 API는 없다.

## 호출 및 검증

Java 17 HttpClient로 `POST https://api.openai.com/v1/responses`를 호출한다. 신호 사전·프롬프트와 정규화 PNG의 Base64 data URL을 전달하고, strict JSON Schema의 text.format으로 기존 추출 구조를 요청한다. 활성 신호 ID enum 제약도 유지한다. `store:false`, 출력 토큰 상한, 요청 제한 시간을 명시하며 외부 도구·파일 업로드 API·이미지 생성 도구는 사용하지 않는다.

Responses의 completed 상태와 assistant/output_text만 읽는다. 이미지 입력·근거 추출·가림·서버 단계 계산·사진별 응답·세션 삭제·피드백의 HTTP 계약은 기존과 같다.

| 공급자 응답 | 서버 처리 |
| --- | --- |
| 키 누락·잘못된 설정·400/404 | 503, 재시도 없음 |
| 401/403 | 503, 재시도 없음 |
| 429 (할당량·크레딧 등) | 429, 재시도·공급자 변경 없음 |
| 시간 초과 | 1회 재시도 후 504 |
| 5xx·잘못된 응답/JSON | 1회 재시도 후 502 |
| refusal·incomplete | 502, 재시도 없음. 안전 판정으로 바꾸지 않음 |

공통 `AnalysisModelClient`와 `ModelException`을 사용한다. 제공자 오류 본문·키·원문을 사용자 오류 응답으로 반환하지 않는다. `store:false`는 Responses 상태 저장 설정이며 모든 제공자 보관 정책을 대체하는 보장은 아니다.

## 실제 검증 기록 (2026-11-13)

사용자가 지정한 키의 모델 목록 조회는 200이었고 gpt-6-luna 접근을 확인했다. 실제 계정의 소유자나 비용 한도는 모델 목록 API로 확인한 것이 아니다. OpenAI 사용량·크레딧·과금은 해당 프로젝트 설정을 따른다.

임시 H2 서버에서 개인정보 없는 합성 캡처를 사용했다. 기존 IntelliJ 서버와 공유 Supabase·Storage는 사용하지 않았다.

| 실제 HTTP 검증 | 결과 |
| --- | --- |
| OTP 요구 합성 캡처 | POST /analyze 200, readable=true, S-A2 2개, DANGER, 사진 1장, 약 7.5초 |
| 공식 재판매 앱 내 결제 합성 캡처 | POST /analyze 200, readable=true, 근거 없음, SAFE, 약 4.9초 |
| 두 결과 세션 삭제 | DELETE /analyze/{id} 204 |

당시 소수 사례 실측으로 정확도·응답 시간을 보장하는 벤치마크는 아니다. 자동 테스트는 로컬 모의 API만 호출한다. 키와 실제 공급자 요청은 자동 테스트·CI에 넣지 않았다.

## 공식 OpenAI 문서

- [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create)
- [이미지 입력](https://developers.openai.com/api/docs/guides/images-vision)
- [구조화 출력](https://developers.openai.com/api/docs/guides/structured-outputs)
- [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna)
