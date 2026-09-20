---
aliases:
  - "BA-083 경로 provider ADR 초안"
doc_type: plan
status: draft
area: engineering
tags:
  - nullnull/plan
  - nullnull/engineering
---

# BA-083 경로 provider ADR 초안 — 카카오맵 vs 카카오모빌리티

## 0. 이 문서의 지위

ADR 번호는 **배정받지 않았다**. 조율자가 배정한다. `docs/decisions/`에 넣지 않았고 위치는 임시다.
원장 `D-002`("지도·경로 provider는 무엇인가?")의 완료 증거가 요구하는 *"가격/쿼터/약관/SDK 비교 ADR"* 에 답한다
(`docs/project/DECISIONS_AND_RISKS.md:76`).

증거 등급은 절마다 붙인다. **[1차]** = 공식 문서를 열어 읽었다(URL) · **[저장소]** = 이 저장소 파일을 읽었다(file:line) · **[추론]**.

## 1. 결론 요약

1. **두 제품은 실제로 다르고, route를 주는 것은 카카오모빌리티뿐이다.** 카카오맵 Local API에는 길찾기가 없고 공식 문서가 카카오모빌리티로 보낸다. **[1차]**
2. **N×M route matrix API는 없다.** 1→N(다중 목적지, 최대 30)과 N→1(다중 출발지, 최대 30)뿐이라 k개 stop의 directed matrix는 **호출 k회**다. **[1차]**
3. **키는 한 벌이다.** 두 제품 모두 Kakao Developers에서 발급한 REST API 키를 `Authorization: KakaoAK ${REST_API_KEY}`로 쓴다. 자동차 길찾기는 자체 발급이고 **도보·자전거는 제휴 계약이 필요하다**. **[1차]**
4. **가장 큰 문제는 가격이 아니라 약관이다.** 카카오 운영정책 제5조 제20항과 공식 답변들이 **API 응답의 DB 저장을 금지**한다. 카드가 선언한 `route_matrix_snapshots`와 run이 증거를 동결하는 이 저장소의 구조가 여기서 정면으로 부딪힌다. **[1차]**
5. **불변식 10은 깨지지 않는다.** 경로 계산에 보내는 좌표는 사용자 기기 위치가 아니라 **이미 서버에 있는 catalog POI 좌표**다. 깨질 수 있는 자리는 BA-083이 아니라 BA-093(위치·주변)이다. **[저장소]**

**막힌 곳은 4번 하나다.** 오너 결정 또는 카카오에 대한 서면 질의 없이는 BA-083의 저장 설계를 확정할 수 없다.

## 2. 어느 제품이 route matrix를 주는가 **[1차]**

| | 카카오맵 (Kakao Developers) | 카카오모빌리티 (Kakaomobility Developers) |
| --- | --- | --- |
| 콘솔 | `developers.kakao.com` | `developers.kakaomobility.com` |
| API host | `dapi.kakao.com/v2/local/` | `apis-navi.kakaomobility.com` |
| 주는 것 | 주소↔좌표 변환, 좌표계 변환, 키워드·카테고리 장소 검색 | 자동차 길찾기, 다중 경유지·다중 출발지·다중 목적지, 미래운행정보 |
| **길찾기·이동시간** | **없다** | **있다** |
| 인증 | `Authorization: KakaoAK ${REST_API_KEY}` | 같다 |

Local API 문서가 길찾기를 직접 "없다"고 말하는 대신 **`길찾기 SDK & API`로 `developers.kakaomobility.com`을 가리킨다.**
그래서 *"카카오맵으로 경로를 한다"* 는 계획은 출발선에서 틀린다 — 계약도 호스트도 제품도 다른 곳이다.

