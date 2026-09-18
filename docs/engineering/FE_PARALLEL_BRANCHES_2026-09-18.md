---
aliases:
  - "FE 병렬 세션 브랜치 규칙 2026-09-18"
doc_type: reference
status: draft
area: engineering
tags:
  - nullnull/reference
  - nullnull/engineering
---

# FE 병렬 세션 — 작업 브랜치 규칙 (2026-09-18)

세션 셋이 한 저장소에서 동시에 일하고 있다. 오너가 **작업 브랜치 방식**으로 가기로
정했다. 이 문서는 그 규칙과, **전환하면서 이미 한 번 물린 함정**을 적는다.

읽는 사람: `/Users/youngjun/Documents/Nullnull`에서 일하는 다른 FE 세션.

## 한 줄

`frontend`에 직접 커밋하지 말고 **`origin/frontend`에서 판 작업 브랜치**에 커밋한다.
`git switch -c <이름>`만 쓰면 **지금 HEAD의 남의 커밋을 상속한다** — 실제로 그렇게 됐다.

## 먼저: 이 방식이 푸는 것과 못 푸는 것

세 세션이 **같은 작업 트리**(`/Users/youngjun/Documents/Nullnull`)를 공유한다.
브랜치는 커밋 이력만 가르고 트리와 index는 계속 하나다.

| 문제 | 브랜치로 해결되나 |
| --- | --- |
| 내 push에 남의 미검증 커밋이 실린다 | **된다** |
| `git status`에 남의 미커밋 파일이 보인다 | 안 된다 — 트리가 하나 |
| `git add .`가 남의 파일을 담는다 | 안 된다 |
| `git add`와 `git commit` 사이에 남이 commit | 안 된다 — **index도 공유** |
| build 산출물 충돌 | 안 된다 |

그래서 **`AGENTS.md` 규칙 6의 기존 규칙은 그대로 유효하다.** 브랜치는 그 위에 얹는
것이지 대체하는 것이 아니다.

- `git add`에 **경로를 명시**한다. `git add .`·`git add -A`를 쓰지 않는다
- `git add`와 `git commit`을 **한 명령**으로 묶는다(사이에 확인을 끼우면 그 창으로
  남의 commit이 내 staged 파일을 가져간다 — `f96cd6f`가 그 사고다)
- 확인은 commit **뒤에** `git show --stat`으로 한다

## 브랜치를 파는 법 — 여기서 물렸다

```bash
git fetch origin frontend
git switch -c frontend-<작업이름> origin/frontend    # ← origin/frontend 를 반드시 적는다
```

**마지막 인자를 빼면 안 된다.** `git switch -c <이름>`은 *지금 HEAD*에서 갈라지고,
그 HEAD에 남의 미푸시 커밋이 얹혀 있으면 **그것까지 상속한다.**

실제로 일어난 일:

```text
frontend-splash-check   dbbe387  ← 이 세션의 커밋 2개가 딸려 들어감
frontend-mytrip-tab     dbbe387  ← 원래 주인
```

`frontend-splash-check`를 판 세션은 `내 여행` 탭 작업 커밋 둘(`23f385f`, `dbbe387`)을
의도치 않게 들고 있다. 실해는 없다 — 같은 커밋이라 양쪽이 merge돼도 중복되지 않는다.
다만 그 브랜치가 먼저 merge되면 **그 작업이 이 브랜치 이름으로 기록된다.**

고치려면 둘 중 하나다.

```bash
# A. 떼어낸다
git rebase --onto origin/frontend dbbe387 frontend-splash-check

# B. 그냥 둔다 — frontend-mytrip-tab 이 먼저 merge되면 자동 해소된다
```

**B로 가도 된다.** 알고만 있으면 된다.

## 로컬 `frontend`는 원격과 같게 유지한다

작업 커밋이 로컬 `frontend`에 남아 있으면 다음 사람이 브랜치를 팔 때 또 상속한다.
이 세션은 자기 커밋을 옮긴 뒤 이렇게 되돌렸다.

```bash
git branch -f frontend origin/frontend
git rev-list --left-right --count origin/frontend...frontend   # 0 0 이어야 한다
```

`-f`는 커밋을 **버리지 않는다** — 이미 작업 브랜치에 있기 때문이다. 되돌리기 전에
반드시 확인한다.

```bash
git branch --contains <커밋>     # 작업 브랜치 이름이 나와야 한다
```

## 현재 브랜치 지도 (2026-09-18 기준)

```text
origin/frontend          506df08   ← 공유 통합 지점
origin/frontend-fe-601             ← 이미 원격에 있음
origin/frontend-fe-602             ← 이미 원격에 있음

frontend-mytrip-tab      dbbe387   ← 내 여행 탭 (BA-011 배선 + fallback)
frontend-splash-check    dbbe387   ← 로컬만. 위의 커밋 2개를 상속함
```

## 합칠 때 — 여기가 진짜 위험한 자리다

각자 브랜치에서 green이어도 **합친 상태는 아무도 검증한 적이 없다.**
`AGENTS.md` 규칙 6이 적은 그대로다 — *"전역 합계는 각자의 파일에 없지만 각자의 변경에
반응한다."*

```bash
git fetch origin frontend
git switch frontend && git merge --no-ff frontend-<작업이름>
cd apps/web && npm run verify:ci        # 합친 상태에서 한 번 더
```

**merge는 한 번에 한 세션만.** 셋이 동시에 하면 통합 지점에서 다시 섞인다.

### CSS 예산이 실제 위험이다

```text
상한   10,200 gzip bytes
현재    9,924  (97%)      ← frontend-mytrip-tab 기준 실측
여유      276 bytes
```

세 세션이 각자 CSS를 더하면 **합칠 때 넘친다.** 각자 브랜치에서는 통과한다.
CSS를 건드리는 세션은 merge 순서를 서로 알려라.

JS는 여유가 있다(159,756 / 178,000 = 90%).

## 안 옮긴 것 — 왜 worktree가 아닌가

`git worktree`면 트리·index까지 갈라진다(`AGENTS.md` 규칙 6이 `apps/api` 검증에
요구하는 방식이다). 지금 안 하는 이유는 비용이다.

- worktree마다 `npm ci` (~3분 × 3)
- 세 세션이 **동시에** 멈추고 전환해야 효과가 난다
- 전환 중 **미커밋 작업 유실**이 가장 큰 위험이다. 이 문서를 쓰는 시점에 트리에
  남의 미커밋 파일이 5개 있었다

실측으로 오늘 커밋이 섞인 사고는 **0건**이었다 — 경로 명시로 매번 막혔다.
마감 뒤에 worktree로 가는 것이 맞다고 본다.

## 요약 체크리스트

```bash
# 브랜치 팔 때
git fetch origin frontend
git switch -c frontend-<이름> origin/frontend

# 커밋할 때
git add <경로> <경로> && git commit -F -   # 한 명령, 경로 명시
git show --stat                              # 뒤에 확인

# push 전
git log --oneline origin/frontend..HEAD      # 전부 내 커밋인지

# merge 할 때 (한 번에 한 세션)
git fetch origin frontend
git switch frontend && git merge --no-ff frontend-<이름>
cd apps/web && npm run verify:ci
```
