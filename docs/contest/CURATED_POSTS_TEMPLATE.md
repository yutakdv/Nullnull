---
aliases:
  - "P0 feed 게시물 큐레이션 서식"
doc_type: runbook
status: active
area: contest
tags:
  - nullnull/contest
  - nullnull/runbook
---

# P0 feed 게시물 큐레이션 서식

제출 대표 이미지(#1)는 `/feed`다. 지금 그 화면은 **게시물이 0건**이라 "아직 보여드릴
게시물이 없어요"를 보여준다. 이 문서는 그 자리를 채울 파일을 사람이 쓸 수 있게 서식과
거절 규칙을 한곳에 모은다.

기계는 준비돼 있다(A-031 `CuratedPostImporter`, `V021`/`V022`/`V025`). 없는 것은 **내용**이다.

## 파일 위치와 실행

서식 원본은 `apps/api/src/integrationTest/resources/curation/curated-posts.sample.json`이고,
실제 계획 파일의 경로·실행 명령은 Backend/AI가 소유한다([#183](https://github.com/yutakdv/Nullnull/issues/183)).
이 문서는 **무엇을 쓰면 통과하는지**만 정한다.

## 한 건의 서식

```json
{
  "posts": [
    {
      "id": "019321b0-0000-7000-8000-000000000001",
      "title": "가을 서울 산책 코스",
      "body": "고궁에서 시작해 골목으로 이어지는 하루 코스예요.",
      "publishedAt": "2026-09-09T02:00:00Z",
      "cover": {
        "url": "https://assets.nullnull.test/covers/autumn-seoul-walk.png",
        "alt": "고궁 담장을 걷는 사람들 일러스트",
        "checksum": "1111111111111111111111111111111111111111111111111111111111111111"
      },
      "places": [
        { "placeId": "<카탈로그에 이미 있는 장소 UUID>", "primary": true },
        { "placeId": "<카탈로그에 이미 있는 장소 UUID>", "primary": false }
      ]
    }
  ]
}
```

## 거절 규칙 — 어기면 importer가 파일 전체를 거부한다

`CuratedPostPlan`이 쓰기 전에 검사하므로, 틀리면 **아무것도 저장되지 않고** 멈춘다.

| 항목 | 규칙 |
| --- | --- |
| `posts` | 최소 1건. 같은 `id`가 두 번 나오면 거절 |
| `title` | 필수, 공백만은 안 되고 200자 이하 |
| `body` | 필수, 20000자 이하 |
| `publishedAt` | 필수, ISO-8601 instant |
| `cover.url` | 필수, **https만** |
| `cover.checksum` | **소문자 hex 64자**(파일의 sha256). 같은 이미지를 두 번 저장하지 못하게 하는 값이라 실제 파일에서 뽑아야 한다 |
| `cover.alt` | 필수. 이미지를 못 보는 사람에게 무엇이 보이는지 쓴다 |
| `places` | 최소 1건, **`primary: true`가 정확히 하나**, 같은 장소를 두 번 넣지 않는다 |

**`placeId`는 이미 카탈로그에 있는 장소여야 한다.** `CuratedPostImporter:136`이
`catalog.summaries(...)`로 조회하고, 없으면 *"the catalog does not have as an active canonical
row; a curated post cannot create a place"* 로 거절한다. importer는 외부 호출을 하지 않고 장소
행을 만들지 않는다.

> **그래서 이 작업에는 선행 조건이 있다 — 카탈로그가 먼저 채워져야 한다.**
>
> `packages/contracts/fixtures`의 장소 UUID는 **FE mock 값이라 쓸 수 없다.** 실제 UUID는 KTO
> 수집이 만든 canonical 행에서 나오고, 그것은 `BA-021`(KTO 실제 gateway) 소관이다. 지금
> `backend-plan.json` 기준 `BA-021`은 `in-progress`이고, `SUBMISSION_RUNBOOK:124`도
> *"catalog 게이트가 열린 뒤여야 실데이터가 나오므로 1·2·3·5번은 그 이후에 찍는다"* 고 적는다.
>
> 즉 **본문·제목·표지는 지금 쓸 수 있고, `placeId`만 게이트가 열린 뒤 채운다.** 먼저 쓰고
> 나중에 id를 넣는 순서가 가능하므로, 이 문서는 그 전제로 읽으면 된다.

## 내용을 쓸 때 지켜야 하는 것

### 1. 영업시간을 모르면 비워둔다

`BA-022` step 3이 날짜 단위로 영업 여부를 저장한다. `restdate`류 문장은
*"매주 화요일 ※ 단, 공휴일과 겹칠 경우…"* 처럼 **휴무를 다른 날로 옮길 수 있다.**
화요일만 비우고 나머지를 OPEN으로 채우면 **관측하지 않은 OPEN**이 생긴다.

P0 slot 판정에는 시각이 없어서 **OPEN 행 하나가 곧 "지금 갈 수 있음"** 이다. 완충이 없다.
그러니 그 날짜에 대해 사람이 해소하지 못했으면 **행을 만들지 않는다** — 없는 것은
`UNKNOWN`으로 읽히고, 잘못 채운 것은 **사실로 읽힌다.**

### 2. 출처를 지어내지 않는다

본문에 장소 정보를 쓸 때, 한국관광공사 데이터에서 온 문장과 사람이 쓴 소개를 섞지 않는다.
화면은 KTO 출처를 `sourceAttribution`으로 따로 표시하며, 본문에 출처 문구를 직접 적으면
**같은 화면에 두 개의 다른 출처 표기**가 생긴다.

### 3. 혼잡도를 본문으로 주장하지 않는다

*"이 시간엔 한산해요"* 같은 문장은 근거 없는 혼잡 주장이다(불변식 8). 혼잡은 서버가
provenance와 함께 주는 값으로만 표시한다. 본문은 **동선과 분위기**를 쓴다.

### 4. 표지는 1st-party 이미지만

`NULLNULL_FIRST_PARTY` 자산이어야 한다. 외부 사진을 쓰려면 라이선스 심사가 먼저이고,
심사되지 않은 이미지는 `attributionRequired`가 참인데 문구가 없어 **화면이 표지를 아예
숨긴다**(CMP-ATT-001, `PostScreen`).

형식은 **팀이 직접 촬영한 사진**이거나 **명시적 일러스트**다(A-024, 2026-09-18 개정).
금지 대상은 사진 형식이 아니라 **실제 장소를 사진처럼 지어낸 합성 이미지**다 — 그것은
보는 사람이 관측으로 읽어 불변식 6을 깬다. 직접 찍은 사진은 관측이므로 해당하지 않는다.

## 몇 건이 필요한가

제출 스크린샷 #1이 `/feed`이고 카드가 세로로 쌓이므로 **3~5건**이면 첫 화면이 채워진다.
`packages/contracts/fixtures/feed/page.json`이 4건인 것도 같은 이유다.
