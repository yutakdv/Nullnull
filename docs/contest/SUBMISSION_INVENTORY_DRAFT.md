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

`scripts/check_submission_inventory.py --ledger` 가 읽을 JSON 의 **사람이 채우는 칸** 초안이다.
`releaseVersion` 과 `ktoOperations[].source`·`endpoint` 는 운영 절차가 kto-inventory 출력에서 뽑고,
`features[]` 와 `usedBy` 는 사람이 적는다([제출 runbook](../contest/SUBMISSION_RUNBOOK.md)).

**이 문서는 주장이지 증거가 아니다.** 아래 §3 을 읽기 전에 이 JSON 을 제출에 쓰지 않는다.

## 1. 기재 후보 — P0 98 개 중 83 개

기능 인벤토리의 P0 는 98 개다(측정: 113 행 중 P0 98 · P1 13 · P2 2).
그중 **15 개를 뺐다**(§2). 남은 83 개를 PDF 항목 단위로 14 개 묶음에 넣었다.

| 묶음 | 기능 수 | 내용 | `capability` |
| --- | --- | --- | --- |
| `FR-ONB` | 3 | 온보딩·언어 선택 | `null` |
| `FR-SES` | 4 | 익명 session·보호·삭제 | `null` |
| `FR-PRO` | 5 | 프로필·내 여행·최적화 이력 | `null` |
| `FR-DAT` | 5 | 데이터 출처·비교 적격성·혼잡 예보 | `null` |
| `FR-TRC` | 11 | 여행 만들기(날짜·관심사·붙여넣기·초안) | `null` |
| `FR-FED` | 4 | 피드 | `null` |
| `FR-PST` | 2 | 게시물 열람·저장 | `null` |
| `FR-PLC` | 1 | 표준 장소 상세 | `null` |
| `FR-CAN` | 7 | 여행 후보 저장·조회 | `null` |
| `FR-TRP` | 5 | 여행 조회·수정·삭제 | `null` |
| `FR-ITM` | 7 | 일정 항목 추가·이동·교체 | `null` |
| `FR-CON` | 6 | 일정 잠금·충돌 복구 | `null` |
| `FR-OPT` | 15 | AI 일정 최적화(preview→APPLY/KEEP/REVERT) | `optimization` |
| `FR-OPS` | 8 | 운영(readiness·수집·로그·삭제 TTL·flag) | `null` |

`capability` 규칙은 runbook 이 정한다 — 그 기능이 `live`·`replay`·`optimization` 없이 동작하지 않으면 그 이름,
아니면 `null`. `FR-OPT-*` 가 `optimization` 이다.

## 2. 뺀 15 개와 이유

`backend-plan.json` · `frontend-plan.json` 의 카드 status 를 기능 ID 로 join 해서 냈다
(측정: 카드 없는 P0 **0 개**, 전부 미완 **14 개**, 일부만 완료 **1 개**).

| 기능 ID | 제목 |
| --- | --- |
| `FR-ITM-04` | 시간/duration 수정 |
| `FR-LIV-01` | area 목록과 capability-gated 지도 선택 유지 |
| `FR-LIV-02` | area별 장소 조회 |
| `FR-LIV-03` | Live 장소 상세 |
| `FR-LIV-04` | 대체 장소 목록 |
| `FR-LIV-05` | 유효 대안 없음 |
| `FR-LIV-06` | 확인 중/불명 관계 |
| `FR-LIV-07` | replay demo |
| `FR-LIV-08` | stale/unavailable degradation |
| `FR-LIV-09` | Live 장소를 후보로 저장 |
| `FR-LIV-11` | 장소명 검색 후 Live coverage 조회 |
| `FR-OPS-07` | backup/PITR/restore |
| `FR-OPS-08` | API/web rollback |
| `FR-OPS-11` | KTO 실제 호출과 비밀값 없는 call-audit |
| `FR-OPS-12` | 공모전 익명 외부망 smoke와 기능설명서 정합성 |

- `FR-LIV-*` 10 개 — `BA-090`·`BA-091`·`BA-092` 가 전부 `planned` 이고 `FE-401`~`403` 도 `planned` 다.
  `queryLiveAreas`·`listLiveAreaPlaces`·`getLivePlace` 는 계약에 선언만 있고 `apps/api` 구현이 0 건이며,
  `live` flag 는 켜면 startup 이 죽는다.
