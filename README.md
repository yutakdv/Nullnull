# 널널(Nullnull) 백엔드

> 붐비는 곳 말고, 널널한 여행 — 오버투어리즘 분산 코스 추천 서비스 (2026 관광데이터 활용 공모전 지정과제 2)

한국관광공사 OpenAPI(필수 5종)와 무료 보조 API만으로 동작하는 FastAPI 백엔드입니다.
`과제2_널널_웹앱구현_기획서_Final.md`의 산식(9장)·ERD(11장)·API 명세(12장)를 그대로 구현했고,
FE 초안(`nullnull-travel-webapp`)의 4개 화면이 쓰는 데이터 계약을 전부 제공합니다.

## 빠른 시작 ① — Docker (권장, FE+BE 한 번에)

```bash
cp .env.example .env                  # API 키 입력(없어도 데모 모드로 동작)
docker compose up -d --build
```

- **웹앱**: http://localhost:3000 (nginx가 `/api`·`/docs`를 백엔드로 프록시 — CORS 불필요)
- **백엔드 직접**: http://localhost:8000 (루트 접속 시 /docs로 리다이렉트)
- 일배치 수동 실행: `docker compose run --rm batch`
- DB는 `nullnull-data` 볼륨에 저장돼 컨테이너를 재생성해도 수집 데이터가 유지됩니다.

## 빠른 시작 ② — 로컬 개발

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --reload          # http://127.0.0.1:8000 (→ /docs)

cd nullnull-travel-webapp && npm install && npm run dev   # http://127.0.0.1:5173
```

- FE dev 서버는 `vite.config.js`의 프록시로 `/api` 요청을 8000 포트로 전달합니다(백엔드를 먼저 켜 두세요).
- 첫 기동 시 테이블 생성 + **서울 MVP 시드 자동 적재**. **API 키가 하나도 없어도 전 기능이 데모 모드로 동작**합니다(심사장 오프라인 대비).

```bash
.venv/bin/python -m pytest                        # 테스트 전체 실행
.venv/bin/python -m app.batch.seed --force        # 시드 재적재
.venv/bin/python -m app.batch.daily               # 통합 일배치(수집→피드백 보정→점수 재계산)
.venv/bin/python -m scripts.backtest              # 가중치 백테스트(9-5) → docs/backtest/<날짜>.md
```

환경변수는 `.env.example`을 `.env`로 복사해 설정합니다. 운영 DB는 `DATABASE_URL`로 PostgreSQL 전환
(`pip install psycopg2-binary` 필요), 배치는 GitHub Actions cron 등에서 `python -m app.batch.daily` 실행.

## Vercel 배포 (FE) — 다른 사람에게 보여주기

FE는 Vercel에 정적 배포하고, `/api` 요청은 `vercel.json`의 rewrite가 **내 백엔드 공개 URL로 서버사이드 프록시**합니다
(브라우저 입장에선 same-origin이라 CORS·mixed-content 문제가 없습니다).

1. **백엔드를 공개 URL로 노출** — 로컬 서버를 켜 둔 상태에서 터널을 뚫는 게 가장 간단합니다:
   ```bash
   docker compose up -d --build                     # 백엔드 기동(8000)
   brew install cloudflared
   cloudflared tunnel --url http://localhost:8000   # → https://xxxx.trycloudflare.com 발급
   ```
2. `nullnull-travel-webapp/vercel.json`의 `REPLACE-WITH-YOUR-BACKEND-URL`을 위 URL로 교체
3. 배포:
   ```bash
   cd nullnull-travel-webapp
   npx vercel --prod          # 또는 GitHub 연동 후 Root Directory를 nullnull-travel-webapp로 지정
   ```

- 무료 터널(trycloudflare)은 실행할 때마다 URL이 바뀌므로, 바뀌면 vercel.json 수정 후 재배포하세요(고정 URL이 필요하면 Cloudflare 무료 계정의 named tunnel 사용).
- rewrite 대신 직접 호출을 원하면 Vercel 프로젝트 환경변수에 `VITE_API_BASE_URL=https://<백엔드URL>`을 넣고, 백엔드 `.env`에 `CORS_ORIGINS=*`(또는 Vercel 도메인)를 설정하면 됩니다.
- 백엔드가 꺼져 있으면 웹앱은 빈 상태 안내를 표시합니다. **심사장 오프라인 대비는 로컬
  `docker compose up`(키 없이 시드 데모 모드) 구성으로 수행**하세요 — 외부 네트워크 없이 전 기능이 동작합니다.

