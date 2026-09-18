---
aliases:
  - "큐레이션 게시물 표지 원본"
doc_type: reference
status: draft
area: contest
tags:
  - nullnull/reference
  - nullnull/contest
---

# 큐레이션 게시물 표지 원본 5장

[#183](https://github.com/yutakdv/Nullnull/issues/183)의 표지 이미지를 두는 곳이다.
글 초안은 [`../curated-posts-draft.md`](../curated-posts-draft.md)에 있다.

## 여기에 두는 이유

**`apps/web/public/`에 두면 게이트가 빨개진다.** `image-assets.test.ts`가 그 디렉터리를
전수로 훑어 허용 목록과 **정확히 일치**하는지 단언하고(CMP-ATT-003), 목록에 없는 파일이
생기면 실패한다. 표지는 앱 번들이 아니라 **배포 도메인에서 서빙되는 콘텐츠 자산**이라
그 목록에 들어갈 것도 아니다.

**최종 URL은 BE가 정한다.** `#182` 결정에 따라 `media_assets`는 **절대 https URL만**
받는다(`media_assets_origin_url_check`가 `^https://`를 강제). 서빙 위치는 `D-001`(서비스
도메인)이 확정된 뒤 BE가 정하고, 이 폴더는 **원본을 주고받는 자리**다.

## 파일 이름

글 순서와 맞춘다. 확장자는 `.png` 권장.

| 파일 | 글 | 그림 |
| --- | --- | --- |
| `01-gyeongbokgung.png` | 담장을 따라 걷는 하루 | 돌담길을 걷는 사람들과 기와 지붕 |
| `02-changdeokgung.png` | 나무가 많은 궁 | 나무 사이로 보이는 전각 지붕 |
| `03-bukchon.png` | 사람이 사는 골목 | 한옥 지붕이 층층이 이어진 좁은 골목 |
| `04-deoksugung.png` | 도심 한가운데의 궁 | 돌담과 서양식 건물이 함께 보이는 도심 |
| `05-seoulsup.png` | 걷다가 앉는 곳 | 잔디밭과 키 큰 나무 사이 산책로 |

**3장만 있어도 제출 스크린샷 #1은 채워진다**(카드가 세로로 쌓인다). 5장이 목표다.

## 표지가 지켜야 하는 것 — A-024 (2026-09-18 개정)

**팀이 직접 촬영한 사진**, 또는 **명시적 일러스트**. 둘 다 된다.

- **KTO·provider 사진 재배포 금지** — record별 공공누리 유형 심사가 필요하고
  `PostSummary`에 credit 경로가 없어 계약 breaking이 된다. 이 금지는 그대로다.
- **실제 장소를 사진처럼 지어낸 합성 이미지 금지** — 보는 사람이 관측으로 읽는다.
  불변식 6(합성·관측 구분)을 깬다.

**직접 찍은 사진은 합성이 아니라 관측이므로 허용된다.** 원래 문구가 일러스트로 형식을
좁혔던 이유가 실사풍 *합성*이었고, 그 이유가 실제 촬영에는 적용되지 않는다.

`V021`의 주석과 `NULLNULL_FIRST_PARTY`의 `metric_definition`은 *"일러스트"* 만 적고
있는데, migration은 적용 뒤 checksum이 고정돼 고칠 수 없다. **살아 있는 정본은
`DECISIONS_AND_RISKS.md`의 A-024 줄이다.**

## 파일이 들어온 뒤 FE가 하는 일

1. `sha256`를 계산해 `cover.checksum`에 넣는다(소문자 hex 64자, importer가 검사한다)
2. `alt`를 **실제 그림에 맞춰** 고친다 — 초안은 글을 보고 쓴 것이라 그림과 다를 수 있고,
   `alt`는 이미지를 못 보는 사람에게 *실제로 무엇이 보이는지* 말하는 값이다
3. 서식에 맞춘 JSON 조각을 만들어 #183에 올린다

BE는 거기에 `id`·`placeId`·`cover.url` 세 칸을 채워 실제 계획 파일로 옮긴다.
