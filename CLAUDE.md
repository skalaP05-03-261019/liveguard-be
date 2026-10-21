# LIVE:GUARD Backend (liveguard-be)

라이브 채팅 관제 서비스의 Spring Boot 백엔드 (SKALA AI Agent Capstone, 6인). 제출 마감이 임박했으므로 **범위를 늘리지 말고 아래 기준 문서의 범위만 구현한다.**

관련 저장소 (org `skalaP05-03-261019`):
- `liveguard-be`: 이 저장소. Spring Boot, 공통 기획서·정책 문서
- `liveguard-agent`: FastAPI + LangGraph Agent, MCP 서버
- `liveguard-fe`: Vue 3

## 기준 문서 (충돌 시 이 순서)

1. `docs/policy/{PROJECT,POLICY,CONTRACTS,ARCHITECTURE}.md`: 정책 원본 v3.1. **충돌 시 최우선**
2. `docs/policy/TBD-decisions.md`: 원본의 미정 항목 결정
3. `contracts/openapi.yaml`: 브라우저·내부 API 계약 v1.0
4. `docs/erd.md`: 테이블·제약·핵심 쿼리 v1.0
5. `docs/live-guard/v0.3/`: 이전 기획. **구현 기준 아님.** `evaluation_cases.md`만 평가 사례로 참고

- 계약과 다른 필드·경로가 필요하면 코드부터 바꾸지 말고 먼저 알려줄 것.
- springdoc Swagger UI는 구현 확인용이다. 기준은 `contracts/openapi.yaml`이며, 둘이 다르면 코드를 계약에 맞춘다.

## 핵심 결정 (바꾸지 말 것)

- HELD·HIDDEN 원문은 작성자·담당 운영자 외에는 SSE·이력 API 어디에도 보내지 않는다.
- MCP 변경 도구는 사람 승인 `interrupt` 뒤에서만 실행한다.
- `/internal/**`는 서비스 토큰(`X-Service-Token`)으로만 접근한다.
- 그 외 정책·판정 규칙은 `docs/policy/`를 따른다.

## 버전 주의

- Spring Boot 4.x, Java 21, Gradle. **Spring Boot 3.x 예제를 그대로 쓰지 말 것.** 패키지·설정 이름이 다를 수 있으니 `build.gradle`의 실제 의존성과 공식 문서로 확인하고 작성한다.
- 빌드·테스트: `./gradlew build`, `./gradlew test` (레포 루트에서)

## 패키지 경계

```
com.lifeguards.liveguard
├─ common    예외 핸들러, traceId, Clock 빈
├─ contracts 공용 DTO·enum, 응답 envelope(dto.response.ApiResponse). openapi.yaml schemas, ErrorCode 포함
├─ auth      Spring Security 세션 로그인, 역할·채널 권한          (B1)
├─ platform  channels, channel_members, messages, 채팅 API·SSE     (B1)
├─ filter    1차 필터, 정규화                                       (B1)
├─ guard     cases, 판정 처리, 누적 계산, kick_reviews, kicks       (B2)
└─ internal  /internal/** 컨트롤러, 서비스 토큰 필터               (B1·B2)
```

- `platform`과 `guard`는 서로의 클래스를 직접 import하지 않는다. 인터페이스(포트)로만 호출한다.
- 다른 패키지의 테이블을 직접 조회하지 않는다.
- 이미 있는 계약 DTO·enum(BE2-01)은 새로 만들지 말고 재사용한다. 계약과 다르면 먼저 알린다.

## 규칙

- 응답: 성공 `{data, traceId}`, 오류 `{error: {code, message, retryable, ...}, traceId}`. 오류 코드는 `openapi.yaml`의 `ErrorCode` enum만 사용.
- DB: Flyway. 버전 대역 `V1xx` = 공통·platform(B1), `V2xx` = guard(B2). 이미 머지된 마이그레이션 파일은 수정하지 않는다.
- 시간: `Instant.now()` 직접 호출 금지. 주입된 `Clock` 빈을 쓴다 (강퇴 만료 시험용).
- 인증: 세션 쿠키 + `CookieCsrfTokenRepository`. SSE GET은 CSRF 대상 아님.
- `/internal/**`는 `X-Service-Token` 헤더를 검사하고, 브라우저 세션으로는 접근 불가.
- 테스트: 컨트롤러는 MockMvc, DB가 필요한 테스트는 Testcontainers 또는 compose의 Postgres.

## 작업 규칙

- main에 직접 커밋하지 않는다. `feat/b1-...` 같은 기능 브랜치에서 작업하고 PR로 머지한다.
- 한 번에 작은 단위 하나만 구현하고, 테스트를 통과시킨 뒤 커밋한다.
- 테스트·빌드 명령은 파이프 없이 실행하고 종료 코드로 성공을 판단한다. 파이프가 필요하면 `set -o pipefail`.
- 비밀키·토큰을 코드나 커밋에 넣지 않는다. `.env`는 커밋하지 않고 `.env.example`만 유지한다.
- 일반 로그에 채팅 원문을 남기지 않는다.