## API 명세 (기획서 12장 + FE 필요분)

| Method | Endpoint | 설명 |
|---|---|---|
| GET | `/api/health` | 상태·데모 모드 여부 |
| GET | `/api/spots?region=&category=&keyword=&page=&size=` | 관광지 목록(캐시된 TourAPI 데이터) |
| GET | `/api/spots/home?region=&date=&time_slot=&themes=&limit=` | 홈 추천 관광지 + 선택 날짜·테마의 널널도 |
| GET | `/api/spots/visited?limit=` | 홈 '최근 방문한 장소'(실사용 피드백·후기 기반, 시드 제외) |
| GET | `/api/spots/{id}` | 상세 + 후기 통계 + 신뢰 지표(proof) + 숨은명소 여부 |
| GET | `/api/spots/{id}/congestion?date=&time_slot=` | 널널도(F3): 5단계 뱃지, ±5일 요일 비교, 오전/오후/저녁 비교, 팁, **행동형 시간 이동 제안**(`time_shift_suggestions`). **date가 오늘~+30일 밖이면 400** |
| GET | `/api/spots/{id}/calendar?time_slot=` | 30일 널널 캘린더 히트맵(F3) — 예측 창 전체의 일별 널널도·공휴일 |
| GET | `/api/spots/{id}/alternatives?date=&time_slot=&themes=&limit=&log_exposure=` | 대안지(F4·F6): AlternativeScore 정렬, 감소율·이동시간·유사도·추천이유·점수 분해, **노출 로그 기록(F8)** — FE 프리페치는 `log_exposure=false` |
| POST | `/api/courses` | 코스 생성(F5): 동선 정렬, 근거 저장, **선택 로그 기록(F8)**. `companion`(solo/couple/family, F1)으로 체류시간 조정 |
| POST | `/api/courses/recommend` | 자유여행 코스(카테고리 시퀀스: 여행지→미식→포토스팟 등). `companion` 지원 |
| GET | `/api/courses/{id}/alternatives?limit=` | 코스 슬롯별 교체 후보(노출 로그 기록) |
| POST | `/api/courses/{id}/swap` | 슬롯 교체 — 원본 보존, 새 코스 반환(교체 장소만 선택 로그) |
| POST | `/api/courses/{id}/reroll` | 같은 조건·같은 모드로 다른 조합 재추천(F8 부하 반영) |
| GET | `/api/courses/{id}` | 코스 상세: 타임라인·요약·임팩트 카드·근거·후기 |
| GET | `/api/courses/popular?limit=` | 홈 인기 코스 캐러셀 |
| POST | `/api/feedback` | 1탭 피드백(F7): `perceived` ∈ {-1,0,1} |
| POST / GET | `/api/reviews` | 별점+태그+텍스트 후기 작성/최근 목록 |
| GET | `/api/impact/summary` | 주간 분산 임팩트(시드 제외, 부족 시 `includes_seed:true`로 고지) |
| POST | `/api/admin/seed` | (X-Admin-Token) 합성 시드 주입 — `is_seed=true` 구분 저장 |
| GET | `/api/admin/ingest-log` | (X-Admin-Token) 공사 API 수집 상태 + 추천 부하 분포(F8 시연 화면) |

### 데모 시나리오(기획서 6장) 흐름

