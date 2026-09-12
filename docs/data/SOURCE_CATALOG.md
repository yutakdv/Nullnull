---
aliases:
  - "외부 데이터 Source Catalog"
doc_type: reference
status: baseline
area: data
tags:
  - nullnull/reference
  - nullnull/data
---

# 외부 데이터 Source Catalog

- 상태: 공모전 KTO 실제 활용 필수, source별 운영 승인·quota·field fixture는 연결 전 확인
- 조사일: 2026-09-05
- 원칙: provider의 현재 문서·약관·승인 상태를 배포 전에 다시 확인

> 구현 순서: [B00~B10 실행 계획](../engineering/IMPLEMENTATION_PLAN.md)을 따른다. 공통 KTO·장소·forecast·비교·relation은 B03, Live 전용 서울 연동·area/API/탭은 B10 마지막이다. Live 이전 검수는 핵심 흐름의 중간 gate이며 전체 P0 완료가 아니다.

## 1. P0 source 요약

| Source code | 제공자/데이터 | 제품 사용 | P0 상태 | 가장 큰 주의점 |
| --- | --- | --- | --- | --- |
| `KTO_KOR_SERVICE_2` | 한국관광공사 국문 관광정보 | canonical POI/검색/상세/이미지 후보 | C2 registry v2 `DEV_APPROVED`, `P7D`; `detailCommon2`만 | 운영 승인·이미지별 이용 조건 |
| `KTO_CONCENTRATION_FORECAST` | 관광지 집중률 방문자 추이 예측 | 같은 POI의 다른 날짜 혼잡 비교 | C4 registry v2 `DEV_APPROVED`, operation `tatsCnctrRatedList`, schema `kto-tats-cnctr-rate-v4.1`, `PT24H` | 방문자 수가 아닌 상대 집중률 예측. 가장 붐비는 시기를 100으로 둔 날짜 단위 상대값이며 인원·수용률·시간대 예측이 아니다 |
| `KTO_RELATED_PLACES` | 관광지별 연관 관광지 | 대체/연관 장소 근거 | `DISABLED` (미신청) | 차량 내비 데이터·과거 기간/의미 한계 |
| `SEOUL_CITYDATA` | 서울 실시간 도시데이터 | Live area 혼잡·지도/목록 | `DISABLED` (B10 전) | area scope, 장소 목록/field 변경, 품질 사고 |
| `DEMO_REPLAY` | 검증된 내부 fixture | 시연/외부 장애 fallback | `DISABLED` (B10 전) | 현재 실시간처럼 표시 금지 |
| `NULLNULL_CATALOG_RULE` | 내부 taxonomy·region 규칙 | C5 `SIMILAR` 대체 후보 | C1 registry v1 `PROD_APPROVED`, `P7D` | 외부 relation 사실·혼잡 근거로 표시 금지 |
| `ROUTE_PROVIDER` | 미정 | 이동 시간/route matrix | P1 | provider/가격/쿼터/약관 미결정 |

registry v1은 공모전 제출 빌드에서 KTO `DEV_APPROVED` 개발 키(1,000/일)를 실제 호출에 쓸 수 있다는 팀 결정을 기록했다. C2 registry v2는 `KorService2/detailCommon2` 하나만 reviewed operation으로 고정하지만, fixture 검증은 actual KTO gateway·서비스 내 사용 증거 또는 source capability ON을 대신하지 않는다. 서울/Replay/live flag와 D-003의 production 운영 key·재배포 조건은 여전히 별도 결정이다. 공모전 제출 서비스는 한국관광공사 OpenAPI를 실제로 사용해야 하므로 KTO 실제 호출·서비스 내 사용 증거가 없으면 제출 자체를 차단한다.

## 2. KTO 국문 관광정보 서비스

