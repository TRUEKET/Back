# 전체 통합 테스트 실행

## 기본 실행

프로젝트 루트에서 Java 17로 실행한다.

```bash
bash gradlew test
```

IntelliJ에서는 Gradle 새로고침 후 Tasks → verification → test를 실행해도 된다. 앱 실행 구성의 팀 DB 환경변수를 테스트 실행 구성에 복사할 필요는 없다.

- 일반 테스트는 기존 H2 메모리 DB를 사용한다.
- `test` 프로필의 조회·신고·애플리케이션 기동 통합 테스트는 **실제 PostgreSQL 17.11 일회용 프로세스**를 사용한다. `PostgreSqlIntegrationSupport`가 임의 포트로 시작하고 동적 접속 정보를 주입한다.
- Docker·Supabase 테스트 프로젝트·`TEST_DB_*`·운영 비밀번호가 필요 없다.
- Gradle이 최초 실행 때 테스트 라이브러리와 OS별 PostgreSQL 바이너리를 Maven 저장소에서 내려받으므로 인터넷과 디스크 공간이 필요하다. 이후에는 캐시를 재사용한다.
- DB 스키마는 테스트에서만 JPA `create-drop`으로 생성한다. 공유 Supabase에 SQL을 적용하지 않는다. PostgreSQL 서버는 테스트 JVM 종료 시 종료되고 임시 데이터도 정리된다.
- 네이티브 PostgreSQL 프로세스를 실행할 수 있어야 한다. Linux에서는 root가 아닌 일반 사용자로 실행한다. 바이너리 다운로드·실행이 막히면 테스트 로그의 `initdb` 및 다운로드 오류를 확인한다.
- ARM Mac/Linux용 바이너리를 테스트 의존성에 포함했다. 다른 OS/CPU에서 실제 실행한 결과는 별도 확인이 필요하다.

## 외부 서비스 대체 범위

DB는 실제 로컬 PostgreSQL이지만 신고 삭제의 Storage는 `@MockitoBean`으로 대체한다. 공유 버킷 파일을 삭제하거나 메일을 보내지 않는다. AI·Storage의 실제 연동 검증은 이 자동 테스트와 구분하며, 앞선 합성 이미지 실호출 기록은 `analyze-api.md`를 참고한다.

`IdentifierHashDebugTest`는 ID 308 같은 공유 데이터에 의존하지 않는다. 가상 계좌를 정규화·암호화·해시 처리해 직접 저장하고, 저장값 복호화·동일 계좌 검색·다른 계좌 불일치를 검증한 뒤 트랜잭션을 롤백한다.

신고 삭제 롤백 테스트는 일회용 DB에 테스트 전용 FK 제약을 만들고, 신고 삭제가 실패했을 때 신고·증빙·식별자 연결이 전부 보존되는지 확인한다. 제약과 커밋된 테스트 데이터는 finally에서 정리한다. 이 테스트는 Storage 객체의 원격 롤백까지 보장하지 않는다.

## 일부 테스트만 다시 실행

```bash
bash gradlew test --tests '*BackendApplicationTests' --tests '*LookupServiceIntegrationTest' --tests '*ReportServiceIntegrationTest' --tests '*IdentifierHashDebugTest'
```

결과 HTML은 `build/reports/tests/test/index.html`, 상세 XML은 `build/test-results/test/`에 생성된다. `--rerun-tasks`를 붙이면 캐시 없이 다시 실행한다.

## 설정 변경 시 주의

`test` 프로필을 앱 실행용으로 사용하거나 팀 DB URL을 넣지 않는다. PostgreSQL 테스트 접속 정보는 동적 프로퍼티가 공급한다. 실제 팀 스키마·RLS·Storage 권한까지 검증하려면 별도 승인된 환경과 절차가 필요하다. 이번 변경은 배포 설정이나 트루샷 AI·CV 구현을 바꾸지 않는다.

사용 라이브러리: [Zonky Embedded Postgres 공식 문서](https://github.com/zonkyio/embedded-postgres).

## 검증 기록 (#68, 2026-11-16)

ARM Mac·Java 17에서 이전 실패 27개 전부 통과. 이어서 `bash gradlew test --rerun-tasks`로 전체 **387개 통과, 실패·오류·제외 0**을 확인했다. 공유 Supabase·실제 Storage·메일은 호출하지 않았다.
