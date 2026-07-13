# 인수인계 — 혼잡·분산·API 활용 보완

**작성일:** 2026-07-13 · **브랜치:** `feature/backend_ver2` · **인계 시점 HEAD:** `07bc7f8`

이 문서 하나로 다른 팀원이 이어서 작업할 수 있습니다. 맨 아래 **[이어받기 프롬프트]** 를 Claude Code에 그대로 붙여 넣으면 됩니다.

> **인계받는 팀원 환경:** superpowers 플러그인 **없음**, **Fable 5** 사용.
> 그래서 Phase F를 만들 때 쓴 subagent-driven 오케스트레이션(서브에이전트 디스패치·리뷰어·`review-package` 스크립트 등)은 **쓰지 않습니다.** 대신 **본인이 직접 태스크별 TDD로 실행 + 셀프리뷰 + 전체 테스트로 회귀 가드** 하는 방식으로 진행합니다(§4). 필요한 건 전부 리포지토리 안에 있습니다(플랜·스펙·이 문서). effort 지침은 §4에 확정해 뒀습니다.

---

## 1. 지금 어디까지 됐나 (Phase F 완료)

플랜 `docs/superpowers/plans/2026-07-13-congestion-dispersion-api-enhancement.md`.
사용자 지시 순서: **Phase F 먼저 → WS-B(B) → 의존성 순서(A ‖ C → D → E)**.

### ✅ 완료: Phase F (Leaflet → 정적 SVG 지도)

Safari에서 Leaflet 지도가 단색 블록으로 깨지던 합성 버그를, Leaflet 제거 + GeoJSON 인라인 SVG 렌더로 원천 해결. 커밋 (`79bc4f3..442182d`):

| 커밋 | 내용 |
|------|------|
| `9c26d4d` | feat(map): GeoJSON 인라인 SVG 컴포넌트 `PointsMap` 추가 |
| `d3d8533` | fix(map): 자치구 컬링을 바운딩박스 교차 판정으로 교정 (리뷰 지적) |
| `615fdb8` | refactor(map): main.jsx에서 Leaflet 제거·`PointsMap` 연결 |
| `13086fb` | style(map): SVG 지도 스타일 + leaflet 잔여/dead-code 정리 |
| `442182d` | fix(map): 핀 라벨/맵 박스모델 특이도 교정·죽은 shimmer 제거 (리뷰 Critical) |

- `cd nullnull-travel-webapp && npx vite build` **성공**. `leaflet` 의존성(package.json+lock) 제거 완료.

### ⚠️ Phase F에 남은 유일한 수동 확인 (사람만 가능)
플랜 §13 검증: **Safari(localhost:3000)에서 코스/대안 지도가 자치구 + 마커 + 경로 SVG로 정상 표시(단색 블록 없음)** 육안 확인. 개발서버 띄우고(`cd nullnull-travel-webapp && npm run dev`) Safari로 대안/코스 화면 지도를 눈으로 확인해 주세요.

### ⏳ 미착수: Phase B, A, C, D, E (백엔드 중심)
각 태스크 전체 스펙은 **플랜 파일**에 코드·테스트까지 들어 있습니다.

```
B1 normalize_name → B2 SpotExternalRef 모델 → B3 nearest_spot/resolve_spot
   → B4 서울 area 매핑 시드 → B5 배치 매칭 resolve_spot 전환
A1 서울 실시간 필드 확장 → A2 refined_score → A3 area_key 조회/커버리지
   → A4 오버투어리즘 지수 → A5 대안 top-N 실시간 blend → A6 FE 쏠림 배지
C1 RegionStatDaily 시군구+인덱스 마이그레이션 → C2 region_for → C3 배치 시군구 → C4 시드 시군구
D1 detailIntro2 운영정보 → D2 enrich+휴무 플래그 → D3 기상 다변수
E1 노출 로그 원지/대안 혼잡차 → E2 분산 리프트 지표
그리고 → 최종 전체 검증(pytest + vite build) → PR/머지
```
의존성: A와 C는 B 이후 서로 독립(순차 실행). D는 A·C 이후, E는 D 이후.

---

## 2. 시작 전에 알아야 할 환경/함정 (실측 확인됨)