- `FR-OPS-07`·`08`·`11` — `BA-072`·`BA-071`·`BA-021` 이 `in-progress` 다.
- `FR-OPS-12` — `BA-073` 이 `planned` 이고 `FE-604` 가 `blocked` 다.
- `FR-ITM-04` — `BA-040` 은 `integration-ready` 인데 `FE-308` 이 `deferred` 다. 서버는 받지만 화면에 편집 수단이 없다.

## 3. 검사기가 **검증하지 않는 것** — 채우는 사람이 지켜야 한다

- **`capability: null` 은 아무것도 검사하지 않는다.** `check_submission_inventory.py:79` 가 `null` 이면
  readiness 를 보지 않는다. 미구현 기능을 `null` 로 적은 ledger 가 exit 0 으로 통과하는 것을 실측했다.
  **게이트 초록은 기재 내용이 참이라는 뜻이 아니다.**
- **`pdfLabel` 은 한 번도 검증되지 않는다.** 기능 쪽은 오류 메시지 텍스트로만 읽고, operation 쪽은 아예 안 읽는다.
  비어 있어도 통과한다. 위 `[오너 교체]` 표시를 **반드시 PDF 문구로 바꾼다.**
- **`endpoint` 는 한국관광공사 operation 이름이 아니다.** 내부 감사 키 `api_ingest_logs.endpoint_key` 이고,
  `apps/api` 가 실제로 내는 값은 `KOR_SERVICE_2_DETAIL_COMMON_2` 와 `TATS_CNCTR_RATE_LIST` 둘뿐이다.
  검사기 docstring 과 그 test fixture 는 `_2` 없는 `KOR_SERVICE_2_DETAIL_COMMON` 을 보여주는데,
  **그것을 그대로 옮기면 양방향 대조에서 실패한다.** provider 이름(`detailCommon2`)은 PDF 문구 쪽에 쓴다.
- **오늘의 증거로는 어떤 ledger 도 통과하지 못한다.** 디스크의 유일한 inventory 가
  `operations=0 counts_as_evidence=false reason=no-usable-call` 이다. 제출 release 에서 KTO 실호출을 먼저 하고
  그 release 의 inventory 를 새로 뽑아야 한다.
- **`--ledger`·`--readiness`·`--inventory` 는 셋 다 필수다.** 하나만 주면 argparse 가 exit 2 를 내는데,
  그건 통과도 거절도 아니다.
- **ledger 형식 자체가 draft 다.** 검사기 docstring 이 *"pending owner/FE agreement"* 라고 적고 있고,
  그 합의가 이뤄졌다는 기록이 저장소에 없다.
- **83 개가 배포본에서 동작한다는 것은 측정되지 않았다.** 근거는 계획 카드의 status 뿐이고,
  그것은 JUnit 집계 상태이지 배포된 release 에 대한 관측이 아니다.

## 4. 초안 JSON

`usedBy` 는 `features[]` 에 있는 ID 만 쓸 수 있고 비어 있으면 거절된다.
아래는 `FR-PLC-01`(표준 장소 상세) 하나만 넣은 최소 형태이며, **다른 기능이 그 API 를 쓰면 오너가 추가한다.**

