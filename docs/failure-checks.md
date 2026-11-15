# 인증·판독·피드백 실패 상황 점검 (#62)

2026-11-15, develop 기반. H2·MockMvc 및 합성 이미지로 재현했다. AI 오류는 대체 모델 또는 로컬 가짜 공급자 서버로 강제했다. 팀 Supabase 변경·실제 AI 과금·문자·메일 발송은 하지 않았다. 실제 사진의 인식률이나 배포 환경의 네트워크 복원력 검증은 아니다.

| 범위 | 검증 시나리오 | 기대 동작 | 근거 테스트 |
| --- | --- | --- | --- |
| 인증 | CSRF 누락·미로그인 | 변경 요청 403, 계정 조회 401 | AuthFlowTests, OwnedApiOpenApiTests |
| 인증 | 인증번호 재발급·오입력·만료·다른 세션 | 60초 재발급 제한, 5회 오입력 잠금, 만료 거부, 타 세션 가입 거부 | AuthFlowTests |
| 계정 | 현재 비밀번호 오류·확인값 불일치·다른 사용자 번호 | 변경 거부, 기존 계정 유지 | AuthFlowTests, AccountFlowTests |
| 업로드 | 파일 없음·가짜 PNG·추가정보 초과 | 400, AI 호출 안 함 | AnalysisFailureHttpTests, AnalyzeApiTests |
| 판독 | AI 설정 누락·인증 실패 | 503, 판정·analyzeId 없음, 재시도 안 함 | AnalysisFailureHttpTests |
| 판독 | AI 할당량 제한 | 429, 재시도·다른 공급자 전환 없음 | AnalysisFailureHttpTests, OpenAiFlowTests |
| 판독 | AI 시간 초과 | 최대 2회 시도 후 504, SAFE 판정 생성 안 함 | AnalysisFailureHttpTests |
| 판독 | AI 상위 서버 오류·잘못된 응답 | 최대 2회 시도 후 502 | AnalysisFailureHttpTests, OpenAiFlowTests |
| 판독 | AI 거절·불완전 출력 | 502, 재시도·SAFE 판정 없음 | AnalysisFailureHttpTests, OpenAiFlowTests |
| 판독 | 읽기 실패 | HTTP 200이지만 readable=false, 새 ID·판정 없음 | AnalyzeApiTests, AnalysisFailureHttpTests |
| 상태 보존 | 이전 성공 뒤 시간 초과·읽기 실패 | 이전 스냅샷 유지, 실패한 입력으로 새 결과 만들지 않음 | AnalysisFailureHttpTests |
| 상태 교체 | 이전 성공 뒤 새 판독 성공 | 이전 ID 404, 최신 결과만 사용 | AnalysisFailureHttpTests |
| 피드백 | 중복·변경 투표·동시 요청 | 중복 409, DB에는 1건 | FeedbackFlowTests |
| 피드백 | 만료·삭제·다른 세션·잘못된 kind | 404 또는 400, 새 피드백 저장 안 함 | FeedbackFlowTests |

## 화면 연동 시 지켜야 할 점

- HTTP 200만으로 성공 판정하지 말고 readable을 먼저 확인한다.
- 실패한 새 캡처에 이전 결과를 붙여 보여 주지 않는다. 이전 스냅샷이 유지되는 것은 서버의 기존 결과 보존 동작이다. 이번 실패에 새 analyzeId가 생긴 것은 아니다.
- 결과의 버튼은 서버가 반환한 showReportButton·showSuspicionReport를 사용한다. 계좌 표시는 마스킹이며 신고 선택은 accountIndex를 사용한다.
- 실패 시 로딩을 해제하고 재시도 안내를 표시한다. 재시도는 중복 클릭을 막고, 429에서 무한 자동 재요청하지 않는다.
- 판독 ID는 세션 소유이며 15분·최신 1건·서버 재시작 등으로 소멸한다. 404이면 재판독을 안내한다.
- 오류 본문은 API에 따라 message 또는 code/message/data 형태가 다를 수 있다. 401·403의 보안 필터 응답도 고려한다.

## 재실행

```bash
bash gradlew test --tests '*AnalysisFailureHttpTests' --tests '*AuthFlowTests' --tests '*AccountFlowTests' --tests '*FeedbackFlowTests' --tests '*AnalyzeApiTests' --tests '*OpenAiFlowTests'
```

이 테스트는 개발 환경의 일관된 실패 처리·재시도 횟수·상태 보존을 확인한다. 실제 AI를 강제로 실패시키거나 공유 DB를 수정해 재현할 필요는 없다. 전체 테스트 중 별도 PostgreSQL 테스트 DB에 의존하는 기존 테스트는 별도 환경에서 실행해야 한다.

## 이번 실행 결과

- 실패 상황 관련 6개 테스트 클래스: 57개 통과.
- 로컬 회귀: 347개 통과, 실패·오류 0. 별도 PostgreSQL TEST_DB 설정·기존 데이터에 의존하는 4개 클래스(27개 테스트)는 제외했다.
- 이번 재현 범위에서 제품 코드 변경이 필요한 오류는 발견하지 않았다. 회귀 검증용 HTTP 실패 테스트 10개와 시연 문서를 추가했다.
- 배포 초안은 기존 stash에 보존했고 트루샷 AI·CV 코드는 수정하지 않았다.
