---
aliases:
  - "제출 inventory ledger 초안"
doc_type: reference
status: draft
area: project
tags:
  - nullnull/reference
  - nullnull/project
---

# 제출 inventory ledger 초안

`scripts/check_submission_inventory.py --ledger`가 읽는 JSON의 초안이다. 데이터는 [`submission-ledger.draft.json`](./submission-ledger.draft.json)에 있고, 이 문서는 그 근거와 남은 결정을 적는다. 쓰는 법은 [제출 runbook의 ledger 만들기](./SUBMISSION_RUNBOOK.md#ledger-만들기)에 있다.

**이 문서는 주장이지 증거가 아니다.** 기능 문구와 API 목록은 PDF에서 옮겼고, 기능 문구를 기능 ID로 잇는 매핑은 이 초안의 판단이다.

이전 판은 PDF가 아니라 기능 인벤토리를 기준으로 P0 83개를 14개 묶음으로 나눴고, `pdfLabel`은 비어 있었다. 그 판은 `b3e3d9d6`까지의 이력에 있다.

## 1. 근거 PDF

- 파일: `Nullnull_기능설명서_상세개정본_수정본.pdf`(17쪽)
- sha256: `16f59996732b118f9ccf856f91c2b2455c6e09bb4cad8eff4abdb0d09e859408`
- PDF 생성 시각: 2026-09-21 15:41:30 KST(`pdfinfo`)
- 이 파일이 접수된 첨부와 같은지는 `[미확인]`이다. 접수 증거의 PDF checksum(CMP-SUB-001)과 대조한다.
- 옮긴 범위는 둘이다.
  - p5~p8 "해시태그 연계 핵심기능 및 상세내용" 표의 "연계 기능" 열 12개 → `features[]`
  - p14 "서비스 개발에 활용한 한국관광공사 OpenAPI 리스트" 3개 → `ktoOperations[]`
- 문구는 PDF 쪽 이미지를 보고 확인했다. 텍스트 추출은 표의 열 순서를 섞기 때문이다.

## 2. 기능

p5~p8 "연계 기능" 열 12개다. `pdfLabel`은 PDF 문구를 그대로 옮겼다. 근거 문장도 PDF 원문이다.

| 해시태그(쪽) | `pdfLabel` | `featureIds` | `capability` | 근거 문장 |
| --- | --- | --- | --- | --- |
| #취향기반발견(p5) | 여행 취향 입력 | FR-TRC-01, FR-TRC-02 | `null` | "여행 날짜와 관심사를 입력합니다."(p9) |
| #취향기반발견(p5) | SNS형 피드 탐색 | FR-FED-01, FR-FED-02, FR-FED-03 | `null` | "현재 피드 정렬은 게시 시각 기준이며" |
| #취향기반발견(p5) | 개인화 노출 고도화 | **넣지 않음** | — | "취향·일정에 따른 노출 순위 연결은 핵심 고도화 과제입니다." |
| #자연스러운장소분산(p6) | 관광 콘텐츠 탐색 | FR-PST-01, FR-PLC-01 | `null` | "피드의 게시물에서 장소와 출처를 확인하고" |
| #자연스러운장소분산(p6) | 게시물·장소 저장 | FR-PST-02, FR-CAN-01, FR-CAN-02 | `null` | "관심 있는 장소를 원하는 여행의 후보로 저장합니다." |
| #자연스러운장소분산(p6) | 여행별 후보 비교 | FR-CAN-05, FR-ITM-08 | `null` | "일정 관리에서는 후보를 추가하거나 기존 장소와 비교하고"(p3) |
| #명소유지시간분산(p7) | 방문 희망 명소 유지 | FR-TRC-04, FR-CON-01 | `null` | "꼭 방문하고 싶은 장소를 후보로 유지합니다."(p11) |
| #명소유지시간분산(p7) | 날짜별 혼잡 예측 | FR-DAT-05, FR-CAN-07 | `null` | "한국관광공사의 날짜별 상대 집중률 예측과 출처·수집시각을 보여줍니다. 일정 조건상 선택할 수 없는 날짜는 구분하고" |
| #명소유지시간분산(p7) | 변경안 검토 | FR-OPT-01, FR-OPT-03, FR-OPT-04, FR-OPT-05, FR-OPT-06 | `optimization` | "장소 하나의 최적화 제안을 검토하는 흐름을 제공합니다."(p8) |
| #발견과일정연결(p8) | 게시물·후보·일정 분리 | FR-PST-02, FR-CAN-02, FR-TRP-01 | `null` | "게시물 저장은 콘텐츠를 다시 보기 위한 기능이고, 여행 후보 저장은 방문을 검토하기 위한 기능입니다." |
| #발견과일정연결(p8) | 일정 추가·교체 | FR-ITM-02, FR-ITM-07, FR-ITM-08 | `null` | "일정 관리에서는 후보의 추가·교체와" |
| #발견과일정연결(p8) | 사용자 승인형 변경 | FR-OPT-07, FR-OPT-08, FR-OPT-09 | `optimization` | "이미 세운 일정은 사용자가 변경을 확정하기 전까지 유지합니다." |

- **"개인화 노출 고도화"는 넣지 않았다.** PDF가 같은 칸에서 이것을 고도화 과제라고 적고, p9 2단계도 *"향후 구현 범위"* 라고 적는다. 기능 인벤토리에서 가장 가까운 행은 `FR-ML-01`(개인화 ranking, **P2**)이다. 넣으면 검사기가 CMP-SUB-008(PDF에는 P0만)로 막는다. 넣지 않은 결정은 §4의 오너 결정 대상이다.
- **P0인데 여기 없는 기능은 PDF가 주장하지 않은 것이다.** 온보딩, session, 프로필이 그렇다. 검사기는 PDF가 적은 것만 대조하므로 빠져도 실패가 아니다.
- p9~p12의 흐름도 문장은 기능 목록이 아니라 설명이라 항목으로 옮기지 않았다. 예외 하나는 §4에 적었다.
- 같은 ID가 두 문구에 나오는 것(`FR-PST-02`, `FR-CAN-02`, `FR-ITM-08`)은 검사기가 허용한다.

## 3. KTO OpenAPI

p14의 목록 3개다.

| 번호 | `pdfLabel`(API명) | PDF 상세설명 | `source` / `endpoint` | `usedBy` |
| --- | --- | --- | --- | --- |
| 1 | 한국관광공사 국문 관광정보 서비스_GW (KorService2) | "detailCommon2: 장소명·주소 등 관광정보를 피드의 장소 연결과 여행 후보·일정에 활용" | `KTO_KOR_SERVICE_2` / `KOR_SERVICE_2_DETAIL_COMMON_2` | FR-PST-01, FR-PLC-01, FR-CAN-02, FR-ITM-02 |
| 2 | 한국관광공사 관광지 집중률 방문자 추이 예측 정보 | "tatsCnctrRateList: 날짜별 상대 집중률 예측으로 명소 방문 날짜를 검토하는 데 활용" | `KTO_CONCENTRATION_FORECAST` / `TATS_CNCTR_RATE_LIST` | FR-DAT-05 |
| 3 | 한국관광공사 영문 관광정보 | "국문에서 가진 장소 ID가 영문 데이터에도 있고 영어로 오는지를 detailCommon2로 한 번 측정하는 데 쓰는 API입니다." | `KTO_ENG_SERVICE` / `ENG_SERVICE_2_DETAIL_COMMON_2` | FR-PLC-01 |

- `source`·`endpoint`는 inventory가 찍는 값이다. 한국관광공사 operation 이름이 아니라 내부 감사 키(`api_ingest_logs.endpoint_key`)다.
  - 1·2: `backend`의 `KtoPlaceDetailGateway`·`KtoCrowdForecastGateway`에 있다.
  - 3: #360의 `KtoEngTextRefresh`에만 있다.
- **3번은 지금 `backend`의 어떤 release도 inventory에 낼 수 없다.** PDF가 말하는 "한 번 측정"은 `KtoEngServiceProbeMain`이고, 그 javadoc이 *"no audit row"* 라고 적는다. 감사 행을 남기는 영문 호출은 #360(`KtoEngTextRefresh`)과 그것을 돌리는 operator task(#367)가 들어와야 생긴다. 오너 결정 (a)에 따라 최종 release에서 그 호출을 돌린다(§4).
- **3번의 `usedBy`는 `FR-PLC-01`이다.** PDF는 이 API에 기능을 잇지 않았으므로 이 값은 PDF 문구가 아니라 오너 결정 (a)에서 왔다. #360이 받아 온 영문 텍스트는 장소 상세에 나온다.
- p15(기타 API)는 검사기 대상이 아니다. inventory가 `KTO_` source만 센다(`JdbcKtoCallInventoryQuery`). 다만 읽다가 본 것이 하나 있다. 1번 "서울 실시간 도시데이터"의 상세설명이 2번 "카카오모빌리티 길찾기"의 상세설명과 같은 문장이다. PDF는 마감 뒤 고칠 수 없다(CMP-SUB-001).

## 4. 오너 결정

1. **p14 3번(영문 API) — 결정됨: (a).** 2026-09-24 오너가 직접 정했다. 최종 release에서 영문 호출을 돌려 inventory에 나오게 하고, `usedBy`는 `FR-PLC-01`로 한다. 그래서 다음이 필요하다.
   - 최종 release에 #360·#367이 들어 있다.
   - 오너가 영문 연결 plan의 바이트를 승인한다(`kto-eng-link-import`).
   - 오너가 영문 KTO 실호출을 승인한다(`kto-eng-text-refresh`).
   - 수용안이었던 (b)는 CMP-KTO-006(공식 필수)을 알고도 미충족으로 두는 것이라 택하지 않았다.
2. **"개인화 노출 고도화"를 원장에서 뺀 것.** 계획 기능은 PDF에 적지 않는다는 것이 CMP-SUB-008이다. 이 문구가 PDF에 있다는 사실 자체는 원장이 바꿀 수 없다.
3. **Live 기능(`FR-LIV-*`)을 넣지 않은 것.** "연계 기능" 열에 Live가 없다. Live 쪽으로 읽힐 수 있는 문장은 p11 4단계 하나다: *"현재 관측이 없는 상태는 별도 표시합니다. 날짜 예측과 실시간 관측을 섞지 않습니다."*
4. **§2 매핑 전체.** 검사기 docstring이 형식을 *"draft, pending owner/FE agreement"* 라고 적는다. 매핑도 오너·FE 검토 대상이다.

## 5. 합성 입력으로 돌린 결과

**증거가 아니다.** `fd52d312`(`origin/backend`)에서 `check_submission_inventory.py`를 돌렸다.

- readiness와 inventory는 손으로 만든 합성 입력이고, release 이름은 `v0.0.0-synthetic`이다.
- ledger는 [ledger 만들기](./SUBMISSION_RUNBOOK.md#ledger-만들기)의 명령으로 이 초안에서 만들었다.

| 경우 | 입력 | 결과 |
| --- | --- | --- |
| S1 | inventory에 1·2번만 있다(지금 `backend`가 낼 수 있는 모양) | exit 1. `the PDF lists KTO_ENG_SERVICE/ENG_SERVICE_2_DETAIL_COMMON_2, which the release never called usably (CMP-KTO-006)` |
| S2 | inventory에 셋 다 있다(결정 (a)가 끝난 release의 모양) | exit 0. `submission_inventory=verified release=v0.0.0-synthetic`. §2의 ID가 모두 P0로 통과했다 |
| S3 | S2와 같고 readiness의 `optimization`만 `UNAVAILABLE` | exit 1. "변경안 검토"·"사용자 승인형 변경" 두 줄 |
| S4 | 초안을 명령 없이 그대로 넣었다(`releaseVersion`이 `<RELEASE>`) | exit 1. release 불일치 |
| S5 | 예전 절차대로 `ktoOperations`를 inventory(1·2번)에서 뽑았다. PDF는 셋을 적는다 | **exit 0. `verified`** |

**S5가 예전 절차의 결함이다.** 대조하는 두 목록이 같은 출처에서 오면, PDF가 적었는데 release가 부르지 않은 API가 원장에 아예 없다. 그래서 CMP-KTO-006의 *"PDF API 목록 ↔ audit operation set diff 0"* 이 공허하게 통과한다. 이 초안은 `ktoOperations`를 PDF에서 옮겼고, runbook도 그렇게 바꿨다.

## 6. 검사기가 검증하지 않는 것

- **`capability: null`은 아무것도 검사하지 않는다.** 검사기는 `null`인 기능의 readiness를 보지 않는다. 예보(`날짜별 혼잡 예측`)에는 readiness capability가 없으므로 `null`이다. 예보가 실제로 도는 증거는 inventory의 2번 행이다.
- **`pdfLabel`은 검사하지 않는다.** 이 초안은 문구를 PDF에서 그대로 옮겼다. independent checker가 PDF를 옆에 두고 §2·§3과 대조한다.
- **매핑이 맞는지는 검사하지 않는다.** 검사기는 ID가 P0인지와 capability만 본다.
- **APPLY가 실제로 되는지는 검사하지 않는다.** `optimization` READY는 capability 플래그다. PDF p8도 *"성공 제안·적용은 별도 검증이 필요합니다"* 라고 적는다. 그 증거는 외부망 완주(`BA-073-T1`)다.
- **`--ledger`·`--readiness`·`--inventory`는 셋 다 필수다.** 하나만 주면 argparse가 exit 2를 낸다. 그건 통과도 거절도 아니다.