```bash
# ① 경복궁 검색                ② 토요일 오후 널널도 → '매우 붐빔' + 시간 분산 제안
curl "localhost:8000/api/spots?keyword=경복궁"
curl "localhost:8000/api/spots/1/congestion?date=2026-07-11&time_slot=afternoon"
# ③ 31일 초과는 API 레벨 400   ④ 대안 코스 → 경희궁·운현궁·성균관(보통/널널 레벨만 우선)
curl "localhost:8000/api/spots/1/congestion?date=2026-08-30"      # 400
curl "localhost:8000/api/spots/1/alternatives?date=2026-07-11"
# ⑤ 코스 생성 → 임팩트 카드    ⑥ 피드백 → 일배치 후 예측 보정
curl -X POST localhost:8000/api/courses -H 'Content-Type: application/json' \
     -d '{"origin_spot_id":1,"spot_ids":[10,9,11],"date":"2026-07-11"}'
curl -X POST localhost:8000/api/feedback -H 'Content-Type: application/json' \
     -d '{"spot_id":10,"perceived":-1}'
# ⑦ F8 로테이션 수치(관리자)
curl localhost:8000/api/admin/ingest-log -H 'X-Admin-Token: nullnull-admin'
```

## 스코어링 (기획서 9장 — 가중치는 `weights.yaml`로 외부화)

| 산식 | 구현 위치 |
|---|---|
| **널널도** `0.55×집중률 + 0.20×지역방문자 + 0.15×수요강도 + 0.10×요일/공휴일/날씨` (결측 항 재정규화, 날씨는 단기예보 ~3일 내 조건부) | `app/scoring/congestion.py` |
| **대안지** `0.30×테마유사도 + 0.25×혼잡완화 + 0.15×이동편의 + 0.10×숨은명소성 + 0.10×날씨적합 − 0.10×추천부하` | `app/scoring/alternative.py` |
| 테마 유사도 `0.6×Jaccard(카테고리) + 0.4×연관API 유사도` (둘 다 없으면 태그 폴백) | 〃 |
| 숨은 명소성 = 방문자수 하위 분위 × 콘텐츠 풍부도(이미지 수+개요 길이+연관 등장 횟수) | 〃 |
| 추천 부하(F8) = 최근 7일 (노출+선택×2) 정규화, **로그 없으면 0(콜드스타트 안전)** | 〃 |
| **코스** `평균 대안점수 − 이동 패널티 − 카테고리 반복 패널티 + 지역 분산 보너스` | `app/scoring/course.py` |
| **피드백 보정** `adjusted = risk × (1 + 0.2×EWMA(최근 30건))`, 30건 미만 미적용, 일배치로 `spot_score_daily` 캐시 | `app/scoring/feedback_adjust.py` |

가중치 백테스트(기획서 9-5)는 `python -m scripts.backtest`로 수행합니다 — 대표/소규모 명소 10곳 ×
14일 격자에서 ① 공휴일>주말>평일 순서 재현, ② 명소 규모 순위 상관(스피어만)을 검증하고
`docs/backtest/<날짜>.md` 1페이지를 생성합니다. 순서가 깨지면 `weights.yaml`을 ±0.05 단위로
조정 후 재실행해 전/후를 비교합니다.

**시드 데이터 반영 정책(데이터 정직성 원칙)** — 합성 시드는 전부 `is_seed=true`로 저장되며,
③분산 임팩트 집계는 시드를 제외(부족 시 포함하되 `includes_seed`로 화면 고지)합니다.
피드백 보정(9-4)·추천 부하(F8)·후기 지표는 콜드스타트 시연을 위해 시드를 포함해 동작하며,
실사용 로그가 쌓이면 자연히 실데이터 중심으로 수렴합니다. 발표 시 이 구분을 그대로 고지합니다.

## 외부 API 연동 (`app/external/`)

