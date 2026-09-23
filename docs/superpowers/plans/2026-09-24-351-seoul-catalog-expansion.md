---
aliases:
  - "서울 주요 장소 catalog 확대 수집 계획"
doc_type: plan
status: draft
area: operations
tags:
  - nullnull/operations
  - nullnull/data
---

# 서울 주요 장소 catalog 확대 — 수집 계획 (#351)

> **상태: 오너 검토 대기.** 이 문서는 계획이다. KTO OpenAPI 호출, staging 적재, 배포는 하지 않았다. 오너가 목록·경로·시점을 승인한 뒤 **오너가 자기 셸에서** 실행한다(§6 전제). [#351](https://github.com/yutakdv/Nullnull/issues/351)은 공개 검색으로 적재를 확인할 때까지 닫지 않는다.

## 요약

- 기존 5곳에 15곳을 더해 20곳으로 넓힌다. 새 목록·발견 operation은 쓰지 않는다. 이미 승인된 `KorService2/detailCommon2` 단건 호출만 쓴다.
- 15곳 모두 KTO 공식 사이트에서 `contentId:contentTypeId`를 확인했다. 기존 5곳과 겹치는 ID는 없고, 15곳 모두 서울특별시에 있다.
- 적재 호출은 15건이다. 개발 계정 하루 한도 1,000건의 1.5%다.
- 가장 큰 위험은 호출량이 아니라 **source 격리**다.
  - 응답 하나가 거절되면(§7) `KTO_KOR_SERVICE_2` 전체가 격리된다. 풀릴 때까지 갱신 시점이 된 기존 장소의 detail 갱신도 실패한다.
  - 거절 원인은 운영자 출력에 나오지 않는다.
  - 그래서 1곳 → 12곳 → 2곳으로 나눠 싣고, 거절된 장소는 값을 고쳐 다시 보내지 않고 목록에서 뺀다.
- **적재는 되돌릴 도구가 없다.** 장소는 바로 공개되고, 숨기거나 내리는 운영 task가 없다(§11).
- 정기 갱신 목록 확대는 이 계획에 넣지 않았다(§12). 현재 정기 detail 갱신에서 찾은 결함은 [#361](https://github.com/yutakdv/Nullnull/issues/361)로 따로 올렸다.

## 1. 범위

- **오너 결정**([#351](https://github.com/yutakdv/Nullnull/issues/351), 2026-09-23): A안, 곧 기존 승인 operation의 단건 수집 대상을 서울 주요 장소 약 20곳으로 넓힌다.
- **이 문서가 다루는 것**: 추가 후보 15곳의 식별자 검증, 기존 5곳과의 중복, 지역·좌표·분류, 권리·출처, 예상 호출량, 실행 순서.
- **다루지 않는 것**:
  - 정기 갱신 목록(`FORECAST_DEMO_PLACES`) 확대(§12)
  - 예보 coverage
  - 영문 이름([#60](https://github.com/yutakdv/Nullnull/issues/60))
  - 영업시간(BA-025)과 관련 장소(BA-026)

  그래서 새 장소에는 적재 뒤에도 예보·영문 이름·영업시간·관련 장소가 없다.

## 2. 근거와 증거 등급

- **공식 근거**: 한국관광공사가 운영하는 「대한민국 구석구석」(`korean.visitkorea.or.kr`)의 상세 페이지와, 그 페이지가 부르는 상세 JSON(`POST /call`, `cmd=TOUR_CONTENT_BODY_DETAIL`)이다.
  - 읽은 필드: `cid`, `contentType`, `title`, `addr1`, `areaCode`, `mapX`/`mapY`, `cat1`, `rmsCat`
  - 조회를 마친 시각: 2026-09-23T18:47:16Z(UTC). 표의 값은 이 조회의 것이다. 요청은 1초 간격으로 20건이었다(후보 15, 보정 5).
- **KTO OpenAPI는 부르지 않았다.** 이 작업의 지시가 장소 수집을 금한다. 그래서 API 응답에서만 볼 수 있는 값은 §10에 미확인으로 남겼다.
- **등급 표기**:
  - `[사이트]`: 공식 사이트에서 읽은 값
  - `[보정]`: 기존 5곳 대조로 API 값과 같다고 확인된 필드의 값
  - `[읽음]`: 이 저장소의 코드·문서를 읽어 확인한 것
  - `[미확인]`: API 응답으로만 확인할 수 있는 값

## 3. 보정 — 사이트 값이 API 값과 같은가

이미 수집된 5곳을 같은 방법으로 읽었다. 그 값을 공개 API의 catalog 값과 대조했다(2026-09-23 UTC, 공개 edge의 `getPlace`).

| 장소 | catalog contentId | 사이트 `cid` | 사이트 `contentType` | 좌표 차이 | 사이트 `rmsCat` → 앞 두 자리 | 사이트 `cat1` | catalog `categoryCode` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 경복궁 | `126508` | `126508` | 12 | 0.04 m | `HS010100` → `HS` | `HS` | `HS` |
| 덕수궁 | `126509` | `126509` | 12 | 0.03 m | `HS010100` → `HS` | `HS` | `HS` |
| 북촌한옥마을 | `126537` | `126537` | 12 | 6.17 m | `HS010600` → `HS` | `A02` | `HS` |
| 서울숲 | `128611` | `128611` | 12 | 0.06 m | `VE030100` → `VE` | `VE` | `VE` |
| 창덕궁과 후원 | `127642` | `127642` | 12 | 0.04 m | `HS010100` → `HS` | `HS` | `HS` |

- **`cid`는 KTO contentId다.** 5곳 모두 같다.
- **`contentType`은 12만, 그것도 두 곳만 대조됐다.**
  - catalog의 content type은 공개 응답에 나오지 않는다. 그래서 대조한 것은 staging 정기 갱신 목록에 적힌 `126508:12`·`128611:12` 둘이다. 둘 다 사이트 값과 같다.
  - 후보 중 두 곳은 `14`이고, 14는 보정 표본이 없다(§6의 배치 C).
- **좌표**: 4곳은 0.06 m 안에서 같다. catalog가 소수점 6자리로 내보내기 때문에 생기는 차이다. 북촌한옥마을은 6.17 m 다르다. 수집 시점 뒤에 KTO 값이 바뀐 것으로 보이지만 확인하지 않았다.
- **분류는 `cat1`이 아니라 `rmsCat`으로 읽는다.**
  - `rmsCat`의 앞 두 자리가 catalog `categoryCode`와 5곳 모두 같다. catalog `categoryCode`는 API의 `lclsSystm1`에서 온다.
  - 사이트 `cat1`은 북촌한옥마을에서 구 체계 값 `A02`를 보인다. 같은 장소의 API `lclsSystm1`은 `HS`였다.

## 4. 후보 15곳

배치: `P`는 시험 1곳, `B`는 content type 12인 나머지, `C`는 content type 14다.

- 장소 이름은 사이트 제목이고, 그 사이트 상세 페이지로 연결된다.
- 거리는 후보의 사이트 좌표와 기존 장소의 catalog 좌표로 쟀다.

| # | 배치 | 장소 | `contentId:contentTypeId` | 구 | 좌표 (위도, 경도) | 분류 `rmsCat` → 예상 `lclsSystm1` | 가장 가까운 기존 장소 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | P | [창경궁](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=8198c334-6d6c-4d87-bdc6-5625b772cc59) | `126511:12` | 종로구 | 37.578881, 126.996449 | `HS010100` → `HS` | 창덕궁과 후원 565 m | |
| 2 | B | [경희궁](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=a82d1e7d-95bc-4a17-ac4d-6beee6b3e716) | `126484:12` | 종로구 | 37.570388, 126.968492 | `HS010100` → `HS` | 덕수궁 927 m | |
| 3 | B | [종묘 \[유네스코 세계유산\]](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=a0bbf927-8a6b-4ada-8e1d-b0524adf9d99) | `126510:12` | 종로구 | 37.570980, 126.995131 | `HS010800` → `HS` | 창덕궁과 후원 858 m | |
| 4 | B | [남산서울타워](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=ad931739-fca3-4135-a878-cc7fb82920ba) | `126535:12` | 용산구 | 37.551055, 126.987882 | `VE010200` → `VE` | 덕수궁 1,849 m | 남산공원(서울)과 637 m |
| 5 | B | [남산공원(서울)](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=4c181f9a-925f-4ebb-8b10-0afa674645ad) | `126485:12` | 중구 | 37.555633, 126.992218 | `VE030500` → `VE` | 덕수궁 1,732 m | |
| 6 | B | [동대문디자인플라자(DDP)](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=758fb4e9-533f-4636-8753-73b8d059c54d) | `2470006:12` | 중구 | 37.566054, 127.009555 | `EX061000` → `EX` | 창덕궁과 후원 2,136 m | 사이트 `cat1`=`A02` |
| 7 | B | [청계천](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=382d5823-16f0-4793-aea7-3e77451bd68e) | `129507:12` | 종로구 | 37.569647, 127.005074 | `VE030100` → `VE` | 창덕궁과 후원 1,582 m | 주소에 번지 없음(창신동). 하천 전체가 아닌 한 점 |
| 8 | B | [인사동](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=3259a90d-1924-43c6-b333-df502374b491) | `264353:12` | 종로구 | 37.575409, 126.983508 | `EX070200` → `EX` | 북촌한옥마을 494 m | 사이트 `cat1`=`A02` |
| 9 | B | [서울 명동성당](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=f12eeeee-67cd-4861-a067-fcafc7d3e6e9) | `126804:12` | 중구 | 37.563676, 126.986776 | `HS030200` → `HS` | 덕수궁 912 m | |
| 10 | B | [여의도한강공원](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=2a242f43-4a4d-4836-a17a-abcb34d0e9bc) | `1059479:12` | 영등포구 | 37.526348, 126.933595 | `VE030100` → `VE` | 덕수궁 5,734 m | 사이트 `cat1`=`A02` |
| 11 | B | [롯데월드 어드벤처](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=78a00ac3-ad3c-4637-8629-48aefb22a79b) | `126498:12` | 송파구 | 37.511297, 127.097884 | `VE020100` → `VE` | 서울숲 6,078 m | |
| 12 | B | [올림픽공원](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=7ba5e7f2-5b78-43a1-8806-d8a6c0e7af2c) | `126532:12` | 송파구 | 37.520555, 127.115052 | `VE030100` → `VE` | 서울숲 6,928 m | |
| 13 | B | [서울식물원](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=309a0548-b732-4f85-8c2f-e45971f30df2) | `2589349:12` | 강서구 | 37.569170, 126.836002 | `NA040700` → `NA` | 덕수궁 12,398 m | |
| 14 | C | [국립중앙박물관](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=0363da87-ef3b-4c21-8508-34f70dda31fb) | `129703:14` | 용산구 | 37.521117, 126.979112 | `VE070100` → `VE` | 덕수궁 4,891 m | content type 14, 보정 표본 없음 |
| 15 | C | [국립현대미술관 서울](https://korean.visitkorea.or.kr/detail/ms_detail.do?cotid=d51b6e84-e905-4901-955f-ab4182f108a5) | `1934593:14` | 종로구 | 37.578595, 126.979988 | `VE070600` → `VE` | 경복궁 405 m | content type 14, 보정 표본 없음. 사이트 `cat1`=`A02` |

### 판정

- **중복**:
  - `[사이트]` 15곳의 contentId가 기존 5곳(`126508`·`126509`·`126537`·`128611`·`127642`)과 겹치지 않는다. 후보끼리도 겹치지 않는다.
  - `[읽음]` DB에 공개되지 않은 같은 ID 행이 있더라도 새 행이 생기지 않는다. canonical ingest가 external reference(source·contentId·content type)로 기존 장소를 찾아 그대로 돌려주기 때문이다(`KtoSnapshotCatalogIngest.ingest`).
- **물리적 겹침**:
  - 기존 장소와 가장 가까운 후보는 국립현대미술관 서울(경복궁에서 405 m)이다. 둘은 서로 다른 시설이다.
  - 후보끼리 1 km 안의 쌍은 5개다. 가까운 순으로 국립현대미술관~인사동 471 m, DDP~청계천 562 m, 남산서울타워~남산공원 637 m, 창경궁~종묘 886 m, 종묘~청계천 889 m이다. 모두 KTO에서 서로 다른 record다.
  - 남산서울타워는 남산공원 권역 안에 있다. 둘 다 넣을지는 오너가 정한다(§11).
- **지역**: `[사이트]` 15곳 모두 `addr1`이 `서울특별시`로 시작하고 `areaCode`가 1(구 체계의 서울)이다. 구별로는 종로구 6, 중구 3, 용산구 2, 송파구 2, 영등포구 1, 강서구 1이다.
  - 기존 5곳 가운데 3곳이 종로구에 있으므로(덕수궁은 중구, 서울숲은 성동구), 20곳 중 9곳이 종로구가 된다. 나머지는 중구 4, 용산구 2, 송파구 2, 성동구·영등포구·강서구 각 1이다.
- **좌표**: `[사이트]` 15곳 모두 좌표가 있다.
  - 공개 검색·상세는 좌표 없는 장소를 내보내지 않으므로 이것은 필요 조건이다.
  - `[읽음]` API 응답에서 좌표가 둘 다 비어도 검증과 적재는 통과한다. 그런 장소는 운영 출력으로는 성공인데 공개 화면에 나오지 않는다. 그래서 배치마다 §8의 공개 확인을 한다.
- **분류**: `[보정]` 예상 `lclsSystm1`은 `VE` 8곳, `HS` 4곳, `EX` 2곳, `NA` 1곳이다.
  - `EX`와 `NA`는 catalog에 처음 들어오는 코드다. `places.category_code`에는 어휘 제한이 없고 빈 값만 거절한다(V010).

## 5. 권리·출처

- **source**: `KTO_KOR_SERVICE_2`이고, registry 상태는 `DEV_APPROVED`, 신선도는 `P7D`, operation은 `detailCommon2` 하나다. 표시 문구는 `출처: ⓒ한국관광공사`, 이용허락은 공공데이터포털 정책을 따른다([SOURCE_CATALOG.md](../../data/SOURCE_CATALOG.md)).
- **저장하는 것**(`KtoDetailResponseValidator`·`KtoSnapshotCatalogIngest`):
  - 제목, `addr1`, 좌표
  - `lclsSystm1`, `lDongRegnCd`, `lDongSignguCd`
  - ID와 payload hash

  개요(overview)와 이미지는 저장하지 않는다.
- **레코드별 권리 심사**: 이미지는 레코드마다 공공누리 유형이 다르다(SOURCE_CATALOG). 이 수집은 이미지를 가져오지 않으므로 그 심사가 생기지 않는다. 텍스트는 source 단위 이용허락을 따른다.
- **mirror 경계**: SOURCE_CATALOG는 *"승인 없는 전체 mirror를 만들지 않고 필요한 지역/변경분만 수집한다"*고 정한다. 이 계획은 오너가 고른 20곳의 allowlist이고, 목록·발견 operation을 쓰지 않는다.

## 6. 실행 경로와 순서

### 경로 선택

두 운영 경로 모두 같은 KTO gateway(`KtoPlaceDetailGateway`), 같은 응답 검증(`KtoDetailResponseValidator`), 같은 canonical ingest(`CatalogIngest` → `KtoSnapshotCatalogIngest`)를 거친다. #351 본문이 적은 *"`kto-ingest` 경로"*가 이 ingest다.

| 항목 | (1) `kto-smoke` → `kto-ingest` | (2) `kto-demo-detail --places` |
| --- | --- | --- |
| 호출·적재 | 장소마다 task 2개(`kto-smoke`가 호출, `kto-ingest`가 적재) | task 하나가 목록 전체를 호출하고 적재한다 |
| task 수(15곳) | 30 | 3(배치 P·B·C) |
| 다시 돌릴 때 | 매번 새로 호출한다(`KtoSmokeMain.FORCE_HORIZON` 365일) | 신선한 snapshot이 있는 장소는 호출하지 않는다 |
| release 확인 | 있다. ops 정의와 image가 배포된 release와 같아야 뜬다 | 없다. 전제 4의 `kto-call-inventory`로 대신한다 |
| 제출 증거 | `kto-smoke`는 성공할 때마다 release의 CMP-KTO-003 증거 `actual-call-<release>.json`을 다시 쓴다. runbook은 이 명령을 release당 한 번 돌리라고 적는다 | 건드리지 않는다 |
| 문서상 용도 | 호출 증거와 단건 확인 | 목록 첫 적재. `KtoDemoDetailRefreshMain`의 javadoc과 `CURATED_POSTS_TEMPLATE` 절차 1에 적혀 있다 |

**권고: (2).** 코드 경로가 같고, 제출 증거를 덮지 않고, 다시 돌려도 이미 받은 장소를 부르지 않으며, task가 적다. (2)에 없는 release 확인은 전제 4로 메운다. (1)을 고르면 아래 배치 순서를 장소 단위로 그대로 따른다.

### 전제(KTO 호출 없음)

1. **목록 승인**: 오너가 §4 목록을 승인한다. 빼거나 바꾼 장소는 이 문서에 반영한다.
2. **실행 주체와 셸**:
   - 오너가 자기 셸에서 실행한다. 승인 변수 `NULLNULL_KTO_SMOKE_APPROVED=true`는 오너가 직접 켠다. 세션이 대신 켜지 않는다(`CURATED_POSTS_TEMPLATE` 절차 1).
   - runbook §11의 환경(`export AWS_PROFILE=nullnull-staging NULLNULL_AWS_AUTH=profile NULLNULL_AWS_ACCOUNT_ID=<account>`)을 먼저 둔다.
   - 대화형 zsh에서는 `setopt interactivecomments`를 켠다. 아래 블록에 `#` 주석 줄이 있다.
3. **잠금과 시간**:
   - 배포나 다른 ops task가 돌고 있지 않다. 모든 ops task는 staging 배포 잠금을 잡고, 실패하면 잠금이 남는다(`deployment_lock=retained owner=<uuid>`).
   - 정기 detail·예보 실행은 배포 잠금 밖에서 돈다(runbook). 직전 정기 실행 시각에서 다음 tick을 계산해 피한다.
   - 게시물 작업([#312](https://github.com/yutakdv/Nullnull/issues/312))의 배포와 겹치지 않게 한다.
4. **release 확인과 기준선**: 배치 P 직전에 `kto-call-inventory`를 돌린다.
   - 읽기 전용이고 KTO를 부르지 않는다. release에 묶여 있어서, ops 정의가 배포된 release가 아니면 `ops-definition-not-the-deployed-release`로 멈춘다.
   - 출력의 `kto_operation … calls=`와 `kto_inventory_excluded rejected=`가 호출 수의 기준선이다.
   - 결과 파일이 로컬 `.artifacts/aws/evidence/`와 release bucket `evidence/kto-inventory/`에 남는다.
5. **source 상태**: `GET /api/v1/health/ready`(인증 없음)의 `source:KTO_KOR_SERVICE_2` 항목이 `DEGRADED`(`latest run quarantined`)가 아니어야 한다.
   - 이 확인은 DB만 읽고 KTO를 부르지 않는다. 2026-09-23T19:39Z에는 `READY`였다.
   - `DemoRefreshFailed` alarm은 이 확인을 대신하지 못한다. 모든 장소가 신선하면 detail 실행은 gateway를 부르지 않으므로, 격리 중에도 성공한다.

### 배치

| 순서 | 배치 | 장소 수 | 호출 | 나눈 이유 |
| --- | --- | --- | --- | --- |
| 1 | P | 1 | 1 | 보정된 유형(12·`HS`)으로 현재 release·key·quota 경로를 먼저 확인한다 |
| 2 | B | 12 | 12 | content type 12. 대조 2/2, contentId·분류 보정 5/5 |
| 3 | C | 2 | 2 | content type 14. 보정 표본이 없어 마지막에 따로 싣는다 |

```bash
# 배치 P
NULLNULL_KTO_SMOKE_APPROVED=true NULLNULL_OPERATIONS_TARGET=postgresql://<rds-endpoint>:5432/nullnull \
  python3 scripts/aws/staging_operator.py task --task kto-demo-detail \
  --places 126511:12 \
  --owner-approval '<누가·어디서 승인했는지>'

# 배치 B
NULLNULL_KTO_SMOKE_APPROVED=true NULLNULL_OPERATIONS_TARGET=postgresql://<rds-endpoint>:5432/nullnull \
  python3 scripts/aws/staging_operator.py task --task kto-demo-detail \
  --places 126484:12,126510:12,126535:12,126485:12,2470006:12,129507:12,264353:12,126804:12,1059479:12,126498:12,126532:12,2589349:12 \
  --owner-approval '<누가·어디서 승인했는지>'

# 배치 C
NULLNULL_KTO_SMOKE_APPROVED=true NULLNULL_OPERATIONS_TARGET=postgresql://<rds-endpoint>:5432/nullnull \
  python3 scripts/aws/staging_operator.py task --task kto-demo-detail \
  --places 129703:14,1934593:14 \
  --owner-approval '<누가·어디서 승인했는지>'
```

**배치마다의 통과 조건**(operator 출력의 `ops_log` 줄):

- `KTO_DEMO_REFRESH_DONE … failed=0`이 찍힌다. `refreshed`와 `current`의 합이 배치 장소 수와 같고, `calls`는 배치 장소 수 이하다. 첫 실행이면 둘이 같다.
- 장소마다 `KTO_DEMO_REFRESH_PLACE mode=detail contentId=<id> contentTypeId=<type> status=<REFRESHED 또는 CURRENT> placeId=<uuid>`가 찍힌다. 다시 돌린 실행에서는 이미 받은 장소가 `CURRENT`다.
- 마지막 줄이 `ops_task=kto-demo-detail result=succeeded`다.
- 장소마다 나온 `placeId`를 기록한다.
- 그 배치의 장소를 §8대로 공개 확인한다. 운영 출력만으로는 좌표가 빈 장소를 잡지 못한다.

통과하지 못하면 다음 배치로 가지 않는다.

## 7. 실패하면 — 멈추는 지점과 복구

### 운영 출력의 실패 코드

장소 줄의 `failure=` 값이다. `[읽음]` 출처는 `KtoSmokeEnvironment.failureCode`, `KtoPlaceDetailGateway`, `KtoDemoRefresh`다.

| 코드 | 뜻 | 격리 | quota |
| --- | --- | --- | --- |
| `SOURCE_QUARANTINED` | 이미 격리돼 있어 호출 전에 거절됐다 | 이미 격리됨 | 쓰지 않음 |
| `KTO_RESPONSE_REJECTED` | 이 호출의 응답이 검증에서 거절됐다. **원인은 출력에 나오지 않는다**(DB의 collector run에만 남는다) | 이 호출이 격리를 만든다 | 씀 |
| `KTO_TRANSPORT_FAILED` | HTTP 오류·timeout 등으로 재시도 뒤에도 실패했다 | 아님 | 씀 |
| `KTO_QUOTA_EXHAUSTED` | 로컬 quota 한도에 걸렸다 | 아님 | 호출 없음 |
| `UNEXPECTED_FAILURE (IllegalArgumentException)` | 호출과 snapshot 저장은 됐고 canonical 적재가 거절됐다. 분류·지역 결측이나 revision 불일치를 출력으로는 구별할 수 없다 | 아님 | 씀 |

### 격리를 만드는 응답

`[읽음]` 출처는 `KtoDetailResponseValidator`, `KtoPlaceSnapshot`, `CollectorRunRecorder`다. 아래 가운데 하나면 collector run이 `QUARANTINED`가 되고 `KTO_KOR_SERVICE_2` 전체가 격리된다.

- 응답의 `contentid`·`contenttypeid`가 요청과 다르다. 요청은 `contentId`만 보내므로, 목록의 content type이 틀리면 여기에 걸린다.
- 결과가 단건이 아니다(0건이거나 여러 건).
- 좌표가 한쪽만 있거나 범위 밖이다.
- 새 코드 체계 셋(`lclsSystm1`·`lDongRegnCd`·`lDongSignguCd`)이 모두 비고 구 코드만 있다.
- 값이 snapshot 형식을 어긴다(제목 300자 초과, 코드 형식, 주소 500자 초과).
- 응답 본문의 `resultCode`가 `0000`이 아니다. provider 쪽 한도 초과도 이 경로라서 격리가 된다. 로컬의 `KTO_QUOTA_EXHAUSTED`와는 다르다.

### 복구

1. **task 실패**: 어느 장소 하나라도 실패하면 exit 1이 되고, operator가 잠금을 남긴다.
   1. ECS task가 멈췄는지 확인한다.
   2. `python3 scripts/aws/staging_operator.py unlock --owner <uuid>`로 잠금을 푼다.
   3. 장소 줄의 `failure=` 코드를 위 표에서 찾는다.

   수동 배치의 실패도 `DemoRefreshFailed` alarm을 울린다. ops task 로그와 정기 실행 로그가 같은 log group에 있기 때문이다. alarm 수신자에게 미리 알린다.
2. **`KTO_RESPONSE_REJECTED`(격리)**:
   - 같은 배치의 남은 장소는 호출 전에 `SOURCE_QUARANTINED`로 거절된다. 격리 여부 검사가 quota 예약과 호출보다 먼저 돈다.
   - 이때 `KTO_DEMO_REFRESH_DONE`의 `calls`는 실제 호출보다 크다. `KtoDemoRefresh`가 예외로 끝난 장소를 모두 호출한 것으로 세기 때문이다.
   - 원인이 출력에 없으므로 **content type 등을 바꿔 다시 보내지 않는다.** 추측이고, 틀리면 두 번째 격리다. 그 장소는 목록에서 뺀다.
   - 복구 순서:
     1. `GET /api/v1/health/ready`에서 격리를 확인한다. 격리가 아닌데 해제 task를 돌리면 `source-not-released`로 끝나고 잠금이 또 남는다.
     2. 오너가 판단한 뒤 `release-source-quarantine`으로 푼다.

        ```bash
        NULLNULL_SOURCE_RELEASE_APPROVED=true NULLNULL_OPERATIONS_TARGET=postgresql://<rds-endpoint>:5432/nullnull \
          python3 scripts/aws/staging_operator.py task --task release-source-quarantine \
          --source-code KTO_KOR_SERVICE_2 --owner-approval '<누가·어디서 승인했는지>'
        ```

     3. 거절된 장소를 뺀 목록으로 같은 배치를 다시 돌린다. 이미 snapshot을 받은 장소는 다시 호출하지 않는다. 호출 대상은 지금부터 P2D 뒤에도 신선한 snapshot이 없는 장소뿐이다.
3. **`UNEXPECTED_FAILURE (IllegalArgumentException)`(적재만 거절)**:
   - 새 코드 가운데 하나 이상은 있지만 분류(`lclsSystm1`)나 지역(`lDongRegnCd`)이 비었거나, snapshot의 revision이 현재와 다를 때다.
   - source는 격리되지 않는다. 그 장소는 snapshot만 남고 catalog 행이 생기지 않는다.
   - 원인이 출력으로 구별되지 않으므로 그 장소를 뺄지 오너가 정한다.
4. **quota**: 이 계획으로는 닿지 않는다(§9). `KTO_QUOTA_EXHAUSTED`가 나오면 멈추고 다음 날 다시 돈다.

### 문서 불일치 — 이 계획은 고치지 않는다

*"해제 도구가 없다"*는 문장이 세 파일 다섯 곳에 남아 있다. `release-source-quarantine`이 생기기 전의 서술이다.

- `STAGING_DEPLOYMENT_RUNBOOK.md` 286·343·499행
- `ENVIRONMENT.md` 277행
- `SUBMISSION_RUNBOOK.md` 213행

같은 runbook의 서울 수집 절과 `staging_operator.py`는 이미 그 도구를 쓴다.

## 8. 적재 뒤 확인(읽기 전용, 배치마다)

- **공개 edge**: 그 배치의 장소마다 `POST /api/v1/places/search`로 이름을 검색해, §6에서 기록한 `placeId`가 나오는지 본다. `GET /api/v1/places/{placeId}`에서는 다음을 본다.
  - `regionCode`=`11`
  - `categoryCode`가 §4의 예상값인지
  - 좌표가 있고 §4 값과 몇 m 안에서 같은지. 북촌한옥마을처럼 수 m 차이는 있을 수 있다.
  - 이름은 API 제목이다. 예: `종묘 [유네스코 세계유산]`, `남산공원(서울)`
- **호출 수**: 배치 뒤 `kto-call-inventory`를 다시 돌려 전제 4의 기준선과 비교한다.
  - `calls=`는 검증을 통과한 호출을 release 전체로 누적해 센다.
  - 거절된 호출은 모든 KTO source를 합친 `rejected=` 한 숫자로만 나온다.
- **장소 수**: 마지막 배치 뒤 공개 검색으로 닿는 장소가 20곳인지 센다. #54에서 쓴 한 글자 검색 방법을 쓸 수 있다.
- **보고**: #351에 배치별 결과, `placeId` 목록, 공개 확인 결과를 댓글로 남긴다. 이 확인이 끝나야 #351을 닫는다.

## 9. 예상 호출량

- **적재**: `KTO_KOR_SERVICE_2`의 `detailCommon2` 15건이다. 새 장소에는 snapshot이 없으므로 장소당 1건이다. 예보 source 호출은 0건이다.
  - 하루 한도는 1,000건(registry `quotaPolicy.perDay`)이고, 15건은 1.5%다. 한도 회계 단위는 활용신청(API) 단위로 확정돼 있다(SOURCE_CATALOG, 2026-09-21).
  - `[읽음]` 호출 1건은 IO 오류·429·5xx에서 최대 3회의 HTTP 요청이 된다(`RetryPolicy`, `NULLNULL_PROVIDER_RETRY_ATTEMPTS` 기본 3). 로컬 quota는 15건을 세고, provider 쪽 요청은 최악 45건(4.5%)이다.
  - operator 출력의 `KTO_DEMO_REFRESH_QUOTA … planned_calls=… per_day=1000 planned_ratio=…` 줄은 task가 끝난 뒤에 보인다. operator가 task가 멈춘 뒤 로그를 읽기 때문이다. 그래서 사전 확인이 아니라 실행 기록이다.
- **다시 돌릴 때**: 이미 snapshot을 받은 장소는 호출하지 않는다. 격리 뒤 거절된 장소도 호출하지 않았으므로 quota를 쓰지 않는다.
- **기존 정기 갱신**: 바뀌지 않는다. 대상은 `126508:12`·`128611:12` 두 곳이고, detail 5일·예보 12시간 주기다. runbook 기준 하루 약 4건이다.
- **적재 뒤, 정기 갱신을 넓히지 않을 때**:
  - 새 장소의 detail snapshot은 P7D 뒤 stale이 된다.
  - 공개 검색·상세는 영향을 받지 않는다. 읽기 경로(`JdbcCatalogPlaceQuery`)는 snapshot을 읽지 않고, 국문 텍스트의 게이트는 source revision과 활성 여부만 본다.
  - 영향을 받는 것은 예보 요청 매핑(`findFreshRequest`)뿐이다. 새 장소의 예보는 이 계획에 없다.

## 10. 미확인 항목

| 항목 | 상태 | 확인되는 때 |
| --- | --- | --- |
| 15곳 각각이 `detailCommon2`에서 단건(`totalCount`=1)으로 오는가 | `[미확인]` 아니면 격리(§7) | 각 배치 응답 |
| content type 12의 API 값(13곳) | `[미확인]` 사이트 값 12, 대조 2/2 | 배치 P·B 응답 |
| content type 14의 API 값(`129703`·`1934593`) | `[미확인]` 사이트 값 14, 보정 표본 없음 | 배치 C 응답 |
| 15곳의 API `lclsSystm1` | `[미확인]` §4의 예상값은 `rmsCat` 앞 두 자리(보정 5/5) | 각 배치 응답. canonical `categoryCode`로 보인다 |
| 15곳의 API `lDongRegnCd`·`lDongSignguCd` | `[미확인]` 사이트에 없다. 적재에 `lDongRegnCd`가 필요하다 | 각 배치 응답 |
| 제목·좌표의 API 값 | `[미확인]` 사이트 값과 같을 것으로 보지만 수집 시점 차이가 있을 수 있다 | 각 배치 응답과 §8 |
| staging DB에 비공개 행이 있는지 | `[미확인]` #351의 DB 집계는 아직 실행 경로가 없다 | 영향 없음(§4 판정의 중복 항목) |
| 새 장소의 KTO 예보 coverage | `[미확인]` 예보 API를 부르지 않았다 | 정기 갱신 확대 결정 때 |

## 11. 오너가 정할 것

1. **§4 목록 승인.** 특히 다음 셋을 판단한다.
   - 남산서울타워와 남산공원(서울)을 둘 다 넣을지(637 m, 한 권역)
   - 청계천을 넣을지. 하천 전체가 아니라 한 점이다. 일정의 경로 계산이 그 점을 쓴다.
   - 종로구 쏠림(20곳 중 9곳)을 받아들일지
2. **되돌릴 수 없음을 받아들일지.**
   - 적재한 장소는 `ACTIVE`로 만들어져 바로 공개된다(`KtoSnapshotCatalogIngest`).
   - 장소를 숨기거나 내리는 운영 task는 없다. staging DB는 VPC 밖에서 닿지 않는다.
   - 공개 이름은 API 제목이다.
3. **경로**: (2) `kto-demo-detail`(권고)과 (1) `kto-smoke`→`kto-ingest` 가운데 하나.
4. **실행 시점**: 배포·게시물 작업·정기 tick과 겹치지 않는 창.

## 12. 별도 결정 — 정기 갱신 목록 확대

이 계획은 정기 갱신 목록을 바꾸지 않는다. 오너 결정(#351 댓글)대로 호출량과 관측 가능성을 본 뒤 따로 정한다. 아래는 그 결정에 필요한 사실이다.

- **목록 하나가 두 스케줄을 움직인다**: `infra/src/staging.ts`의 `FORECAST_DEMO_PLACES`가 detail(5일)과 예보(12시간) 스케줄의 입력이다.
  - 두 스케줄은 같은 설정으로 만들어진다. 종료는 2026-10-25T14:59:59Z(`FORECAST_SCHEDULE_END`)이고, scheduler 재시도는 `retryAttempts: 3`이다.
  - 스케줄은 `Migration` stack에 있다. 목록을 바꾸면 `infra/` 코드를 고치지만 app release로 나간다.
- **현재 detail 갱신의 결함**([#361](https://github.com/yutakdv/Nullnull/issues/361), `[읽음]` 코드 분석, 측정 안 함):
  - `rate 5일 + DETAIL_RENEW_BEFORE 2일 = 수명 7일`이라 갱신 여부가 기동 지연 차이로 갈린다.
  - 건너뛴 주기에는 detail snapshot이 약 3일 비고, 그동안 예보 갱신이 `NO_VERIFIED_KTO_MAPPING`으로 실패한다.
  - 목록을 넓히면 같은 공백이 새 장소에도 생긴다. 목록 확대 전에 이것을 먼저 정한다.
- **호출량(목록에 N곳일 때, `[읽음]` 코드를 읽어 낸 추정)**:
  - detail은 5일마다 최대 N건이다(위 결함으로 건너뛰는 주기에는 더 적다).
  - 예보는 하루 N~2N건이다. set 수명이 PT24H이고 만료 12시간 전부터 갱신하므로, 12시간 주기의 실행이 장소마다 하루 1~2번 부른다. coverage가 없는 장소는 set을 저장하지 않으므로(`KtoCrowdForecastGateway`) 매 실행, 곧 하루 2번 부른다. 예보 호출은 예보 source의 하루 한도 1,000건에 따로 잡힌다.
  - N=20이면 detail은 하루 최대 약 4건, 예보는 하루 최대 약 40건이다.
- **관측 가능성**: KTO는 모든 관광지를 예보하지 않는다.
  - coverage가 없는 장소의 예보 갱신은 실패가 아니라 `coverage=0`인 `REFRESHED`로 끝난다.
  - 이것을 따로 알리는 alarm은 INT-04 장소(경복궁) 하나에만 있다(`infra/src/staging.ts`).
  - 그래서 coverage가 없는 장소를 넣으면, 호출은 쓰는데 데이터는 없는 상태를 어떤 alarm도 알리지 않는다. 결정 전에 새 장소의 예보 coverage를 확인해야 한다(§10).