- **테스트 DB는 루트 `nullnull.db`가 아니라 `tests/test_nullnull.db`.** conftest의 `client`(session-scoped) 픽스처가 세션마다 이 파일을 지우고 다시 만들어, `create_all`이 **신규 테이블·컬럼을 포함한 전체 스키마**를 새로 만듭니다. → B2(SpotExternalRef 테이블), C1/D1/E1(신규 컬럼)은 테스트에서 파일 수동삭제 불필요. pytest가 루트 `nullnull.db`를 건드리지 않습니다.
- **루트 `nullnull.db`에 커밋 안 된 변경이 있습니다(로컬 데이터 churn). 절대 커밋에 쓸어담지 마세요.** 플랜의 여러 태스크가 `git add -A`를 쓰는데, 이걸 **명시적 파일 스코프 add로 바꿔서** 커밋하세요(예: `git add app/services/congestion_service.py app/schemas.py tests/test_overtourism.py`). Phase F 내내 이렇게 처리했습니다.
- conftest 픽스처: `db`, `client`, `gyeongbok_id`. `DEMO_MODE=true` 고정 → 실시간/배치 HTTP는 None 경로.
- **의존성 존재 확인됨(실측):** `app/external/seoul_api.py`에 `CONGEST_LEVEL_SCORE`·`_fcst_hour`·`get_realtime_congestion`·`SPOT_TO_AREA` 있음(A1/A3 안전). B4가 매핑하는 서울 area→스팟명 10개(경복궁/창덕궁/덕수궁/북촌한옥마을/명동거리/N서울타워/홍대거리/익선동 골목/낙산공원/서울숲) 모두 시드에 존재(≥10 통과 가능). `app/geo.py::haversine_km` 있음(B3).
- **커밋 트레일러:** 팀원 환경 기본 규칙을 따르세요(참고: Phase F 커밋은 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` 사용).
- 작업 브랜치는 이미 `feature/backend_ver2` (main 아님) — 격리 OK.
- (선택) `docs/superpowers/tools/task-brief-labeled.sh PLAN B1 OUT.md` — 플랜에서 한 태스크 절만 뽑아내는 순수 bash 헬퍼(플러그인 불필요). 단독 실행이면 굳이 안 써도 되고 플랜을 바로 읽어도 됩니다.

## 3. 절대 지켜야 할 Global Constraints (플랜 발췌)
- **회귀 0:** `weights.yaml`의 `congestion_risk` 가중치 불변. 신규 신호는 결측 시 `renormalize`로 흡수. **기존 테스트 전량 통과 유지**(사전 존재 OpenAI 관련 3건 실패는 회귀 아님 — 그 외 0).
- **쿼터 1000/일:** 요청 경로 서울 실시간 HTTP는 원 관광지 + 대안 top-N(≤5)만. 배치에 서울 실시간 넣지 않음.
- **demo/오프라인 폴백:** `DEMO_MODE=true`거나 키 없으면 실시간 None → 시드/휴리스틱으로 동작.
- **정직성:** 실시간이 아닌 소스일 때 오버투어리즘/실시간 인원 필드는 전부 None.
- **마이그레이션 순서:** `create_all` → `apply_column_migrations` → (신규)`apply_index_migrations` → seed.

---

## 4. 이어받는 방법 — superpowers 없이 Fable 5 단독 실행

subagent 오케스트레이션 대신 **본인이 직접 태스크별 TDD**로 실행합니다. 리뷰어 안전망이 없으니 태스크마다 스스로 diff를 스펙과 대조하고 전체 테스트로 회귀를 막으세요. 진실의 원천은 **git log와 이 문서**(`git log --oneline 79bc4f3..HEAD`에 Phase F 5커밋이 보이면 정상 — Phase F는 다시 하지 않음).

**태스크마다:**
1. 플랜에서 해당 Task 절을 읽는다(구현 코드·테스트가 그대로 있음).
2. **RED**: 실패 테스트 먼저 작성 → 실행해 실패 확인.
3. **GREEN**: 구현 → 해당 포커스 테스트 통과.
4. **회귀 가드**: 커밋 전 `python -m pytest -q` 전체 실행 — 신규 통과 + (사전 존재 OpenAI 3건 외) 실패 0 확인. FE 태스크(A6)는 `cd nullnull-travel-webapp && npx vite build` 성공 확인.
5. **셀프 리뷰 후 스코프 커밋**: diff를 스펙과 대조(빠진 것/과한 것/오해). `git add <명시적 파일들>` **만**(절대 `git add -A` 금지).
6. 전 태스크 완료 후: 전체 `pytest -q` + `vite build` 최종 확인 → PR/머지.

**스펙 §참조 태스크(중요):** C1(§9.2 `apply_index_migrations` 코드), A5(§5.5), D1(§7.1), C3(`SEOUL_SIGNGU_CODES`)는 설계 스펙 `docs/superpowers/specs/2026-07-13-congestion-dispersion-api-enhancement-design.md`의 해당 절 코드를 함께 읽고 구현.

**Phase F 교훈(반복 방지):** ① 기존 코드/CSS와의 상호작용을 스스로 의심해 확인하면 실제 결함을 잡습니다(핀 라벨이 기존 `.route-map text`에 덮여 green-on-green invisible이던 것). ② **플랜 예시 코드에도 버그가 있을 수 있습니다**(F1 자치구 컬링) — 코드를 그대로 옮기되 경계/특이도/폴백을 검증하세요.

### Fable 5 effort — 이렇게 쓰세요 (결정)

리뷰어 안전망이 없고 회귀 0·마이그레이션 제약이 있어, **기본 `high`, 아래 태스크는 `xhigh`로 올립니다.**

- **`xhigh`** — 스키마/마이그레이션·다파일 콜플로우 배선·스코어링/집계 알고리즘 (실수 비용 큼):
  **B3**(ref→이름→좌표 폴백 + upsert), **B5**(배치 sync_concentration/sync_related 재배선 + 매칭율 로깅), **A2**(밴드 내 보간·클램프·범위0 엣지), **A3**(area_key + get_realtime_by_area 2파일 리팩터·하위호환), **A5**(실시간 blend 후 decrease_pct/relief 재계산·쿼터), **C1**(시군구 컬럼 + UniqueConstraint 변경 + `apply_index_migrations` + lifespan 순서), **C2**(region_for 시군구 우선 + bulk_risks 맵 재작성·변별력·회귀0), **D2**(enrich 통합 + `is_closed_on` 요일 파싱 + 응답 배선), **D3**(weather_fit 확장 — 스코어링 회귀 위험), **E2**(dispersion_lift 집계 — 전환율·실현 감소율·is_seed 제외·콜드스타트).
- **`high`** — 플랜에 완전 코드 있고 단일 파일 추가/테스트 위주:
  **B1, B2, B4, A1, A4, A6, C3, C4, D1, E1**.
- **`low`/`medium`은 쓰지 마세요** — 전사 태스크도 `run()` 등록·리셋목록·마이그레이션·기존 테스트 유지가 얽혀 medium은 얕습니다.

> effort가 가장 큰 레버입니다. 어려운 태스크에서 `high`로 버티지 말고 `xhigh`로 올리세요. 반대로 xhigh 목록 밖 태스크에 xhigh를 남발할 필요는 없습니다.

---

## 5. [이어받기 프롬프트] — Claude Code(Fable 5)에 그대로 붙여넣기

```
docs/superpowers/plans/2026-07-13-congestion-dispersion-api-enhancement.md 플랜을 이어서 구현해줘.
나는 superpowers 플러그인이 없어. subagent 오케스트레이션 말고, 네가 직접 태스크별 TDD로 진행해.

먼저 docs/superpowers/HANDOFF-2026-07-13-congestion-dispersion.md 를 읽어.
Phase F(F1~F3, SVG 지도)는 이미 완료·커밋됨(git log 79bc4f3..HEAD 의 5커밋) — 다시 하지 마.
남은 건 Safari 육안 검증(사람) 하나뿐. 너는 WS-B의 Task B1(normalize_name)부터
의존성 순서(B1→B5, A1→A6, C1→C4, D1→D3, E1→E2)로 진행하고 마지막에 전체 검증해줘.

태스크마다:
- 플랜의 해당 Task 절을 읽고 → 실패 테스트 먼저(RED) → 구현(GREEN) →
  커밋 전 python -m pytest -q 전체로 회귀 0 확인(사전 존재 OpenAI 3건 외 실패 없어야 함).
  FE 태스크(A6)는 cd nullnull-travel-webapp && npx vite build 성공 확인.
- 커밋은 명시적 파일 스코프 add만. 절대 git add -A 금지(루트 nullnull.db 로컬 변경이 있음).
- 스펙 참조 태스크(C1 §9.2, A5 §5.5, D1 §7.1, C3 SEOUL_SIGNGU_CODES)는
  docs/superpowers/specs/2026-07-13-congestion-dispersion-api-enhancement-design.md 해당 절도 읽고 구현.
- 테스트는 tests/test_nullnull.db(세션마다 새로 생성)라 스키마 변경 태스크(B2/C1/D1/E1) DB 수동삭제 불필요.
- 회귀 0(congestion_risk 가중치 불변), demo 폴백,
  마이그레이션 순서(create_all→apply_column_migrations→apply_index_migrations→seed) 준수.

effort: 기본 high. 다음 태스크는 xhigh로 올려 —
  B3, B5, A2, A3, A5, C1, C2, D2, D3, E2 (마이그레이션·다파일 배선·스코어링/집계 알고리즘).
  나머지(B1, B2, B4, A1, A4, A6, C3, C4, D1, E1)는 high. low/medium은 쓰지 마.
  (하드한 태스크에서 high로 버티지 말고 xhigh로 올릴 것.)

git log로 Phase F 5커밋 확인하고, 남은 태스크 todo를 만든 뒤 B1부터 시작해줘.
```