출처: [Local API 개발 가이드](https://developers.kakao.com/docs/ko/local/dev-guide) · [길찾기 API 제품 페이지](https://developers.kakaomobility.com/product/naviapi.html)

## 3. matrix API는 없다 — 비용은 호출 수로 센다 **[1차]**

| operation | 모양 | 상한 | endpoint |
| --- | --- | --- | --- |
| 자동차 길찾기 | 1→1 (경유지 5) | 총 1,500km | `GET /v1/directions` |
| 다중 경유지 | 1→1 (경유지 30) | 30 | `POST` |
| 다중 목적지 | **1→N** | 목적지 30 | `POST /v1/destinations/directions` |
| 다중 출발지 | **N→1** | 출발지 30 | `POST /v1/origins/directions` |

응답은 목적지/출발지마다 `key`(우리가 정하는 식별자), `summary.distance`(m), `summary.duration`(초), `result_code`/`result_msg`를 준다.
`radius`가 **필수**이고 최대 10,000m다.

**그래서 k개 stop의 directed matrix는 다중 목적지 k회다.** 하루 6 stop이면 6회, TRIP 12 stop이면 12회.
비대칭(A→B ≠ B→A)은 이 호출들이 자연히 만든다 — 카드 `BA-083-T1`이 요구하는 비대칭 검증은 provider가 주는 성질이지 우리가 만드는 것이 아니다.

**누락은 `result_code`로 온다.** 카드의 *"불가능한 구간"* 은 예외가 아니라 정상 응답의 한 필드다. 이 값을 성공으로 접으면
카드의 실패 경계(*"거리/속도로 임의 travel time을 성공 경로로 간주하지 않는다"*)가 바로 깨진다.

출처: [다중 목적지](https://developers.kakaomobility.com/guide/navi-api/destinations) · [다중 출발지](https://developers.kakaomobility.com/guide/navi-api/origins) · [자동차 길찾기](https://developers.kakaomobility.com/guide/navi-api/directions)

## 4. 쿼터와 가격 **[1차]**

무료 **일일** 한도와 초과 단가:

| API | 무료/일 | 초과 단가 | 월 구간 |
| --- | --- | --- | --- |
| 자동차 길찾기 | 10,000건 | 8원 | ~1,000,000 |
| 미래운행정보 | 5,000건 | 8원 | ~1,000,000 |
| 다중 경유지 | 5,000건 | 16원 | ~500,000 |
| **다중 목적지** | **1,000건** | **20원** | ~300,000 |
| **다중 출발지** | **1,000건** | (표 미기재) | — |

> "월간 요청 건수가 위 구간을 초과할 경우 Volume discount 적용 (별도 문의)"

**우리가 쓰는 것이 가장 싼 것이 아니라 가장 비싸고 가장 적다.** matrix를 만드는 다중 목적지가 1,000건/일이고 단가도 20원으로 제일 높다.
6 stop 하루 최적화 한 번이 6건이므로 무료 한도는 **하루 약 160회 run**이다(산술). 공모전 심사 트래픽에는 충분하지만,
**§6의 저장 금지가 사실이면 preview와 APPLY 재검증이 같은 matrix를 두 번 부르므로 실효 한도는 절반**이다. **[추론]**

출처: [가격](https://developers.kakaomobility.com/price/)

## 5. 키와 접근 — 한 벌, 단 도보는 제휴 **[1차]**

- 자동차 길찾기: **자체 발급**이다. *"앱을 등록하고 REST API 키를 발급 받아야 합니다"* — Kakao Developers 로그인 → 앱 생성 → REST API 키 복사. 별도 승인 단계가 문서에 없다.
- 제휴가 필요한 곳은 둘이다: **쿼터 상향**(*"쿼터 상향이 필요하거나 … 제휴 문의로 문의하세요"*)과 **도보·자전거 길찾기**.
- 도보 길찾기는 `/affiliate/walking/v1/directions`이고 문서가 **제휴사 전용**이라고 명시한다. 사전 제휴 계약이 필수다.

**이것이 제품 결정에 직접 걸린다.** 도심 여행 일정에서 인접 POI 사이의 실제 이동은 **도보**인데 자동차 ETA는 그 구간에서 틀린 값이다
(편도 진입·회전 제약·주차가 섞인다). 카드가 step 1에서 *"이동수단·오차"* 를 먼저 정하라고 한 자리가 여기다.
**자동차만으로 갈 것인지, 도보를 위해 제휴를 신청할 것인지가 오너 결정이다.**

출처: [길찾기 API 시작하기](https://developers.kakaomobility.com/guide/navi-api/start.html) · [도보 길찾기(제휴)](https://developers.kakaomobility.com/affiliate/walking/directions)

## 6. 약관 — 여기서 설계가 바뀐다 **[1차, 단 모빌리티 전용 조항은 미발견]**

카카오 **운영정책 제5조 제20항**:

> "앱에서 사용자 환경을 개선하기 위한 목적 외 다른 목적으로 카카오에서 받은 데이터를 캐시하거나 캐시 후 최신 데이터로 유지하지 않는 행위"

즉 캐시는 *사용자 환경 개선 목적*에 한하고, **캐시한 것을 최신으로 유지하지 않는 것 자체가 금지 행위**다.

공식 답변 둘이 이 조항을 저장 금지로 해석한다:

- 2026-08-18, Kakao 직원 C.L: *"당사가 제공하는 API 의 응답 결과는 저장하여 사용하실 수 없습니다."* — 사용자가 직접 입력한 장소명·주소는 저장 가능하나 **그 쿼리로 응답받은 결과의 저장은 불가**.
- 2026-09-17, Kakao 직원 Map: *"장소ID와 URL은 저장하여 활용 가능하며, 이 외 데이터는 DB저장이 불가한 점 참고하여 이용 부탁드립니다."* 같은 스레드에서 **1회 호출 결과를 안내 동안 임시 보유 후 즉시 폐기하는 구조는 허용**, 사전 조회 후 재사용은 불가, **개발용 샘플 응답 저장도 불가**, *"실시간이 아닌 임시 캐싱 등의 방법으로 API 호출 및 이용은 금지됩니다"*.

**부딪히는 지점이 셋이다. [저장소]**

1. **카드가 선언한 entity가 저장을 전제한다** — `route_matrix_snapshots`(`backend-plan.json` BA-083 `entities`, `BACKEND_AI_PLAYBOOK.md:2130`).
2. **run 구조 전체가 증거 동결 위에 서 있다** — `V024__optimization_runs.sql`이 *"optimization_run_route_snapshots would reference route_matrix_snapshots, which does not exist - there is no route provider in P0 … the slice that adds the provider is the one that can say what a route snapshot is"* 라고 적는다. **그 slice가 이 카드이고, 지금 나온 답이 "저장할 수 없을지도 모른다"다.**
3. **출처 보존 규칙과 부딪힌다** — AGENTS 원칙 9는 `source`·`observed_at`·`freshness` 보존을 요구하고, source registry는 `retentionPolicy`(*원본/정규화 snapshot 보존 허용*)와 `staleAfter`를 필수 필드로 둔다(`SOURCE_CATALOG.md` §7). 보존이 금지된 source는 이 registry에서 `retentionPolicy = 보존 불가`가 되고, snapshot FK를 참조하는 설계가 성립하지 않는다.

**중요한 한계 — 이것을 단정으로 쓰지 마라.** 위 두 답변은 **카카오맵/Local API**에 대한 것이고
**카카오모빌리티 길찾기 API를 명시적으로 다룬 공식 답변을 나는 찾지 못했다.** 찾아본 곳: `developers.kakaomobility.com`의
`/`·`/guide/`·`/guide/navi-api/start.html`·`/guide/navi-api/directions`·`/guide/navi-api/origins`·`/guide/navi-api/destinations`·`/price/`·`/affiliate/navi-api/start`·`/affiliate/walking/directions`,
그리고 `이용약관`·`운영정책`·`캐싱`·`저장` 조합 웹 검색 3회. **모빌리티 문서 어디에도 저장·캐싱 조항이 없다.**
운영정책이 지배한다고 보는 근거는 *키가 Kakao Developers에서 발급되고 그 조항에 제휴 예외가 없다*는 것이며, 이는 **[추론]** 이다.

## 7. 불변식 10과의 관계 — 깨지지 않는다 **[저장소]**

불변식 10은 *"P0은 붙여넣기 원문을 저장/로그/analytics에 남기지 않고 **정밀 위치를 서버로 보내지 않는다**"* 이다.
경로 계산이 보내는 좌표는 **사용자 위치가 아니라 catalog POI 좌표**이고 그것은 이미 서버에 있다:

- `V010__canonical_catalog_foundation.sql:12-13` `places.latitude/longitude numeric(9, 6)`
- `V008__catalog_places.sql:15-16` KTO snapshot 좌표

즉 **바깥으로 나가는 것은 사용자에게서 온 적 없는 값**이다. 불변식 10이 겨누는 것은 기기 geolocation이고,
제출 profile은 그것을 아예 OFF로 둔다. 그 경계가 실제로 문제되는 카드는 **BA-093(위치·주변)**이지 이 카드가 아니다.

**다만 공짜는 아니다. 새로 생기는 것은 "정밀 위치 유출"이 아니라 "일정의 외부 전송"이다.**

- 한 번의 호출이 **한 여행의 stop 집합과 순서**를 제3자에게 넘긴다. 개인정보는 아니지만 전송 범위는 카드 step 1이 명시적으로 정하라고 한 항목이다.
- 요청의 `key` 필드는 **우리가 정하는 문자열**이다. 여기에 owner/session ID나 붙여넣기 원문이 들어가면 그때 불변식 10·11이 깨진다.
  `V001__background_jobs.sql:2`가 같은 규칙을 이미 적고 있다 — *"Payload holds domain IDs only; never raw itinerary text, coordinates, or provider secrets."*
  **`key`는 trip item id 같은 내부 id로 고정하고 test로 못박아야 한다**(선례: `RecommendationRequestShapeTest`가 나가는 요청의 key 집합을 고정한다).
- `name`은 optional이므로 **보내지 않는다**. 장소명을 안 보내면 provider에 남길 것이 좌표뿐이다.

## 8. 저장소의 기존 패턴에 담기는가 **[저장소]**

**모양은 이미 있다.** source registry가 `scope`에 **`ROUTE`를 이미 열거**하고 있고(`SOURCE_CATALOG.md` §7),
`quotaPolicy`(일/초당 승인량과 60/80/90 threshold)·`attributionTemplate`·`licenseReviewState`·`retentionPolicy`가 전부 필수 필드다.
egress allowlist도 provider별로 `application.yaml:185-191`에 `allowed-hosts`로 선언돼 있고
`compose.integration.yml:43-44`가 그것을 고정한다 — `apis-navi.kakaomobility.com` 한 줄을 더하는 모양이다.
응답 검증은 `shared/provider/ProviderResponseValidator`와 `operations/application/IngestAudit`의 어휘를 따른다.

**담기지 않는 것은 한 가지뿐이고 그게 §6이다.** 이 저장소의 외부 데이터 설계는 *"관측을 snapshot으로 남기고 그 snapshot을 FK로 참조해 재현한다"* 를
전제로 서 있는데, 저장이 금지된 source는 그 전제를 만족하지 못한다. **패턴을 못 쓰는 것이 아니라 패턴이 딛고 선 가정이 이 provider에 대해 거짓이다.**

## 9. 선택지

| | 내용 | 대가 |
| --- | --- | --- |
| **A. 저장 없이 간다** | matrix를 run 안에서 계산하고 메모리에서만 쓴다. `route_matrix_snapshots`를 만들지 않는다 | run이 route 근거를 동결하지 못한다 → `BA-083-T3`의 *"route stale race"* 가 재정의된다. APPLY 재검증이 매번 재호출이라 쿼터 2배 |
| **B. 서면 허가를 받는다** | `tech.partners@kakaomobility.com`에 캐싱 허용 범위와 도보 제휴를 함께 질의 | 오너 실행·대기. 답이 "불가"면 A나 C로 되돌아온다 |
| **C. provider를 바꾼다** | 저장이 허용되는 출처로 matrix를 만든다(자체 호스팅 OSM 기반 등) | `D-002`의 오너 결정(카카오)을 뒤집는 것이라 오너 판단 영역 |

**권고: B를 먼저, A를 기본 설계로.**
B는 메일 한 통이고 **A/C 중 무엇을 할지가 그 답에 달려 있다**. 동시에 설계는 A를 기본값으로 잡는다 —
**보존 가능 여부를 registry의 `retentionPolicy`로 읽어 분기**하게 만들면, 허가가 오든 안 오든 코드를 다시 쓰지 않는다.
그리고 이것이 *"no silent fallbacks"* 와도 맞는다: 보존 불가인 source에 대해 snapshot을 만들지 **않고**, 조용히 빈 근거를 만들지도 않는다.

**A를 고르면 카드 문구가 바뀐다.** `entities`의 `route_matrix_snapshots`와 `BA-083-T3`의 stale race 절은 조율자 소유 파일이므로
**내가 고치지 않는다.** 제안만 여기 남긴다.

## 10. 오너 결정이 필요한 것

1. **도보를 쓸 것인가** — 쓰면 제휴 신청이 필요하다(자동차만으로는 도심 인접 구간 ETA가 틀린다).
2. **카카오에 캐싱 허용 범위를 서면 질의할 것인가** — §6이 미해결인 채로는 저장 설계를 확정할 수 없다.
3. **키 발급** — 자동차 길찾기는 자체 발급이다. 키는 **파일에 적지 않고** env로만 읽는다(`.env` 값은 비워 둔다).

## 11. 하지 않은 것 · 확인하지 못한 것

- **카카오모빌리티 전용 이용약관/운영정책 문서를 찾지 못했다.** 찾아본 경로는 §6에 나열했다. 모빌리티 API에 운영정책 제5조가 적용되는지는 **[추론]** 이다.
- `/notice/21`(도메인·네트워크 안내)은 **404**였다. egress allowlist에 넣을 호스트는 문서의 endpoint에서 읽은 `apis-navi.kakaomobility.com` 하나이고, 추가 도메인이나 IP 대역이 있는지는 확인하지 못했다.
- 다중 출발지의 **초과 단가**가 가격표에 없다. 무료 1,000건/일만 확인했다.
- 실제 호출은 **한 번도 하지 않았다.** 키가 없고, 이번 Wave는 코드 0줄이다. `result_code` 어휘의 실제 값 목록은 미확인이다.
- 대중교통 경로는 검토 범위에 넣지 않았다(카카오맵 쪽에 대중교통 경로 API가 있다는 정황을 데브톡에서 봤으나 제품 문서로 확인하지 않았다).

## 12. 저장소에 경로 provider가 없다는 주장의 근거 **[저장소]**

`wt-route`(`bc9ce3b`) tracked 파일 전체에 대해:

- `git grep -ri 'kakao|카카오'` → **0건**
- `git grep -riw 'tmap'` → **1건** (`docs/data/SOURCE_CATALOG.md:151`, KTO 연관관광지의 산출 근거 설명이지 우리가 부르는 provider가 아니다)
- `ODSAY`·`OSRM`·`valhalla`·`graphhopper`·`naver.*directions` → 각 **0건**
- `route_matrix` → **6건**, 전부 *앞으로 만들 것*에 대한 서술(`V024` 주석, `SYSTEM_ARCHITECTURE.md:83`, BA-083 카드 2곳)

참고로 `git grep -ri 'TMAP'`는 **215건**을 내는데 전부 `ObjectMapper`·`flatMap`·`PostMapping`의 부분일치다. 단어 경계 없이 세면 틀린다.
