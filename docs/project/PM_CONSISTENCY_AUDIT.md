# Nullnull PM 정합성·완성도 감사

- 감사 기준일: 2026-09-05
- 범위: 공개 Figma 3개 page, 저장소 문서·OpenAPI·이벤트·CI 계약, 2인 역할·일정,
  공모전·외부 데이터 공식 자료
- 판정: **문서 기준선은 조건부 승인, 공모전 출시·제출은 NO-GO**

## 1. 총평

Nullnull의 문제 정의, `SavedPost`/`TripCandidate`/`TripItem` 분리, 승인 전 일정 변경
금지, 데이터 provenance, 익명 session·개인정보 최소화는 일관되고 구현 가능한 수준으로
설계돼 있다. 반면 현재 Git 저장소에는 `apps/web`, `apps/api`, 생성 client, migration,
배포 artifact가 없다. 따라서 문서의 깊이와 실제 서비스 완성도를 같은 것으로 보고
`구현 완료` 또는 `제출 준비 완료`라고 표현하면 안 된다.

Figma에는 계약과 충돌하는 P0 문구·활성 control이 있고, 가장 중요한 ITEM 최적화의
승인 전 preview 화면이 빠져 있다. 상세 항목은
[Figma 정합성 수정 요청](../design/FIGMA_CHANGE_REQUESTS.md)에서 관리한다.

## 2. 영역별 판정

| 영역 | 판정 | 근거 | 다음 gate |
| --- | --- | --- | --- |
| 문제·가치·P0/P1 경계 | Green | 오버투어리즘 완화와 후보→일정→preview→승인 흐름이 명확함 | 제출 PDF에는 실제 배포 기능만 기재 |
| 도메인·API·이벤트·ERD | Green after fixes | 핵심 불변식과 50개 operation이 연결됨; title/빈 관심사/timezone 불일치는 이번 감사에서 수정 | Redocly, 생성 client diff, BE contract test |
| Figma 구조·컴포넌트 | Amber | 현재 52개 frame·49개 component를 확인했으나 FCR-001~015가 Open | P0 blocker/major FCR 종료·node 증거 |
| 데이터·추천·AI 경계 | Green | 사실 검증은 결정적 server logic, LLM은 선호 해석·설명으로 제한 | 실제 provider fixture/property test |
| 개인정보·보안·운영 | Amber | 원문·정밀 위치 비수집, CSRF/owner/삭제/배포 규칙은 강함; 실제 계정·alarm·restore 증거는 없음 | staging security/restore/tabletop |
| 역할·협업 | Amber | FE와 BE/AI 책임·handoff가 상세함; 실제 이름/handle·ruleset은 TBD | 두 담당자 지정과 review 동작 증거 |
| 일정·범위 | Red | 추정 합계가 FE 43일, BE/AI 52일, 공동·운영 16일인데 마감까지 16일 | M0 뒤 재추정, 제출 critical slice 외 OFF |
| 구현·배포·공모전 증거 | Red | 앱 코드, DB migration, 외부 URL, 실제 KTO call-audit가 아직 없음 | INT-01~04, 외부망 smoke, 실제 KTO 증거 |

객관적 현재치는 `문서/계약 검증 가능`, `실행 가능한 제품 gate 0개 완료`다. 구현물이
없는 상태에서 백분율 하나로 완성도를 과장하지 않고, 아래 release gate로 진척을
판정한다.

## 3. 이번 감사에서 반영한 수정

| 항목 | 반영 결과 |
| --- | --- |
| 여행 생성 제목 | Figma에 없는 title을 필수에서 선택으로 변경; 생략 시 locale 기반 결정적 기본값 |
| 관심사 0개 | 제품·화면 규칙과 맞게 create/replace OpenAPI에서 빈 배열 허용 |
| timezone 수정 | `updateTrip` schema에 IANA timezone과 local wall-clock 보존 규칙 추가 |
| feed dead control | P0에서 following/latest/search/bell/follow/filter를 숨김 또는 명시적 disabled로 규정 |
| ITEM 최적화 | P0 READY preview 누락을 출시 blocker로 등록하고 route provider 없는 fallback 명시 |
| 언어·guest·data guide | English 활성, 익명 저장 copy, 6개 source state를 Figma 수정 요청으로 고정 |
| Live 검색·거리 | canonical search→Live coverage와 거리 기준 provenance를 명시 |
| component inventory | 실제 Figma 이름의 공백·`icon/` prefix까지 49개 exact name으로 검증하도록 강화 |
| PM 거버넌스 | 제품 범위·문구·제출 주장 go/no-go는 총괄 PM, 기술 안전성은 각 DRI가 veto하도록 분리 |

