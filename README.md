# LIVE:GUARD Backend

Spring Boot 업무 서버의 기본 골격입니다. Java 21, Gradle Wrapper를 사용합니다.

## 로컬 실행

JDK 21이 필요합니다.

```bash
./gradlew bootRun
```

기본 포트는 `8080`입니다.

## API 문서 (Swagger)

Spring Boot 4.1에 맞는 `springdoc-openapi-starter-webmvc-ui` 3.1.1을 사용합니다.
서버 실행 후 다음 주소에서 확인할 수 있습니다.

- Swagger UI: http://localhost:8080/swagger-ui/index.html (`/swagger-ui.html`에서도 이동 가능)
- OpenAPI JSON: http://localhost:8080/v3/api-docs

`Business MCP Backend` 태그에서 Agent용 내부 REST 계약을 확인할 수 있습니다.
외부에 배포할 때 API 문서를 공개하지 않으려면
`SPRINGDOC_API_DOCS_ENABLED=false`, `SPRINGDOC_SWAGGER_UI_ENABLED=false`를 설정합니다.

## Agent Business MCP REST 계약

인접한 `../liveguard-agent/app/business_mcp/tools.py` 및 `backend.py`의 실제 호출에 맞춘
**계약 스텁**입니다. 정상 형식 요청도 HTTP `501`과
`{error:{code:"NOT_IMPLEMENTED",message:...,retryable:false},traceId:...}`를 반환합니다.
조회 결과를 꾸며내거나 제재·재심 조치를 실행하지 않습니다.
`X-Trace-Id`에는 UUID를 전달하며, 생략하면 오류 응답에 새 UUID를 생성합니다.

| Agent tool | Backend REST |
| --- | --- |
| `get_moderation_case` | `GET /internal/cases/{caseId}/context?view=moderationCase` |
| `get_chat_context` | `GET /internal/cases/{caseId}/context?view=chatContext&before=5&after=5` |
| `get_stream_info` | `GET /internal/cases/{caseId}/context?view=streamInfo` |
| `get_official_links` | `GET /internal/cases/{caseId}/context?view=officialLinks` |
| `get_security_facts` | `GET /internal/cases/{caseId}/context?view=securityFacts` |
| `get_user_history` | `GET /internal/cases/{caseId}/history?days=30` |
| `get_action_status` | `GET /internal/actions/{actionId}` |
| `hide_message`, `timeout_user`, `release_message`, `kick_user` | `POST /internal/actions/{actionId}/execute` |
| `reconcile_review` | `POST /internal/cases/{caseId}/reviews/{decisionId}/reconcile` |

실행 요청 본문은 Agent의 `ActionCommand` JSON입니다. Agent 전용 `BusinessActionCommand`로
`KICK`, 승인 `decisionId`, 서로 다른 위반 참조 `violationIds` 3개 이상을 포함한 계약을 정의하며,
기존 업무 `ActionCommand`의 `RECONCILE_REVIEW` 계약은 변경하지 않습니다.
재심 요청 본문은 `{caseId,decisionId}`이며 경로의 값과 같아야 합니다.

기획서 v0.3과 달리 Agent는 조회를 `context?view=...`로 통합하고 `/history`를 사용합니다.
조회 범위는 기존 Backend 정책을 유지합니다. `before/after`는 각각 0~10,
`days`는 1~30이며 Agent에서 더 큰 값을 허용하더라도 Backend는 거부합니다.
내부 API 인증·권한 검사도 아직 미구현이므로 `/internal/**`를 외부에 공개하지 마세요.
실제 업무 구현 전에 서비스 토큰 검증과 권한·버전·멱등성 검증을 연결해야 합니다.

## Docker 실행

Docker 데몬이 실행 중이어야 합니다. Java 21 JDK로 실행 JAR를 빌드한 뒤,
Java 21 JRE 이미지에서 비루트 사용자로 실행하는 멀티스테이지 구성입니다.
호스트에는 JDK가 없어도 됩니다.

```bash
docker build -t liveguard-be:local .
docker run --rm --name liveguard-be -p 8080:8080 liveguard-be:local
```

Swagger UI 주소는 로컬 실행과 같습니다. Spring Boot 설정은 `-e`로 전달할 수 있습니다.
로컬 환경 파일을 사용하는 경우 아래와 같이 실행합니다.

```bash
docker run --rm --name liveguard-be -p 8080:8080 --env-file .env liveguard-be:local
```

`.env`는 필요한 경우 직접 생성하며, 비밀 값은 저장소나 이미지에 포함하지 않습니다.
컨테이너에서 외부 서비스에 연결할 때 `localhost`는 호스트가 아닌 컨테이너 자신을 가리킵니다.
Docker Desktop의 호스트 서비스를 이용하려면 `host.docker.internal`을 사용하세요.

## 검증

```bash
./gradlew test bootJar
```

Docker 이미지 빌드는 테스트를 실행하지 않으므로 위 명령으로 별도 검증합니다.

현재 채팅·인증·제재·MCP 업무 로직은 미구현이며, 실행 환경 연결도 별도 확인이 필요합니다.
Python Agent는 [liveguard-agent](https://github.com/skalaP05-03-261019/liveguard-agent), 화면은 [liveguard-fe](https://github.com/skalaP05-03-261019/liveguard-fe) 저장소에서 개발합니다.

## 공통 문서

[기획서·정책 v0.3](docs/live-guard/v0.3/README.md)를 공통 문서 기준으로 유지합니다.
정책·API 계약 변경은 다른 저장소 담당과 함께 조율합니다.

기존 skala-capstone-P05_03의 커밋 이력은 그대로 보존하며, 저장소 분리 커밋에서 서비스 파일만 재배치합니다.

[저장소 구성·이전 안내](docs/repository-layout.md)를 확인하세요.
