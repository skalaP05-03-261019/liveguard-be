# LIVE:GUARD Backend

Spring Boot 업무 서버의 기본 골격입니다. Java 21, Gradle Wrapper를 사용합니다.

```bash
./gradlew bootRun
```

현재 채팅·인증·제재·MCP 업무 로직은 미구현이며, 실행 환경 연결도 별도 확인이 필요합니다.
Python Agent는 별도 `liveguard-agent`, 화면은 `liveguard-fe` 저장소에서 개발합니다.

## 공통 문서

[기획서·정책 v0.3](docs/live-guard/v0.3/README.md)를 공통 문서 기준으로 유지합니다.
정책·API 계약 변경은 다른 저장소 담당과 함께 조율합니다.

기존 skala-capstone-P05_03의 커밋 이력은 그대로 보존하며, 저장소 분리 커밋에서 서비스 파일만 재배치합니다.