```json
{
  "submissionInventory": {
    "releaseVersion": "[오너 교체] kto-inventory 의 release= 값과 글자까지 같게",
    "features": [
      {
        "featureIds": [
          "FR-ONB-01",
          "FR-ONB-02",
          "FR-ONB-03"
        ],
        "pdfLabel": "[오너 교체] 온보딩·언어 선택",
        "capability": null
      },
      {
        "featureIds": [
          "FR-SES-01",
          "FR-SES-02",
          "FR-SES-03",
          "FR-SES-04"
        ],
        "pdfLabel": "[오너 교체] 익명 session·보호·삭제",
        "capability": null
      },
      {
        "featureIds": [
          "FR-PRO-01",
          "FR-PRO-02",
          "FR-PRO-03",
          "FR-PRO-04",
          "FR-PRO-05"
        ],
        "pdfLabel": "[오너 교체] 프로필·내 여행·최적화 이력",
        "capability": null
      },
      {
        "featureIds": [
          "FR-DAT-01",
          "FR-DAT-02",
          "FR-DAT-03",
          "FR-DAT-04",
          "FR-DAT-05"
        ],
        "pdfLabel": "[오너 교체] 데이터 출처·비교 적격성·혼잡 예보",
        "capability": null
      },
      {
        "featureIds": [
          "FR-TRC-01",
          "FR-TRC-02",
          "FR-TRC-03",
          "FR-TRC-04",
          "FR-TRC-05",
          "FR-TRC-06",
          "FR-TRC-07",
          "FR-TRC-08",
          "FR-TRC-09",
          "FR-TRC-10",
          "FR-TRC-12"
        ],
        "pdfLabel": "[오너 교체] 여행 만들기(날짜·관심사·붙여넣기·초안)",
        "capability": null
      },
      {
        "featureIds": [
          "FR-FED-01",
          "FR-FED-02",
          "FR-FED-03",
          "FR-FED-04"
        ],
        "pdfLabel": "[오너 교체] 피드",
        "capability": null
      },
      {
        "featureIds": [
          "FR-PST-01",
          "FR-PST-02"
        ],
        "pdfLabel": "[오너 교체] 게시물 열람·저장",
        "capability": null
      },
      {
        "featureIds": [
          "FR-PLC-01"
        ],
        "pdfLabel": "[오너 교체] 표준 장소 상세",
        "capability": null
      },
      {
        "featureIds": [
          "FR-CAN-01",
          "FR-CAN-02",
          "FR-CAN-03",
          "FR-CAN-04",
          "FR-CAN-05",
          "FR-CAN-06",
          "FR-CAN-07"
        ],
        "pdfLabel": "[오너 교체] 여행 후보 저장·조회",
        "capability": null
      },
      {
        "featureIds": [
          "FR-TRP-01",
          "FR-TRP-02",
          "FR-TRP-03",
          "FR-TRP-04",
          "FR-TRP-05"
        ],
        "pdfLabel": "[오너 교체] 여행 조회·수정·삭제",
        "capability": null
      },
      {
        "featureIds": [
          "FR-ITM-01",
          "FR-ITM-02",
          "FR-ITM-03",
          "FR-ITM-05",
          "FR-ITM-06",
          "FR-ITM-07",
          "FR-ITM-08"
        ],
        "pdfLabel": "[오너 교체] 일정 항목 추가·이동·교체",
        "capability": null
      },
      {
        "featureIds": [
          "FR-CON-01",
          "FR-CON-02",
          "FR-CON-03",
          "FR-CON-04",
          "FR-CON-05",
          "FR-CON-06"
        ],
        "pdfLabel": "[오너 교체] 일정 잠금·충돌 복구",
        "capability": null
      },
      {
        "featureIds": [
          "FR-OPT-01",
          "FR-OPT-03",
          "FR-OPT-04",
          "FR-OPT-05",
          "FR-OPT-06",
          "FR-OPT-07",
          "FR-OPT-08",
          "FR-OPT-09",
          "FR-OPT-10",
          "FR-OPT-11",
          "FR-OPT-12",
          "FR-OPT-13",
          "FR-OPT-14",
          "FR-OPT-15",
          "FR-OPT-16"
        ],
        "pdfLabel": "[오너 교체] AI 일정 최적화(preview→APPLY/KEEP/REVERT)",
        "capability": "optimization"
      },
      {
        "featureIds": [
          "FR-OPS-01",
          "FR-OPS-02",
          "FR-OPS-03",
          "FR-OPS-04",
          "FR-OPS-05",
          "FR-OPS-06",
          "FR-OPS-09",
          "FR-OPS-10"
        ],
        "pdfLabel": "[오너 교체] 운영(readiness·수집·로그·삭제 TTL·flag)",
        "capability": null
      }
    ],
    "ktoOperations": [
      {
        "source": "KTO_KOR_SERVICE_2",
        "endpoint": "KOR_SERVICE_2_DETAIL_COMMON_2",
        "pdfLabel": "[오너 교체] 한국관광공사 TourAPI 관광정보 상세조회",
        "usedBy": [
          "FR-PLC-01"
        ]
      }
    ]
  }
}
```
