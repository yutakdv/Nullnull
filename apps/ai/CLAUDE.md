# apps/ai — Nullnull 추천 계산 서비스

Python 3.13 · uv 0.12.10 고정. Spring(`apps/api`)이 보낸 immutable 입력만 계산하는 순수 서비스다(ADR-0006).
저장소 전체 규칙은 root `CLAUDE.md`를 따른다. 이 경로에는 `.claude/rules/*`가 적용되지 않으므로 이 파일이 정본이다.

## Package 지도

`src/nullnull_ai/` 아래 구조다.

| Package | 담당 |
| --- | --- |
| `api` | 내부 HTTP endpoint(`/internal/v1/...`)와 요청/응답 schema |
| `domain` | 입력 model과 값 객체 |
| `pipeline` | `runner` 실행 순서·단계 조립 |
| `feed` · `item` · `related` · `slot` | 실제 계산(피드 순서, ITEM 개선, 관련 장소, slot 판정) |
| `policy` | 판정 기준과 pin된 정책 값 |
| `explain` | 근거 설명 template |
| `evaluation` | corpus·invariant·fixture와 `evaluation.json` gate |
| `contracts.py` | 계약 JSON export 진입점 |

## 순수성 규칙 (깨면 안 됨)

- DB·외부 API·clock·난수를 쓰지 않는다. 현재 시각이 필요하면 입력으로 받는다.
- owner/session ID·붙여넣기 원문·정밀 좌표를 입력으로 받지 않는다.
- 같은 입력은 같은 출력을 낸다. `tests/test_purity.py`가 이를 검사한다.
- 사실·영업·좌표·경로·혼잡·적용 가능성의 최종 판정자는 이 서비스가 아니다.

## 검증

로컬에는 `uv`가 PATH에 없다. `apps/ai/README.md`의 부트스트랩 venv를 쓴다(CI·Docker에서는 `uv`가 PATH에 있다).

```bash
cd apps/ai
python3.13 -m venv .uv-bootstrap && .uv-bootstrap/bin/pip install uv==0.12.10   # 최초 1회
.uv-bootstrap/bin/uv sync --frozen

B=.uv-bootstrap/bin/uv
$B run ruff check . && $B run ruff format --check . && $B run mypy && $B run pytest
```

`pytest`는 `build/reports/recommendation/evaluation.json`을 만든다. 대안은
`docker build -f apps/ai/Dockerfile --target test`다.

## Gotchas

- endpoint/schema를 바꾸면 반드시 계약 JSON을 다시 export하고 Spring DTO를 맞춘다. 빠뜨리면
  `apps/api`의 `recommendationTest` parity 검사가 실패한다.

  ```bash
  .uv-bootstrap/bin/uv run python -m nullnull_ai.contracts export   # → contracts/recommendation-internal-v1.json
  ```

- 새 REC test ID는 `tests/recommendation/manifest.json`의 `implementedTestIds`와 fixture sha256에 함께 등록한다.
  등록하지 않은 ID는 CI가 실행한 것으로 보지 않는다(`AGENTS.md#ci-검사-등록`).
- skip·0건 실행·report 누락으로 gate를 통과시키지 않는다. 결측값을 0/보통으로 채우지 않는다.
- mypy는 strict다. ruff format 결과는 `--check`로 검증되므로 포맷 후 커밋한다.
