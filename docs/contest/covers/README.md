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

**서빙 위치: staging distribution의 `/covers/`.** `#182` 결정에 따라 `media_assets`는 **절대 https URL만**
받는다(`media_assets_origin_url_check`가 `^https://`를 강제). staging operator의 release plan이 이 폴더의
`*.jpg`를 배포 묶음(assembly)에 넣고 WebEdge distribution이 `<PublicUrl>/covers/<파일>`로 서빙한다
(`STAGING_DEPLOYMENT_RUNBOOK.md` §11). 그래서 **이 폴더의 jpg는 모두 공개로 배포된다** — 게시물이 쓰지 않는
사진을 여기 두지 않는다. 한 번 올라간 사진은 다음 release에서도 지워지지 않는다(`prune: false`).

## 파일 이름

글 순서와 맞춘다. 파일 형식은 `.jpg`다.

| 파일 | 글 | 그림 |
| --- | --- | --- |
| `01-gyeongbokgung.jpg` | 담장을 따라 걷는 하루 | 근정전 앞 넓은 마당과 계단을 오르내리는 사람들, 뒤로 산이 보인다 |
| `02-changdeokgung.jpg` | 나무가 많은 궁 | 연못 건너편 이층 전각과 물에 비친 그림자, 단풍 든 나뭇가지가 위를 덮는다 |
| `03-bukchon.jpg` | 사람이 사는 골목 | 한옥 처마가 양옆으로 이어진 내리막 골목, 멀리 해 지는 도심과 남산타워가 보인다 |
| `04-deoksugung.jpg` | 도심 한가운데의 궁 | 기둥이 늘어선 석조전과 그 앞 분수대, 뒤로 도심 고층 건물이 보인다 |
| `05-seoulsup.jpg` | 걷다가 앉는 곳 | 잔디밭에 돗자리를 펴고 앉은 사람들, 나무 너머로 해 지는 고층 건물이 보인다 |

표지 5장이 모두 준비됐다.

## 권리 근거

다섯 장 모두 **팀이 만든 1st-party 자산**이며 provider(KTO) 이미지가 아니다.

**2026-09-20 정정 — 이 문단은 원래 *"오너가 직접 촬영해 보유한 사진"* 이라고 적고 있었고 그것은 사실이 아니다.**
다섯 파일 전부에 C2PA 매니페스트(`caBX` chunk, 21~23KB)가 박혀 있고 그 내용이 `softwareAgent.name=gpt-image`,
`digitalSourceType=trainedAlgorithmicMedia`, `claim_generator_info.name=OpenAI Media Service API`, 동작은
`c2pa.created`다. 촬영본을 편집한 것이라면 원본을 가리키는 `ingredient`/`c2pa.opened` assertion 이 있어야 하는데
**0건**이다. 배포본에도 같은 바이트가 나간다(CloudFront 에서 받은 sha256 == repo 파일 == plan 의 checksum).
**그래서 이 파일들을 Content Credentials 류 도구로 열면 "AI 생성"이 나온다.**

**`A-024`는 개정하지 않았다** — 그 결정은 *"실제 장소를 사진처럼 묘사한 합성 이미지는 금지한다"* 로 그대로이고,
이 다섯 장은 그 금지에 해당한 채로 남아 있다(위험 대장 `R-040`). 2026-09-20 오너가 교체하지 않기로 했다.
**이 문단을 고친 이유는 결정을 정당화하려는 것이 아니라 기록이 거짓이었기 때문이다.**

이 결정의 출처는 [GitHub issue #183](https://github.com/yutakdv/Nullnull/issues/183) (2026-09-19 BE·FE 공동 결정)이다.

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

BE는 거기에 `id`·`placeId`·`cover.url` 세 칸을 채워 실제 계획 파일로 옮긴다. 다섯 장 모두 옮겨졌다
(`ops/curated-posts.json`). 사진을 바꾸면 `cover.checksum`도 바꿔야 하고,
`scripts/tests/test_curated_post_covers.py`가 둘이 어긋나면 실패한다.
