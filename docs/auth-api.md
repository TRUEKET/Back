# 가입·로그인 API

Spring Security 서버 세션을 사용한다. 사용자 식별자는 인증 객체의 name에 문자열 id로 저장된다.
비밀번호는 BCrypt, 전화번호는 AES-256-GCM 암호문·HMAC-SHA256 해시·뒷 4자리로 저장한다.

## 실행 준비

1. Supabase SQL Editor에서 `src/main/resources/db/auth-schema.sql`을 실행한다. 자동 적용은 하지 않는다.
2. 기존 DB 환경변수와 `PHONE_ENCRYPTION_KEY`, `PHONE_HASH_KEY`를 설정한다. 각각 별도로 생성한 32바이트 키의 Base64 문자열을 사용한다. DB와 분리 보관한다. 기존 전화번호 해시와 키·정규화 방식의 호환성을 팀과 맞춰야 한다.
3. 시연에서는 `SPRING_PROFILES_ACTIVE=demo`를 설정한다. 문자를 발송하지 않으며 매번 생성한 무작위 인증번호를 발송 응답의 `demoSms.code`로 확인한다. 고정 코드는 사용하지 않는다. 운영에서는 demo를 사용하지 않고 `SmsSender` 구현을 제공해야 한다.
4. 로컬 HTTP에서는 `SESSION_COOKIE_SECURE=false`를 설정한다. HTTPS 환경의 기본값은 true다.

프런트와 API가 같은 사이트에서 제공되는 구성을 기준으로 한다. 개발 시 프런트 프록시를 사용한다. 다른 출처를 사용할 경우 정확한 프런트 주소에 대한 CORS·쿠키 설정이 추가로 필요하다.

## 호출 순서

모든 요청에 세션 쿠키를 유지한다. 브라우저 fetch에서는 `credentials: 'include'`를 사용한다.
먼저 `GET /auth/csrf`를 호출한다. 응답의 `headerName`을 헤더 이름으로, `token`을 값으로 사용해 POST·PATCH 요청을 보낸다.
가입·로그인·로그아웃 후 CSRF 토큰을 다시 받는다.

| 메서드 | 경로 | 요청·응답 |
| --- | --- | --- |
| GET | /auth/csrf | CSRF token, headerName, parameterName |
| POST | /auth/phone/send | `{ "phone": "010-1234-5678" }` → verificationId, expiresAt, demo 모드에서만 demoSms |
| POST | /auth/phone/verify | `{ "verificationId": 1, "phone": "01012345678", "code": "123456" }` → 200 |
| POST | /auth/signup | `{ "email": "fan@example.com", "password": "password123", "phone": "01012345678", "verificationId": 1, "privacyConsent": true }` → 자동 로그인, id·email·phoneLast4 |
| POST | /auth/login | `{ "email": "fan@example.com", "password": "password123" }` → 로그인, id·email·phoneLast4 |
| PATCH | /me/password | `{ "currentPassword": "old12345", "newPassword": "new12345", "confirmPassword": "new12345" }` → 완료 메시지 |
| GET | /me/account | 계정 수정용 id·email·전체 phone. Cache-Control no-store |
| POST | /me/phone/send | `{ "phone": "010-9876-5432" }` → verificationId, expiresAt, demo 모드에서만 demoSms |
| POST | /me/phone/verify | `{ "phone": "01098765432", "verificationId": 2, "code": "123456" }` → 인증 완료 메시지 |
| PATCH | /me/phone | `{ "phone": "01098765432", "verificationId": 2 }` → id·email·갱신된 phoneLast4 |
| GET | /me | 로그인 사용자의 id·email·phoneLast4 |
| POST | /auth/logout | 세션 무효화, 204 |

인증 발송·확인·가입은 같은 브라우저 세션에서 수행해야 한다. 다른 세션의 인증 결과는 사용할 수 없다.
인증은 3분 유효하고 재전송은 60초 간격이다. 오입력 5회 후 재전송이 필요하다. 재전송은 기존 코드를 대체한다.
가입은 서버에 저장된 인증 완료·전화번호 일치·유효기간·미사용 여부를 확인한다. 중복 가입은 DB 고유 제약으로도 차단한다.
인증 완료 후 가입까지 3분 유효기간을 적용한다. 화면의 다시 인증하기는 입력을 초기화하고 새 코드 요청으로 이어진다.
오류 응답은 `{ "message": "..." }`다. 인증·인가 필터의 401·403은 HTTP 상태로 처리한다.
로그인·가입 성공 후 홈 또는 원래 화면으로 이동하는 처리는 프런트가 보관한 내부 경로를 사용한다.
만 14세 확인은 디자인에 있지만 최신 화면별 기능표의 가입 필수항목에는 없어 이번 API에서 별도 필드로 요구하지 않는다.

## 검증

`bash gradlew test --tests 'com.trueket.backend.user.AuthFlowTests'`

H2 테스트 DB에서 인증 필수, 오입력 제한, 재발송 간격, 만료, 동의 필수, 인증 재사용 차단, 비밀번호 검증, 자동 로그인·세션 유지·로그아웃·CSRF를 확인한다.
실제 Supabase와 문자 업체 연동은 별도 환경 검증이 필요하다.

## 내 계정 조회·번호 변경