| API | 키 환경변수 | 비고 |
|---|---|---|
| TourAPI 국문관광정보(KorService2) — 필수① | `KTO_API_KEY` | 스팟 마스터·콘텐츠 풍부도 |
| 관광지 집중률 예측(TatsCnctrRate) — 필수② | 〃 | 널널도 핵심 지표(향후 30일) |
| 빅데이터 지역별 방문자수(DataLab) — 필수③ | 〃 | 요일 패턴 → 향후 30일 상대지수 투영 |
| 관광지별 연관 관광지(TarRlteTar) — 필수④ | 〃 | 대안 후보군·테마 유사도 결합 항 |
| 지역별 관광 수요 강도 — 필수⑤ | 〃 | 지역 과밀 보정 |
| 기상청 단기예보 (무료) | `KMA_API_KEY` | 방문일 ~3일 내에서만 날씨 항 적용 |
| 서울 실시간 도시데이터 (무료) | `SEOUL_API_KEY` | 당일 조회 시 실시간 혼잡 대입(F3) |
| 카카오모빌리티 길찾기 (무료 쿼터) | `KAKAO_REST_API_KEY` | 없으면 하버사인 추정 폴백 |

- 키가 없는 API는 일배치에서 `skipped`로 기록되고 시드 스냅샷으로 폴백합니다(`api_ingest_log`가 구동 안정성 근거).
- ⚠️ 집중률·연관·수요강도 API의 **오퍼레이션/필드명은 활용신청 승인 후 콘텐츠랩 Swagger에서 반드시 재확인**(기획서 19장). 각 클라이언트 파일 상단에 확인 포인트를 주석으로 남겨 두었습니다.

## DB (기획서 11장 ERD + 보강)

`tourist_spot`, `congestion_snapshot`, `related_spot`, `course`, `course_item`, `spot_score_daily`,
`recommendation_evidence`, `recommendation_log(is_seed)`, `visit_feedback(is_seed)`, `api_ingest_log`, `app_user`
— 기획서 원안 그대로. 추가로 `region_stat_daily`(방문자지수·수요강도 저장), `visit_review`(FE 별점 후기 UI 지원).

## FE(nullnull-travel-webapp) 연동 가이드

CORS는 Vite dev 서버(5173) 허용 완료. 화면 ↔ 엔드포인트 매핑:

| FE 화면(하드코딩 데이터) | 대체할 API |
|---|---|
| 홈 임팩트 카운터(58%·312) | `GET /api/impact/summary` → `avoid_rate_avg_pct`, `hidden_pick_count` |
| 홈 인기 코스 캐러셀 | `GET /api/courses/popular` → `title/location/image_url/rate_pct/duration_text/tag/level` |
| 상세 널널도 뱃지·문구 | `GET /api/spots/{id}/congestion` → `level/label/tip` |
| 상세 요일별 차트(`congestion` 배열) | 〃 `weekday_comparison` + `time_slots` |
| 상세 리뷰 proof 카드 | `GET /api/spots/{id}` → `review_stats`, `proof` |
| 대안 카드(감소율·이동·유사도·이유) | `GET /api/spots/{id}/alternatives` → `decrease_pct/travel_time_min/similarity_pct/reason` |
| 코스 타임라인·요약·임팩트 | `POST /api/courses` · `GET /api/courses/{id}` → `timeline/summary/impact_text` |
| 1탭 피드백 버튼 3종 | `POST /api/feedback` (한산 -1 / 비슷 0 / 붐빔 +1) |
| 후기 작성·최근 후기 | `POST /api/reviews` · `GET /api/reviews?course_id=` |

스팟 `image_url`은 FE의 `/assets/*.png` 경로를 그대로 사용하므로 오프라인 데모에서도 이미지가 렌더링됩니다.
(TourAPI 키 등록 후 일배치가 실제 `firstimage` URL로 교체)

## 배포 (기획서 10장)

- MVP: 단일 서버(uvicorn) + PostgreSQL(RDS), FE는 S3+CloudFront/Vercel, 배치는 GitHub Actions cron
- 확장(10-2 로드맵): ECS Fargate + ElastiCache + EventBridge — IaC 코드는 별도 저장소로 준비
