# apps/ai — Nullnull 추천 계산 서비스

Python 3.13 · uv 0.12.10 고정. Spring(`apps/api`)이 보낸 immutable 입력으로 **계산 package가 순수하게** 판정한다(ADR-0006).
DB·clock·난수는 **어느 package에도 없고**, 외부 호출은 경계 package 셋에만 있다 — `api`(전송), `evaluation`(fixture·report),
그리고 `AI_PROVIDER != NONE`에서 모델을 호출하는 `provider`. 서비스 전체를 *"외부 API를 쓰지 않는다"* 로 적지 않는다.
저장소 전체 규칙은 root `CLAUDE.md`를 따른다. 이 경로에는 `.claude/rules/*`가 적용되지 않으므로 이 파일이 정본이다.

## Package 지도

`src/nullnull_ai/` 아래 구조다.

| Package | 담당 |
| --- | --- |
| `api` | 내부 HTTP endpoint(`/internal/v1/...`)와 요청/응답 schema |
| `domain` | 입력 model과 값 객체 |
| `pipeline` | `runner` 실행 순서·단계 조립 |
| `feed` · `item` · `related` · `slot` | 실제 계산(피드 순서, ITEM 개선, 관련 장소, slot 판정). `related`는 P0 운영 경로에서 호출되지 않는다 — 관련 장소 정렬은 Spring이 한다(ADR-0006 · 예외) |
| `policy` | 판정 기준과 pin된 정책 값 |
| `explain` | 근거 설명 template |
| `evaluation` | corpus·invariant·fixture와 `evaluation.json` gate |
| `contracts.py` | 계약 JSON export 진입점 |
| `main.py` · `settings.py` | 서비스 진입점과 환경 설정 |

## 순수성 규칙 (깨면 안 됨)

- **decision package**(`domain`·`item`·`slot`·`related`·`explain`·`feed`·`pipeline`·`draft`·`preference`)는
  DB·외부 API·clock·난수를 쓰지 않는다. 현재 시각이 필요하면 입력으로 받는다.
- **I/O는 경계 package에만 있다.** `api`(전송), `evaluation`(fixture·report), 그리고 `AI_PROVIDER != NONE`에서
  모델을 호출하는 `provider`가 전부다. 이 셋은 `tests/test_purity.py`의 `IMPURE_PACKAGES`에 **사유와 함께**
  등록돼 있고, 등록되지 않은 package는 `test_every_package_under_src_is_classified_as_pure_or_exempt`가
  그 자리에서 거절한다.
- **그 예외가 번지지 않는 이유는 선언이 아니라 장치다.** `DECISION_PACKAGES`는 `PURE_PACKAGES`와 따로 적힌
  두 번째 리터럴이고 `DECISION_PACKAGES <= PURE_PACKAGES`가 단언된다. 그래서 adapter를 `explain`으로 옮기려면
  (a) `explain`을 impure로 재선언해야 하는데 그 단언이 막고, (b) 그대로 두면 AST scan이 import에서 실패한다.
  **두 길이 다 빨갛다** — (b)를 재는 것은 `test_the_scan_sees_io_and_not_only_randomness_and_the_clock`이다.
  **그 test가 생기기 전까지 이 문장은 거짓이었다.** scan의 금지 목록이 `random`·`requests`·`httpx`·`sqlalchemy`
  넷뿐이라 `urllib`·`socket`·`subprocess`·`open()`·`pathlib`이 전부 통과했고, **`urllib`은 이 adapter가 쓰는
  바로 그 module이다.** 즉 (b)는 정확히 이 adapter에 대해 발화하지 않았다. 셀 수 있는 주장을 적을 때는
  무엇이 그것을 재는지 같이 적는다 — 그 이름이 없으면 다음 사람이 이 문장을 확인할 방법이 없다.
- **(a)(b)가 막는 것은 *옮기기*이고, *도달*은 다른 절이다.** 위 두 길은 adapter 파일이 decision package **안으로
  들어올 때** 빨개진다. decision package가 adapter를 **제자리에 둔 채 import**하는 것은 둘 중 어느 것도 보지
  않았다 — `FORBIDDEN_MODULES`는 third-party·stdlib 이름이고 `nullnull_ai.provider.openai`는 그 어느 쪽도
  아니며, I/O는 adapter 안에 남아 importer의 tree에는 금지된 것이 나타나지 않는다. **측정(2026-09-20)**:
  `explain/service.py`에 그 import 한 줄을 넣으면 572개가 전부 초록이었고, 같은 파일에 `import urllib.request`를
  넣으면 `test_module_is_free_of_clocks_randomness_environment_and_io[explain/service.py]`가 반경 1로 빨갰다 —
  scan은 그 파일을 덮고 있었고 없던 것은 **규칙**이다. 지금 그 절을 재는 것은
  `test_module_does_not_reach_into_a_boundary_package`이고, 심은 위반(`adapter from`·`adapter import`·
  `adapter relative`·`transport`·`evaluation`)과 허용 방향 대조가 같이 있다. `BA-084-T6`이 묻는 것이 이쪽이다.
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
  등록하지 않은 ID는 CI가 실행한 것으로 보지 않는다([AGENTS.md의 CI 검사 등록](../../AGENTS.md#ci-검사-등록)).
- skip·0건 실행·report 누락으로 gate를 통과시키지 않는다. 결측값을 0/보통으로 채우지 않는다.
- mypy는 strict다. ruff format 결과는 `--check`로 검증되므로 포맷 후 커밋한다.