## 4. 제출 critical path

공모전 profile의 최소 완결 흐름은 다음 순서다.

1. 익명 session과 KO/EN
2. 여행 생성과 canonical KTO 장소 검색
3. feed/post에서 특정 여행 후보 저장
4. 내 여행에서 후보를 일정화하고 최소 편집·잠금
5. ITEM 최적화 before/after preview와 APPLY/KEEP
6. 데이터 상태·출처·기준 시각과 list fallback
7. 외부 HTTPS 익명창 E2E, 실제 KTO server-side 호출·비밀값 없는 audit, 제출 PDF 대조

붙여넣기 import, 지도, replay 시각화, 고급 편집 variant 등은 위 흐름과 안전 gate가
통과한 뒤만 추가한다. 미완성 기능은 active control과 기능설명서에서 제거하고,
mock/replay를 실제 KTO 호출로 주장하지 않는다.

## 5. Release gate와 go/no-go

| Gate | 기한 목표 | Go 증거 | 미통과 조치 |
| --- | --- | --- | --- |
| G0 계약·Figma | 09-06 | FCR-001~015 종료, M0 scope 확정, lint 통과 | 영향 slice 착수 중지 |
| G1 실행 뼈대 | 09-07 | web→API→PostgreSQL Docker hello, 외부 preview URL | 인프라 장식 중지, scaffold 우선 |
| G2 핵심 탐색 | 09-10 | 익명 session→여행→실제 KTO 검색/상세→후보 저장, 출처·call-audit | 제출 NO-GO 유지, provider 해결 |
| G3 일정 무결성 | 09-13 | 후보 일정화·ETag·독립 lock·실패 rollback E2E | 편집 variant 축소 |
| G4 승인형 최적화 | 09-15 | ITEM preview/APPLY/KEEP, 승인 전 mutation 0 | 최적화를 제출 주장에 포함 금지 |
| G5 제출 후보 | 09-18 | KO/EN·a11y·외부망·source degradation·PDF/실제 API 대조 | 기능 추가 금지, 완결된 범위만 제출 |

현재는 G0도 닫히지 않았다. 특히 실제 KTO 호출, 익명 외부 URL, production secret·quota,
CODEOWNERS/ruleset, AWS 비용·alarm, asset license는 문서만으로 닫을 수 없는 외부 blocker다.

## 6. 총괄 PM 의사결정 규칙

- 총괄 PM은 scope, priority, 사용자 문구, 공모전 claim, 최종 go/no-go를 승인한다.
- FE는 접근성·시각 완결성, BE/AI는 보안·데이터·도메인 무결성에 대해 veto할 수 있다.
- PM 승인은 필수 기술 review나 자동 gate를 대신하지 않는다.
- 2인 구현팀의 GitHub 승인은 계속 상대 담당자 1명이 수행한다. PM은 별도의 세 번째
  개발자 또는 우회 code reviewer로 간주하지 않는다.
- 일정이 늦으면 품질 gate를 낮추지 않고 active scope와 제출 claim을 줄인다.

## 7. 공식 근거 재확인

- [투어라즈 공식 공고](https://touraz.kr/announcementList/pssrpView?curPage=1&pssrpSeqEnc=tCHutZnHQt3WheOzQ7OdTQ%3D%3D)는 웹·앱 구현 부문, 지정과제 선택, 한국관광공사 OpenAPI 필수 활용을 명시한다. 09-21 세부 제출 일정은 참가자 대상 최신 공지·매뉴얼을 제출 직전에 다시 대조한다.
- [공공데이터포털 국문관광정보 서비스](https://www.data.go.kr/tcs/dss/selectApiDataDetailView.do?publicDataPk=15101578)는 현재 개발계정 트래픽·운영계정 신청과 이미지 이용 제한을 안내하므로 key 승인과 asset license를 별도 증거로 남긴다.
- [서울 실시간 도시데이터](https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do)는 장소 단위 조회와 제공 범위를 확인해야 하며, [2026-07-22 품질 공지](https://data.seoul.go.kr/together/notice/datasetNoticeView.do?bbsCd=10008&ditcCd=&pageIndex=1&seq=80d6f0ee7267f416f3f199e85d4da68e)처럼 실제 품질 이슈가 있으므로 `STALE`/`UNAVAILABLE`/replay fallback이 필수다.
