# Nullnull 작업 지침

이 저장소는 오버투어리즘 완화를 위한 여행 플래너 **널널(Nullnull)**의 작업공간이다. Frontend 1명과 Backend/AI 1명이 계약을 공유한다. 최신 Figma와 `docs/`의 계약을 운영 목표의 기준으로 삼으며 현재 목표 서비스에 속하지 않는 과거 프로토타입은 저장소에 다시 섞지 않는다.

공모전 자격·마감·제출·필수 데이터 규칙은 최신 공식 공지, Figma는 시각·화면·문구, OpenAPI/이벤트/ERD는 상태 변화·데이터 의미의 정본이다. 충돌은 임의 우선순위로 덮지 말고 같은 기능 ID에서 함께 해결한다. 자세한 지도는 `docs/README.md`, Claude Code 규칙은 `CLAUDE.md`, 역할은 `docs/engineering/OWNERSHIP_MATRIX.md`를 따른다.

## 작업 원칙

1. 한 번에 전면 교체하지 않는다. 계약이 확정된 vertical slice 단위로 새 목표 stack에 옮긴다.
2. 추천 정확성·데이터 출처·인증·피드백 무결성 등 P0를 시각적 개선이나 복잡한 ML보다 먼저 해결한다.
3. LLM은 관광지와 경로의 사실 판단자가 아니다. 후보 검색, 실시간 값, 영업 여부, 경로 가능성은 결정적 서버 로직이 검증하고 LLM은 선호 해석과 근거 기반 설명에 사용한다.
4. SavedPost, TripCandidate, TripItem을 분리하고 후보 저장으로 일정을 변경하지 않는다.
5. AI/optimizer는 변경 전후를 preview하며 사용자 승인 전에는 일정을 바꾸지 않는다.
6. 합성 시드, 실제 관측, 예측, replay, 오래된 정보, 데이터 부재를 API와 화면에서 명확히 구분한다.
7. 정밀 위치는 P0에서 서버 수집하지 않는다. 붙여넣기 원문도 저장·로그하지 않는다.
8. 변경 시 OpenAPI 계약과 이벤트 스키마를 먼저 갱신하고 프론트 클라이언트를 생성한다.
9. 새로운 외부 데이터 연동은 `source`, `source_state`, `observed_at`, `target_at`, `freshness`, `confidence`, `license`, 비교 가능성 정보를 보존한다.
10. 사용자 변경을 보존하고, unrelated file을 정리하거나 destructive git/file 작업을 하지 않는다.
11. 기능 작업은 `기능 ID → Figma node/state → operationId/schema → entity/transition → test → 담당/검토자`를 연결한다.
12. Frontend는 승인된 생성 client/example을 소비하고, Backend/AI는 계약을 제안하되 Frontend 승인 없이 FE-facing shape를 동결하지 않는다.
13. P0의 로그인, 일본어·중국어 UI는 disabled `준비 중`이며 요청을 보내지 않는다. 한국어·영어는 실제 선택·복구를 지원한다.
14. 공모전 제출본은 로그인 없이 핵심 흐름이 완결되고, 한국관광공사 OpenAPI를 실제 server-side 호출하며 승인된 텍스트 출처와 호출 증거를 남긴다.
15. Frontend는 `frontend`, Backend/AI는 `backend`에서 작업하고 상대 승인 및 `docs-contract`·`docker-integration` 뒤 `main`에 merge commit한다.

## 필수 검증

문서·계약 변경:

```bash
python3 scripts/validate_docs.py
npx --yes markdownlint-cli2@0.23.2
npx --yes @redocly/cli@2.51.1 lint docs/api/openapi.yaml
npx --yes --package ajv-cli@5.0.0 --package ajv-formats@3.0.1 \
  ajv validate --spec=draft2020 -c ajv-formats \
  -s docs/contracts/events.schema.json -d docs/contracts/events.example.json
```

새 목표 frontend(`apps/web`)가 생성된 뒤:

```bash
cd apps/web
npm run lint
npm run format:check
npm run typecheck
npm run test
npm run build
```

새 목표 backend(`apps/api`)가 생성된 뒤:

```bash
cd apps/api
./gradlew test
./gradlew integrationTest
./gradlew openapiContractTest
```

모든 `main` PR:

```bash
bash scripts/integration-test.sh
```

라우팅, 검색, sheet/dialog, 여행 생성, 후보 저장, 일정 교체, 최적화 흐름을 바꾸면 Playwright E2E와 키보드 접근성 검사를 함께 추가한다. 새 DB 마이그레이션은 Flyway와 실제 PostgreSQL(Testcontainers/CI)에서 검증한다. 실행하지 못한 검증은 통과로 쓰지 않는다.

## 문서 지도

- 전체 지도와 정본: `docs/README.md`
- 제품 범위: `docs/product/PRODUCT_SPEC.md`
- 화면/상태: `docs/design/FIGMA_HANDOFF.md`
- 컴포넌트: `docs/design/COMPONENT_CATALOG.md`
- 시스템/ERD: `docs/architecture/`
- 외부 데이터: `docs/data/SOURCE_CATALOG.md`
- API/이벤트: `docs/api/openapi.yaml`, `docs/contracts/`
- 구현/협업/테스트: `docs/engineering/`
- 역할별 실행서: `docs/roles/`
- 공모전 기준·제출: `docs/contest/`
- 개인정보/위협 모델: `docs/security/`
- AWS/환경: `docs/operations/`
- 결정/위험: `docs/project/DECISIONS_AND_RISKS.md`