공식 [공공데이터포털 상세](https://www.data.go.kr/tcs/dss/selectApiDataDetailView.do?publicDataPk=15101578)는 base path를 `apis.data.go.kr/B551011/KorService2`로 안내하고, 지역/위치/키워드 검색, 공통·소개·반복·이미지 등 15종 기능을 제공한다. 2026-02-26 수정 기준 개발 계정 신청 가능 트래픽은 1,000이고 운영 단계는 심의 승인이라고 명시한다.

### 사용 범위

- canonical place seed와 검색 후보
- 이름, category, region/address, 좌표
- 상세 소개/운영 정보는 source timestamp와 함께 표시
- image는 해당 record의 공공누리 유형과 제한을 별도 asset metadata로 보존

### 금지/주의

- `contentId`를 Nullnull 내부 ID로 쓰지 않는다. `(source, externalId, externalType)` 외부 참조로 둔다.
- 좌표/영업 정보가 존재한다고 최신·정확하다고 단정하지 않는다.
- 사진은 record마다 공공누리 1/3 유형 등 조건이 다를 수 있고 기업 CI/BI 등 제한 안내가 있으므로 URL만 가져와 무조건 재배포하지 않는다.
- 승인 없는 전체 mirror를 만들지 않고 필요한 지역/변경분만 수집한다.
- 22(일일 한도), 23(초당 한도), 인증 만료 등 provider error를 application error와 구분한다.
- 서비스 키는 Backend runtime에만 두고 브라우저 URL/bundle/source map/log에 절대 포함하지 않는다.
- KTO 표시 기본 문구는 `출처: ⓒ한국관광공사`다. `TourAPI`만 단독 표기하거나 승인 없는 CI·BI 이미지를 사용하지 않는다.

### Source registry 초기값

```yaml
code: KTO_KOR_SERVICE_2
displayName: 한국관광공사 국문 관광정보
scope: PLACE
sourceState: QUALITATIVE
license: RECORD_LEVEL_REVIEW_REQUIRED
approvalState: DEV_APPROVED
quotaPolicy: { perDay: 1000, thresholds: [60, 80, 90] }
staleAfter: P7D
registryVersion: 2
providerSchemaVersion: kto-kor-service2-detailcommon2-v1
attributionTemplate: "출처: ⓒ한국관광공사"
```

### C2 고정 provider operation

- exact base는 `https://apis.data.go.kr/B551011/KorService2`, operation은 `GET /detailCommon2`뿐이다. runtime에는 공공데이터포털 **decoding key**를 `KTO_SERVICE_KEY`로 주입하며 key·full URL/query는 log, audit, artifact, browser에 남기지 않는다.
- 2026-09-10 현재 request는 이미 검증된 숫자 `contentId`와 baseline `MobileOS=ETC`, `MobileApp=Nullnull`, `_type=json`만 사용한다.
- **quota 회계가 key가 아니라 source 단위다(2026-09-13 확인).** 저장소 스스로 개발 키를 *"`DEV_APPROVED` 개발 키(**1,000/일**)"* 로 적는데 그것은 **키당** 한도다. 그런데 `JdbcSourceQuotaStore.reserveInTransaction`은 사용량을 `WHERE r.source_code = ?`로 세고 `source_registry`의 두 KTO 행이 **각각** `perDay: 1000`을 갖는다. 그리고 `KtoKorServiceProperties`의 **`serviceKey` 한 필드**가 detail URI(`:84`)와 forecast URI(`:109`) 양쪽에 쓰인다 — **자격증명은 하나인데 카운터는 둘이다.**

  즉 우리 guard는 **하루 2,000회까지 허용**하고 실제 한도는 1,000회다. 초과분은 provider가 `resultCode 22`로 거절하고 그것은 `PROVIDER_ERROR`로 처리되므로 **틀린 데이터가 저장되지는 않는다** — 안전하게 실패한다. 다만 **우리 quota 정지선이 먼저 걸리지 않아** 운영자는 provider 오류를 보게 되고, `thresholds [60,80,90]` 경고도 실제 소진율의 절반에서 울린다. 한도를 키 단위로 모으거나 두 행의 합이 1,000을 넘지 않게 배분해야 맞다.

  **소진 속도 자체는 데모에 문제가 아니다.** `KTO_KOR_SERVICE_2`는 `stale_after_seconds` 604800(P7D)이라 장소당 7일에 1회, `KTO_CONCENTRATION_FORECAST`는 86400(PT24H)이라 장소당 하루 1회다. 장소 N개면 하루 약 `N + N/7 ≈ 1.14N`회이므로 **1,000회 안에서 약 870개 장소**를 덮는다. P0 제출 데모 규모에서는 여유가 있다.

- **2026-09-13 실제 호출 성공(local profile).** 세 단계(`ktoSmoke` → `ktoCanonicalIngest` → `ktoForecastSmoke`)가 end-to-end로 통과했다. fetch는 `2026-09-12T18:56:08Z`(= 09-13 03:56 KST)이고, DB에서 직접 확인한 결과는 아래와 같다. 값이 아니라 **집계와 content 유래 식별자만** 기록한다.

  | 확인 항목 | 값 |
  | --- | --- |
  | `collector_runs` | 2건 모두 `COMPLETED`, `records_rejected=0` — `KTO_KOR_SERVICE_2` 1/1, `KTO_CONCENTRATION_FORECAST` **30/30** |
  | `api_ingest_logs` | `KOR_SERVICE_2_DETAIL_COMMON_2` OK/200/1/OK, `TATS_CNCTR_RATE_LIST` OK/200/**30**/OK |
  | 저장된 행 | `kto_place_snapshots` 1, `places` 1, `place_external_refs` 1, `snapshot_sets` 1, `crowd_snapshots` **30** |
  | detail snapshot | registry revision **4**, `contentId` 126508 / `contentTypeId` 12, `payloadHash` `3a59a84a…a700d` |
  | forecast set | registry revision 2, `kto-tats-cnctr-rate-v4.1`, `forecastIssueId` `kto-tats-6ffe43bd…3ca8` |
  | `quality_flags` | 30행 전부 `[]` — `PROVIDER_INCIDENT` 외에는 생산자가 없으므로 예상대로다 |

  **저장된 예보 창은 `2026-09-12` ~ `2026-10-11`(KST)이고, 첫 행이 조회일(09-13)보다 하루 앞선다.** 이것이 창 하한 수정(#174)을 **실응답으로 증명한 값**이다 — 하한이 조회일이었다면 첫 행에서 `RANGE`로 **30행 전체가 거절**됐을 것이고, `records_rejected=0`이 그렇지 않았음을 말한다. 상한은 조회일 +28일이라 `MAX_FORECAST_DAYS`(30) 안이다.

  **이것은 local 증거이지 staging 증거가 아니다.** `BA-021-T3`(*staging 실제 KTO 성공 이력과 공개 응답 provenance 연결*)과 compliance의 `EV-KTO-02`(*매 staging release*)는 **여전히 열려 있다.** local 실행으로 그 행을 채우지 않는다.

- **2026-09-11 실제 호출 실측:** `detailCommon2` 응답에서 `areacode`·`sigungucode`·`cat1`·`cat2`·`cat3`은 빈 문자열이고 값은 `lDongRegnCd`·`lDongSignguCd`·`lclsSystm1`·`lclsSystm2`·`lclsSystm3`에 있다. registry revision 4(`kto-kor-service2-detailcommon2-v3`)부터 validator가 실제로 값이 있는 쪽을 읽는다. 두 코드 체계를 섞지 않으므로 revision 3 이하로 수집된 snapshot은 수집 당시의 의미를 유지한다. retired 식별자만 담긴 응답은 remap하지 않고 `SCHEMA_DRIFT`로 quarantine한다. `tatsCnctrRatedList`는 `areaCd`가 필수(`resultCode 11 NO_MANDATORY_REQUEST_PARAMETERS_ERROR1`)이고, **코드 체계는 실호출로 확정됐다(#109)**: `areaCd`는 시도 2자리, `signguCd`는 **시도+시군구 5자리 법정동 접두사**다. 측정한 조합과 결과는 아래와 같으며, 값이 아니라 `resultCode`·`totalCount`·item field 이름만 기록한다.

| `areaCd` | `signguCd` | `tAtsNm` | `resultCode` | `totalCount` |
| --- | --- | --- | --- | --- |
| `11` | `110` | 있음 | `0000` | **0** |
| `11` | `11110` | 있음 | `0000` | **30** |
| `11110` | `11110` | 있음 | `0000` | 0 |
| `1111000000` | `1111000000` | 있음 | `0000` | 0 |
| `11` | `11110` | 생략 | `0000` | **3390** |
| `11` | `110` | 생략 | `0000` | 0 |

즉 `11`+`110`이 0건이던 것은 파라미터 형식 오류가 아니라 `signguCd`가 5자리여야 하기 때문이었고, `tAtsNm`은 **필터**라 생략하면 같은 시군구 전체가 돌아온다(30건 대 3390건). item은 `areaCd`·`areaNm`·`baseYmd`·`cnctrRate`·`signguCd`·`signguNm`·`tAtsNm`이고, `areaCd`/`signguCd`는 보낸 값을 그대로 echo하므로 validator가 보낸 값과 대조하는 것이 맞다. `baseYmd`는 `yyyyMMdd` 일 단위 30건인데 **조회일부터가 아니라 조회일 전날부터**다 — 02:00 KST 조회에서 `20260912..20261011`이 왔다. batch가 생성된 날짜로 키를 잡기 때문이고, 이 하루 차이가 validator의 하한을 조회일에 맞춰 두면 **실응답 전체가 `RANGE`로 거절되는** 원인이었다. `cnctrRate`는 **문자열**로 오고 실측 소수 자릿수는 1~2자리(허용 상한 4), 값은 0~100 안이다. 한 시군구 전체는 3390건이라 `MAX_RECORDS`(31)를 넘으므로 `tAtsNm`는 선택이 아니라 필수이며, 그 값은 **정확히 일치**해야 한다(보낸 `경복궁`이 그대로 echo됐다). provider가 `areaNm`(시도명)·`signguNm`(시군구명)을 함께 주지만 이는 forecast source의 값이므로 catalog의 `regionName`으로 그대로 쓰지 않는다 — source가 다르고 forecast coverage가 있는 장소만 덮는다. current `detailCommon2`는 `contentTypeId`와 legacy detail flag(`defaultYN`, `firstImageYN`, `areacodeYN`, `catcodeYN`, `addrinfoYN`, `mapinfoYN`, `overviewYN`)를 보내지 않는다. 후보에서 보유한 content type은 response의 normalized `contenttypeid`와 대조할 뿐 request parameter가 아니다.

- response는 `resultCode=0000`, 하나의 matching item, bounded title/category/area/address, 함께 존재하는 지리 좌표와 지구 범위를 통과해야 한다. 정상 projection은 source registry revision·collector run·hash·fetched/stale 시각만 포함한 immutable snapshot이다.
- `nullnull.env=test`의 loopback fixture만 official base 검사를 예외로 할 수 있다. 실제 staging success → collector audit → C3 public provenance projection은 BA-021-T3의 별도 release gate이며, 현재 fixture 결과로 대체할 수 없다.

## 3. KTO 관광지 집중률 예측

공식 [관광지 집중률 방문자 추이 예측 정보](https://www.data.go.kr/data/15128555/openapi.do)는 KT 이동통신 기반 과거 패턴으로 조회일 기준 향후 30일 집중률을 예측한다. 가장 붐비는 시기를 100으로 둔 **상대 수치**이며 실제 방문자 수와 차이가 날 수 있다고 명시한다. 2026-05-19 수정 기준 개발 계정 1,000, 운영 단계 심의 승인이다.

### 올바른 의미

- 같은 관광지의 다른 target date/time을 비교하는 temporal signal.
- 값 `80`은 80명, 수용량 80%, 서울 혼잡도 4단계의 특정 단계가 아니다.
- source가 제공하는 예측 발표/버전 식별자가 충분하지 않으면 collector run + request hash + 수집 시각으로 내부 `forecastIssueId`를 만든다.
- 전후 비교는 같은 POI와 같은 forecast issue, 같은 metric definition일 때만 eligible이다.

### API/UI mapping

```text
sourceState = FORECAST
observedAt = provider가 제공한 발표 시각, 없으면 null
targetAt = 예측 대상 날짜/시각
value = 원본 상대 집중률
unit = KTO_RELATIVE_CONCENTRATION_INDEX
comparisonAxis = TEMPORAL
```

`observedAt`을 provider가 제공하지 않으면 null을 숨기지 않고 `fetchedAt`을 별도로 보여 준다. 수집 시각을 관측 시각으로 속이지 않는다.

### 수집/캐시

- active trip/curated demo POI만 요청한다.
- 한 forecast issue의 series를 snapshot set으로 고정한다.
- UI 요청은 Backend read-through/refresh 정책을 사용한다. 같은 요청을 중복 호출하지 않되 제출 심사 flow의 실제 KTO 호출과 서비스 내 사용을 재현할 수 있어야 한다.
- 개발 quota 1,000을 전제로 full nationwide refresh를 설계하지 않는다.
- D-015의 stale threshold는 forecast `PT24H`로 확정했다. 공식 갱신 특성과 실제 quota 관측은 collector schedule을 재검토하는 근거이며 threshold를 조용히 바꾸는 근거가 아니다.

## 4. KTO 관광지별 연관 관광지

공공데이터포털의 공식 활용 설명은 이 데이터가 Tmap 사용자의 차량 목적지 조회와 이동 조건을 바탕으로 산출되며, 실제 연계 방문 정도나 방문자 수와 차이가 있을 수 있다고 설명한다. 지역/유형별 최대 순위가 있을 수 있으나 이는 “도보로 비슷한 장소”, “지금 더 한산함”, “사용자 취향과 일치”를 자동으로 뜻하지 않는다. [공공데이터포털 공식 활용 설명](https://www.data.go.kr/tcs/puc/selectPublicUseCaseView.do?bg_radio_off_on=on&bindCndCtgry=&cndCtgryAgriFish=&cndCtgryCulTour=&cndCtgryEdu=&cndCtgryEnvWthr=&cndCtgryFdHlth=&cndCtgryFnc=&cndCtgryHlthCare=&cndCtgryLaw=&cndCtgryMngtTrt=&cndCtgryPblAdmin=&cndCtgrySncTech=&cndCtgrySocWlf=&cndCtgryTrnsLgc=&cndCtgryUnfc=&hbrdSe=&pageIndex=1&prcuseCaseSn=1068161&prcuseType=&searchCondition1=&searchCondition2=&searchKeyword1=&sort-post=all)

### 사용 규칙

- `KTO_RELATED_PLACES`는 신청하지 않았으므로 C1 registry에서 `DISABLED`다. adapter가 이 source를 선택하거나 quota를 예약하지 않는다.
- 관계 원본은 `EXACT`가 아니라 provider relation evidence로 저장한다.
- Nullnull의 `EXACT`는 공식 direct relation + canonical ID 검증 등 강한 조건을 만족할 때만 mapping policy가 부여한다.
- category/거리/interest 후처리로 만든 후보는 `SIMILAR`이고 이유를 표시한다.
- relation과 crowd comparison은 별도다. 관계가 있어도 같은 snapshot scope가 아니면 crowd delta를 계산하지 않는다.
- 원본 집계 기간/effective period를 `effectiveAt/expiresAt/evidence`에 보존한다.
- 실제 서비스 상세 페이지, operation, 이용 기간, 재배포 조건을 해당 adapter 착수 전에 다시 확인한다. 확인 실패·근거 부족은 `UNKNOWN`으로 표시한다. 독립적으로 검증된 내부 규칙 후보만 `SIMILAR`로 제공하며, 필요한 조회와 검증을 완료한 뒤 적격 후보가 0일 때만 `NONE`이다.

## 5. 서울 실시간 도시데이터

공식 [서울시 실시간 도시데이터 상세](https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do)는 인구, 교통, 날씨/환경, 문화행사 등을 결합한 area 단위 데이터다. 2026-09-03 갱신 화면 기준 주요 121장소를 안내하며 한 번에 한 장소만 호출할 수 있고 sample key는 제한된 장소만 조회할 수 있다고 명시한다. 이용허락은 공공누리 1유형(출처표시)으로 안내된다.

매뉴얼은 데이터별 주기가 다르지만 최소 약 5분 단위 갱신을 설명한다. [서울 실시간 도시데이터 매뉴얼](https://data.seoul.go.kr/SeoulRtd/downloads/%EC%8B%A4%EC%8B%9C%EA%B0%84_%EB%8F%84%EC%8B%9C%EB%8D%B0%EC%9D%B4%ED%84%B0_%EB%A7%A4%EB%89%B4%EC%96%BC.pdf)

### FCR-011 출처 계약

2026-09-06 확인 기준으로 제공기관·저작권자는 서울특별시, 공개일은 2022-08-31,
이용허락은 공공누리 제1유형이다. 공공누리 제1유형은 기관·저작물·출처를 표시하고,
온라인에서 가능하면 원 출처 링크를 제공하도록 요구한다. source registry revision 1의
Frontend-facing 값은 다음으로 고정한다.

```yaml
code: SEOUL_CITYDATA
displayName: 서울시 실시간 도시데이터
officialUrl: https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do
licenseName: 공공누리 제1유형
licenseUrl: https://www.kogl.or.kr/info/licenseType1.do
attributionTemplate: "출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)"
```

API는 위 문구와 URL을 `DataProvenance`에 넣는다. Frontend는 `source`로 제공자 문구를
다시 만들지 않고 `attribution`을 그대로 표시하며, 온라인 화면에서는
`officialUrl`에 연결한다. `licenseUrl`은 데이터 안내나 출처 상세에서 함께 제공한다.
KTO 관광정보·집중률 예측과 서울 실시간 관측이 한 카드에 있으면 provenance primitive를
각각 렌더링하고 하나의 `ⓒ한국관광공사` 문구로 합치지 않는다.

### Scope 경계

- 서울 값은 `LiveArea` 범위의 신호이지 개별 `Place` 입장 인원이나 수용량이 아니다.
- POI에 붙일 때 `mappingType`, `confidence`, `fallbackUsed`를 항상 제공한다.
- area 값과 KTO POI 예측을 하나의 숫자 축으로 합치지 않는다.
- source의 원래 ordinal 상태 수와 label을 보존한다. 중간 단계를 임의 생성하지 않는다.
- area polygon/목록 변경을 static code enum으로 고정하지 않고 versioned mapping table로 관리한다.

### 변경/품질 위험

- [2026-03 변경 공지](https://data.seoul.go.kr/together/notice/datasetNoticeView.do?bbsCd=10008&ditcCd=&pageIndex=1&seq=5b81e643725fe82dc5a8ecaca38a3c7c)는 장소 추가·삭제·명칭 변경으로 목록이 122개로 바뀐 사실을 공지했다. 현재 상세 화면은 121개로 안내하므로 장소 수를 business invariant로 두면 안 된다.
- [2026-05 field 변경 공지](https://data.seoul.go.kr/together/notice/datasetNoticeView.do?bbsCd=10008&ditcCd=&pageIndex=1&seq=14fa5cad50638967fe9823ebdcff3222)는 원천 생산 중단에 따라 체감온도 field 삭제를 알렸다. unknown field 허용과 required field 최소화가 필요하다.
- [2026-07 품질 오류 공지](https://data.seoul.go.kr/together/notice/datasetNoticeView.do?bbsCd=10008&ditcCd=&pageIndex=1&seq=80d6f0ee7267f416f3f199e85d4da68e)는 약 8시간 동안 121곳 중 71곳이 통신 집계 누락으로 과소 추정됐고 별도 보정값을 제공하지 않는다고 알렸다.

따라서 값이 schema상 정상이어도 provider quality incident window에 포함되면 `qualityFlags=[PROVIDER_INCIDENT]`, `comparisonEligible=false`로 격리한다. 알려진 사고 window는 운영 override table에 등록하고 사후 분석/추천에 쓰지 않는다.

### 수집 전략

- P0 데모/active trip 관련 area의 allowlist부터 시작하고 전국/서울 전수 mirror를 가정하지 않는다.
- 한 장소씩 호출하는 제약과 승인 quota를 반영해 priority queue와 jitter를 둔다.
- `observedAt` 기준으로 latest row를 선택하고 API 수신 순서를 신선도로 착각하지 않는다.
- timeout/부분 field 누락 시 last-known-good는 STALE로만 제공한다.
- 데모 당일 readiness가 나쁘면 명시적 REPLAY로 전환한다.

## 6. Demo replay

Replay는 외부 장애를 숨기는 fallback이 아니라 별도의 data product다.

- fixture마다 source snapshot 원본 출처, 수집 허용 여부, scrub 방식, 시각 범위, schema version, checksum을 기록한다.
- manifest는 immutable UUID/version이며 승인자·승인시각, source registry revision, license snapshot, capture window, scrub method, file/record checksum과 entry 순서를 가진다. snapshot은 manifest junction에 명시적으로 속해야 한다.
- response의 `sourceState=REPLAY`, `selectorMode`, `snapshotSetId`, 재현 기준 시각을 제공한다.
- production에서 replay 강제 시 화면 상단에 persistent badge/banner를 표시한다.
- replay 값으로 “지금 한산하다” 알림을 보내지 않는다.
- 개인정보/기기/사용자 위치가 포함된 원본을 fixture로 저장하지 않는다.

## 7. Source registry 필수 필드

| Field | 의미 |
| --- | --- |
| `code` | API에서 사용하는 안정적 source code |
| `displayName` | 사용자 출처 표기 |
| `officialUrl` | 상세/약관의 공식 URL |
| `licenseName/licenseUrl` | 출처·변경·상업 이용 조건 |
| `licenseReviewState` | source 기본 license 또는 record별 review 필요 여부 |
| `sourceState` | LIVE/FORECAST/REPLAY/QUALITATIVE/STALE/UNAVAILABLE 의미 |
| `approvalState` | DEV_APPROVED/PROD_PENDING/PROD_APPROVED/DISABLED |
| `quotaPolicy` | 일/초당 승인량과 60/80/90 threshold |
| `scope` | PLACE/LIVE_AREA/REGION/ROUTE 등 |
| `metricDefinition` | 값의 단위와 해석 |
| `refreshExpectation` | 공식 설명과 관측된 주기 분리 |
| `staleAfter` | 승인된 freshness 경계 (forecast `PT24H`, place detail `P7D`) |
| `retentionPolicy` | 원본/정규화 snapshot 보존 허용 |
| `attributionTemplate` | UI/데이터 안내 문구 |
| `schemaVersion` | adapter가 검증한 provider schema |
| `registryVersion/contractHash` | snapshot이 참조하는 immutable canonical contract revision |
| `reviewedAt/reviewedBy` | 마지막 수동 검토 |
| `contestUse` | 제출 기능, 실제 provider operation, 화면 위치, 증거 ID |

`reviewedAt`이 release 기준보다 오래됐거나 official URL이 사라지면 readiness를 degraded로 하고 production flag를 자동으로 켜지 않는다.

registry 수정은 기존 row를 덮어쓰지 않고 version을 올린 immutable revision을 만든다. 각 normalized snapshot은 `(sourceCode, sourceRegistryVersion)`을 필수로 참조하므로 당시 metric/license/attribution/schema 의미를 재현할 수 있다. canonical contract hash는 approval·quota·license review·scope·retention·refresh·schema·stale·contest use까지 포함한다. `DISABLED`, stale policy 없음, 또는 승인되지 않은 source는 collector에서 선택할 수 없다. 공모전 `2026_KTO_WEBAPP` 빌드에서는 팀 결정에 따라 `DEV_APPROVED` KTO source도 허용하며, 이 예외는 실제 C2 KTO gateway가 등록되기 전 provider 호출을 허용한다는 뜻은 아니다.

### 품질 사고 registry

공식 공지나 관측으로 확인한 사고는 `sourceCode`, 안정적 `incidentCode`, affected from/to, scope, notice URL, disposition, reviewer/time으로 저장한다. affected window와 겹치는 snapshot은 schema가 정상이어도 `PROVIDER_INCIDENT` flag를 받고 비교·ranking·알림에서 제외된다. 종료 시각이 불명확하면 open-ended로 격리하고 명시적 review가 있어야 닫는다.

## 8. Normalization contract

```text
Provider response
  → transport validity (HTTP/encoding/body)
  → schema validity (required key/type/enum drift)
  → semantic validity (time/range/scope/quality incident)
  → canonical mapping (external id → place/area)
  → provenance enrichment
  → immutable snapshot set
  → comparison eligibility
  → product read model
```

단계별 failure reason을 남기되 provider body와 key는 로그하지 않는다. reject count/sample field name만 저장한다.

정규화 결과의 canonical provenance는 다음 필드를 모두 보존한다.

```text
provenanceId, sourceCode, sourceDisplayName, sourceRegistryVersion,
sourceState, observedAt|null, targetAt|null, fetchedAt, staleAt|null,
freshness, confidence|null, license, officialUrl|null, licenseUrl|null,
attribution, metricDefinition,
normalizationVersion, qualityFlags, forecastIssueId|null,
collectorRunId, snapshotSetId, scope, mappingType, fallbackUsed
```

`FORECAST`는 `targetAt/forecastIssueId`가 필수지만 provider 발표 시각이 없으면 `observedAt=null`이 정상이다. `fetchedAt`이나 batch 시작 시각을 관측 시각으로 복사하지 않는다. `UNAVAILABLE`만 `provenanceId`가 null일 수 있다.

## 9. Comparison eligibility reason code

| Code | 의미 | UI 기본 처리 |
| --- | --- | --- |
| `SAME_METRIC_AND_ISSUE` | 유효 temporal 비교 | delta 허용 |
| `SAME_SOURCE_SCOPE_SET` | 유효 spatial 비교 | delta/rank 허용 |
| `DIFFERENT_SOURCE` | source 다름 | 상태 병렬 표시만 |
| `DIFFERENT_SCOPE` | POI/area 등 범위 다름 | 수치 비교 금지 |
| `DIFFERENT_FORECAST_ISSUE` | 발표 batch 다름 | 다시 계산 |
| `STALE_INPUT` | stale threshold 초과 | stale 안내, 최적화 차단 |
| `REPLAY_INPUT` | replay 포함 | 시연 설명만, live claim 금지 |
| `QUALITATIVE_ONLY` | 정성/ordinal만 | 원본 label만 표시 |
| `MAPPING_UNCERTAIN` | area↔POI confidence 부족 | 후보 관계 약화 |
| `PROVIDER_INCIDENT` | 공식 품질 사고 window | 격리/비교 금지 |
| `MISSING_PROVENANCE` | 필수 metadata 없음 | 응답에서 수치 제외 |

## 10. Pair comparison 계약

delta는 snapshot 개별 flag가 아니라 **before/after pair**의 산출물이다. Backend/AI 담당은 comparison row에 두 snapshot ID, axis, eligibility, reason, nullable before/after/delta를 고정한다.

- `TEMPORAL`: 같은 canonical place, source, metric definition, forecast issue이며 target만 달라야 한다.
- `SPATIAL`: 같은 source, metric, scope, comparison group, snapshot set이어야 한다.
- 어느 한쪽이라도 STALE/REPLAY/incident/missing provenance면 `eligible=false`, `delta=null`이다.
- ordinal/qualitative 값을 임의 숫자로 바꿔 delta를 만들지 않는다.
- optimizer run은 사용한 모든 snapshot set과 pair를 junction으로 고정한다. 나중의 “latest” row로 preview를 재구성하지 않는다.

Frontend 담당은 `eligible=false`에서 delta/ranking 문구를 숨기고 reason별 안내를 표시한다. null을 0으로 바꾸거나 서로 다른 단위를 한 chart 축에 놓지 않는다.

## 11. Asset license 계약

관광지·post image는 URL 문자열만으로 배포하지 않는다. asset metadata는 source external ID, origin/served URL, checksum, media type/alt, 검토 시각과 검토된 license revision을 참조한다. license에는 attribution 문구/URL, redistribution/derivative 허용 여부를 둔다.

- `redistributionAllowed=false`: origin URL 정책이 허용할 때 직접 표시만 하고 S3/CDN mirror 금지.
- attribution required: API `MediaAsset`과 Figma 상세/데이터 안내에서 문구 표시.
- license 불명/만료: placeholder로 degrade하며 다운로드·캐시하지 않는다.
- provider record별 license가 다르면 source 기본값보다 record license가 우선한다.

## 12. 외부 데이터 착수 체크리스트

- [ ] 각 API 개발/운영 활용신청과 실제 quota 캡처
- [ ] 최신 공식 manual/schema/license 다운로드 또는 URL 기록
- [ ] secret을 Secrets Manager/local secret store에 등록
- [ ] 최소 fixture와 checksum, 저장 허용 여부 기록
- [ ] timeout/429/provider error mapping
- [ ] source별 observedAt/targetAt 의미 확인
- [ ] stale threshold와 collector schedule 부하 계산
- [ ] external ID/canonical POI/LiveArea mapping review
- [ ] attribution 문구를 S15 데이터 안내와 place detail에 반영
- [ ] provider 공지/품질 incident override 운영 절차
- [ ] replay 전환과 화면 label rehearsal
- [ ] source registry revision/contract hash와 snapshot FK 검증
- [ ] replay manifest checksum/license/scrub 승인 검증
- [ ] media asset별 redistribution/attribution fixture 검증
- [ ] before/after pair mixed-source property test

## 13. 공모전 제출용 KTO 증거 계약

[공식 공지 요약](../contest/2026-관광데이터-활용-공모전-공지-심사기준.md)은 파일 데이터만으로 필수 OpenAPI 활용을 대신할 수 없고 실제 호출 이력을 확인할 수 있다고 명시한다. 따라서 다음을 같은 release ID로 연결한다.

| 증거 | 저장 내용 | 금지 내용 | 확인자 |
| --- | --- | --- | --- |
| 활용 신청 | API명, 신청자, 개발/운영 상태, 승인 quota, 확인일 | 인증키 원문 | Backend/AI, Frontend 확인 |
| call-audit | source/operation, 시작·종료시각, outcome, count, request/collector ID, release ID | key, 전체 URL query, provider 원문 body, 사용자 입력 | Backend/AI |
| 서비스 사용 | operation → normalized field → OpenAPI response → Figma node mapping | mock-only 화면을 실제 활용으로 표시 | 공동 |
| 화면 출처 | `출처: ⓒ한국관광공사`, 기준시각, source state screenshot/DOM test | `TourAPI` 단독, 무허가 CI·BI | Frontend |
| 제출 목록 | 최종 배포에서 실제 호출한 operation만 공식 기능설명서에 기재 | 계획·disabled API 기재 | 공동 |

- call-audit는 비밀값 없는 내부 운영 증거이며 공개 기능설명서에 trace/request identifier를 노출하지 않는다.
- local DB에는 제품 동작에 필요한 최소 정규화 record와 TTL만 둔다. 전체/장기 복제가 불가피하면 구현 전에 공모전 문의처에 질의하고 별도 신청서·답변·허용 범위를 비공개 evidence ledger에 보관한다.
- PR Docker test는 deterministic fixture와 외부망 차단을 사용한다. staging/submission gate는 승인된 runtime secret으로 `실제 call → validator → 공개 response → UI attribution`을 확인한다.
- replay는 외부 장애 대비로 유지하지만 공모전 필수 KTO 활용 증거로 세지 않는다.

## 14. 운영 검토 주기

- 매일 자동: freshness, schema drift, quota, HTTP/provider error.
- 매주: mapping reject, comparison eligibility, fallback rate.
- release 전: official detail/manual/notice와 approval state 수동 확인.
- 공모전 code freeze 전: 최종 배포 URL의 실제 KTO operation·call-audit·화면 출처를 2인 교차 확인.
- 분기: license/retention/attribution, unused source, API version review.
- provider 공지 발생 즉시: affected field/area/window와 adapter/fixture/update plan 기록.

## 15. #11 카드 출처와 외부 링크 제안 (2026-09-06)

[계약 packet](../contracts/review-2026-09-06/README.md)에 KTO 관광정보 QUALITATIVE와 집중률 FORECAST 예시를 추가했다. 두 공식 상세의 이용허락범위는 공공데이터포털 정책 페이지에 연결됨을 09-06에 확인했다. 관광정보 텍스트의 안내와 이미지별 공공누리 1/3유형 심사를 분리한다.

| Source | officialUrl | licenseUrl | 좁은 카드 문구 |
| --- | --- | --- | --- |
| KTO_KOR_SERVICE_2 | [공식 관광정보](https://www.data.go.kr/tcs/dss/selectApiDataDetailView.do?publicDataPk=15101578) | [포털 정책](https://data.go.kr/ugs/selectPortalPolicyView.do) | 출처: ⓒ한국관광공사 |
| KTO_CONCENTRATION_FORECAST | [공식 집중률 예측](https://www.data.go.kr/data/15128555/openapi.do) | [포털 정책](https://data.go.kr/ugs/selectPortalPolicyView.do) | 출처: ⓒ한국관광공사 |
| SEOUL_CITYDATA | [공식 도시데이터](https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do) | [공공누리 1유형](https://www.kogl.or.kr/info/licenseType1.do) | 출처: 서울특별시 |

DataProvenance.attributionShort는 선택 nullable, 유효한 문구는 1~160자다. 없거나 null이면 full attribution을 표시한다. 줄인 문구를 쓰더라도 같은 카드의 접근 가능한 출처 상세에 full attribution·officialUrl·licenseUrl을 제공한다. 긴 문구를 FE가 임의로 잘라 필수 credit을 없애지 않는다. 링크를 확인하지 못한 future source는 URL을 추측하지 않고 null·미확인 안내와 source 검토 항목을 남긴다.

브라우저용 officialUrl/licenseUrl은 [source-link-policy.json](../contracts/review-2026-09-06/source-link-policy.json)의 exact host만 허용한다: `data.seoul.go.kr`, `www.kogl.or.kr`, `data.go.kr`, `www.data.go.kr`, `api.visitkorea.or.kr`. https만 허용하며 상대 URL, userinfo, 비기본 port, wildcard/subdomain 추정은 거부한다. 표에 있는 공개 query parameter는 유지한다. 외부 링크는 안전한 새 창 속성을 적용한다. source registry 등록/갱신 때 redirect chain과 최종 URL도 같은 host 정책으로 확인한다. 브라우저 anchor만으로 이후 모든 redirect를 통제할 수 있다고 가정하지 않는다. provider 서버 호출의 SSRF allowlist와는 별개의 표시 링크 정책이다.

이 필드·host 정책은 #11의 FE 검토 대상이다. `freshness=UNKNOWN`인 합성 예시는 실제 호출이나 비교 적격 판정 증거가 아니며 KTO 예측을 인원·5단계·시간대 그래프로 변환하는 근거로 쓰지 않는다.
