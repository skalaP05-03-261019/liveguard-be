# LIVE:GUARD Backend

Spring Boot 업무 서버의 기본 골격입니다. Java 21, Gradle Wrapper를 사용합니다.

## 로컬 실행

JDK 21이 필요합니다.

```bash
./gradlew bootRun
```

기본 포트는 `8080`입니다.

### 로컬 DB (PostgreSQL + pgvector)

Docker가 필요합니다. `.env`는 저장소에 올리지 않으므로 예시 파일을 복사해 비밀번호를 바꿉니다.

```bash
cp .env.example .env        # POSTGRES_PASSWORD 수정
docker compose up -d db     # 호스트 포트 5433 (POSTGRES_PORT로 변경 가능)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로파일(`application-local.yml`)이 `.env`의 `POSTGRES_*` 값으로 DB에 접속합니다.
DB 데이터를 비우려면 `docker compose down -v`를 실행합니다.

앱이 뜨면 Flyway가 `db/migration`(스키마·테이블)과, `local`·`test` 프로파일에서만 `db/seed`(개발용 계정·채널)를 적용합니다.
시드는 운영 배포(기본 프로파일)에는 포함되지 않습니다. 모든 시드 계정의 비밀번호는 `liveguard-local`입니다.

| 계정 | 역할 | 소속 채널 |
| --- | --- | --- |
| `viewer` | VIEWER | 라이브 채널 A, B (시청자) |
| `moderator` | MODERATOR | 라이브 채널 A, B (운영자) |
| `admin` | PLATFORM_ADMIN | 없음 |

테스트(`./gradlew test`)는 Testcontainers가 `pgvector/pgvector:pg16` 컨테이너를 직접 띄우므로
compose 없이 Docker만 실행 중이면 됩니다.

## API 문서 (Swagger)

Spring Boot 4.1에 맞는 `springdoc-openapi-starter-webmvc-ui` 3.1.1을 사용합니다.
서버 실행 후 다음 주소에서 확인할 수 있습니다.

- Swagger UI: http://localhost:8080/swagger-ui/index.html (`/swagger-ui.html`에서도 이동 가능)
- OpenAPI JSON: http://localhost:8080/v3/api-docs

아직 업무 API 컨트롤러가 없어 문서에 표시되는 업무 API는 없습니다.
컨트롤러가 추가되면 자동으로 문서화됩니다.
외부에 배포할 때 API 문서를 공개하지 않으려면
`SPRINGDOC_API_DOCS_ENABLED=false`, `SPRINGDOC_SWAGGER_UI_ENABLED=false`를 설정합니다.

## Docker 실행

Docker 데몬이 실행 중이어야 합니다. Java 21 JDK로 실행 JAR를 빌드한 뒤,
Java 21 JRE 이미지에서 비루트 사용자로 실행하는 멀티스테이지 구성입니다.
호스트에는 JDK가 없어도 됩니다.

```bash
docker build -t liveguard-be:local .
docker run --rm --name liveguard-be -p 8080:8080 liveguard-be:local
```

앱이 기동할 때 Flyway가 DB에 접속하므로 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`도 함께 전달해야 합니다 (위 `docker run`만으로는 기동하지 않습니다).

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
