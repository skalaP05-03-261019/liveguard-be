# LIVE:GUARD 저장소 구성

organization: [skalaP05-03-261019](https://github.com/skalaP05-03-261019)

세 저장소는 모두 Private로 관리한다.

| 저장소 | 내용 |
|---|---|
| [liveguard-be](https://github.com/skalaP05-03-261019/liveguard-be) | 기존 저장소를 transfer. Spring Boot와 공통 기획서·정책 |
| [liveguard-agent](https://github.com/skalaP05-03-261019/liveguard-agent) | 기존 agent/의 변경 이력을 추출한 Python Agent 골격 |
| [liveguard-fe](https://github.com/skalaP05-03-261019/liveguard-fe) | Frontend 개발 시작 위치. 이전 원본 main에는 FE 코드 없음 |

## 공통 기준

기획서·정책의 기준 위치는 [docs/live-guard/v0.3](live-guard/v0.3/README.md)다.
분리는 배치 변경이며 정책이나 구현 완료 상태를 변경하지 않는다.
API·도구·정책 버전을 변경할 때 Backend·Agent·Frontend 담당이 함께 검토한다.

## 기존 이력 보존

분리 전 main: `10a8e5d6ba6bbc55472e78a6eea4fe7952499add`.
BE 저장소는 기존 `taetegwgn/skala-capstone-P05_03`의 저장소 자체를 이전하므로 기존 전체 커밋 이력을 유지한다.
이후 커밋에서 liveguard/의 파일을 루트로 이동하고 agent/는 별도 저장소로 분리한다.
Agent 저장소는 git subtree split으로 관련 경로의 이력을 추출했다. 추출 과정에서 커밋 SHA는 달라진다.
원본 시점의 파일 내용은 README의 독립 실행 경로 수정 외에는 유지했다.

## 로컬 작업 위치

서비스별로 새 작업 폴더를 만드는 방법:

```bash
git clone https://github.com/skalaP05-03-261019/liveguard-be.git
git clone https://github.com/skalaP05-03-261019/liveguard-agent.git
git clone https://github.com/skalaP05-03-261019/liveguard-fe.git
```

기존 전체 저장소의 clone을 BE 작업에 계속 사용할 경우 origin을 다음 주소로 바꾼다.
진행 중인 변경을 먼저 보존한 후 main을 갱신한다. Agent 작업은 별도 Agent 저장소를 clone해 진행한다.

```bash
git remote set-url origin https://github.com/skalaP05-03-261019/liveguard-be.git
```

새 저장소의 팀원 접근 권한은 organization 또는 저장소별로 관리한다.