기존 팀 DB에는 `src/main/resources/db/phone-change-schema.sql`도 적용한다. 기존 목적 SIGNUP·REPORT를 유지하고 `PHONE_CHANGE:<사용자 id>`를 허용한다. 목적 길이는 40자로 확장하고 번호·목적 중복 방지 인덱스를 만든다.

홈·모달은 `/me`의 뒷 4자리를 사용하고, 전체 번호는 수정 화면 진입 시 `/me/account`로 조회한다. 비밀번호·해시·암호문은 반환하지 않는다. 기존 테스트 데이터의 임의 암호문은 복호화할 수 없으므로 가입 API로 만든 계정을 사용한다.

번호 변경은 로그인 상태에서 send → verify → PATCH 순서로 호출한다. 인증 확인만으로 번호는 바뀌지 않고 저장하기에서 PATCH를 호출해야 한다. 가입과 같은 demo 방식·3분 만료·60초 재발송·5회 오입력 제한을 사용한다. 실제 SMS 연동은 후속 범위다.

인증은 로그인 사용자와 브라우저 세션에 연결되고 가입 인증과 분리된다. 다른 브라우저에서 재발급되면 이전 인증 결과도 무효가 된다. 저장 시 다른 계정의 번호인지 다시 검사하고, 성공한 인증은 재사용할 수 없다. 같은 번호의 다른 사용자 동시 저장은 users.phone_hash 고유 제약으로도 보호한다.

저장 응답의 phoneLast4로 홈 헤더를 갱신하거나 `/me`를 다시 조회한다. 기존 신고의 reporter_hash는 신고 당시 값이므로 번호 변경에서 일괄 수정하지 않는다.

Supabase 트랜잭션 풀러(6543)의 JDBC 연결은 `prepareThreshold=0`을 적용한다. 서버 측 prepared statement 충돌 방지를 위한 공식 권장 설정이다. [Supabase 연결 안내](https://supabase.com/docs/guides/database/connecting-to-postgres)


## 무작위 코드와 시연용 문자 수신함

`SPRING_PROFILES_ACTIVE=demo`에서 `http://localhost:8080/auth/demo`를 열면 같은 출처의 인증 API를 사용하는 테스트 화면이 열린다. 배포 시에는 백엔드 공개 URL의 `/auth/demo` 경로를 안내한다. demo 이외 프로필에는 이 컨트롤러가 등록되지 않는다. 실제 서비스 디자인을 대신하는 화면이 아니라 검증용 화면이며, 정식 프런트의 수신함 표시는 별도 연동해야 한다.

가입과 번호 변경 발송 응답은 기존 verificationId·expiresAt에 아래 필드가 추가된다. 숫자는 응답 예시이며 고정 코드가 아니다.

```json
{
  "verificationId": 1,
  "expiresAt": "2026-11-13T10:03:00+09:00",
  "demoSms": {
    "maskedPhone": "010-****-5678",
    "code": "482193",
    "message": "시연용 문자 수신함입니다. 실제 문자는 발송되지 않으며 휴대폰 소유 여부를 확인하지 않습니다."
  }
}
```

프런트는 `demoSms`가 있을 때만 별도 ‘시연용 문자 수신함’을 표시한다. 코드 자동 입력·자동 인증 대신 사용자가 수신함의 코드를 직접 입력하게 한다. 새 발급 성공 시 기존 코드를 교체하고, 번호 수정·화면 이탈·가입 완료 시 화면의 코드를 지운다. 서버는 코드 평문을 DB·로그에 남기지 않으며 발송 응답에 `Cache-Control: no-store`를 적용한다. `demoSms`는 demo 이외 모드에서는 null이며 응답 JSON에서 제외한다. 실제 문자 연동 모드에서는 SmsSender가 코드를 발송하고 클라이언트에는 코드를 돌려주지 않는다.

### 심사위원 확인 순서

1. 시연 URL의 `/auth/demo`에 접속한다. 실제 휴대폰 소유 여부를 검증하는 환경이 아님을 확인한다.
2. 가입되지 않은 테스트용 전화번호로 인증번호 받기를 누른다.
3. 수신함과 다른 숫자 6자리를 입력하면 서버의 불일치 응답을 확인할 수 있다.
4. 발급된 번호를 입력하면 인증 성공한다. 이메일·비밀번호·동의를 입력하고 가입하면 서버 세션이 발급된다. 이 페이지의 가입도 실제 테스트 DB에 계정을 만든다.
5. 같은 번호로 60초 이내 재요청하면 제한되고, 60초 후 재발급하면 이전 코드가 무효가 된다. 3분 만료와 5회 오입력 제한도 실제 서버 규칙으로 검사한다. 재발급 코드는 직전 코드와 다르게 생성한다.

### 기획 자료 설명 문구

문자 발송 비용과 발신번호 등록 절차 때문에 외부 SMS 발송을 시연용 수신함으로 대체했습니다. 서버의 SecureRandom 기반 6자리 생성, HMAC 해시 검증, 3분 유효시간, 60초 재발급 제한, 5회 오입력 제한, 인증 결과에 따른 회원가입 및 번호 변경은 실제로 동작합니다. 실제 휴대폰 소유 확인을 제공하지 않으며 실제 SMS 연동 시 발송 모듈을 교체합니다.
