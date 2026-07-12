"""서울 집중 MVP 시드 데이터 (기획서 8-3 '서울 집중 MVP 기본안', 9-2 시드 계획).

- 대표 과밀 명소 + 테마별 대안지 19곳 (좌표·카테고리 코드는 TourAPI 승인 후 실데이터로 대체)
- 연관 관광지 엣지(연관 API 캐시 형태)
- 오늘~+30일 × 3시간대 혼잡 스냅샷을 결정적(해시 기반)으로 생성 → 데모 모드 데이터 소스
- 합성 로그(recommendation_log·visit_feedback·visit_review)는 전부 is_seed=True 로 구분 저장
"""
import hashlib
from datetime import date, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.config import KR_HOLIDAYS
from app import models

# FE(nullnull-travel-webapp/public/assets)와 같은 경로를 쓰면 오프라인 데모에서도 이미지가 뜬다
IMAGES = {
    "coast": "/assets/hero-coastal-path.png",
    "forest": "/assets/forest-temple.png",
    "lake": "/assets/lakeside-village.png",
    "cafe": "/assets/cafe-alley.png",
}

# 시드 대표 명소는 generic placeholder 대신 실제 한국관광공사(tong) 사진을 쓴다.
# 확실히 맞는 사진이 있는 곳만 매핑하고, 없는 곳은 None → FE 브랜드 플레이스홀더로 처리.
_TONG = "http://tong.visitkorea.or.kr/cms/resource"
SEED_IMAGES = {
    "경복궁": f"{_TONG}/24/3349624_image2_1.png",
    "명동거리": f"{_TONG}/96/3548996_image2_1.jpg",
    "N서울타워": f"{_TONG}/50/3590450_image2_1.jpg",
    "북촌한옥마을": f"{_TONG}/04/3304404_image2_1.jpg",
    "홍대거리": f"{_TONG}/17/3526617_image2_1.jpg",
    "창덕궁": f"{_TONG}/78/3384878_image2_1.JPG",
    "덕수궁": f"{_TONG}/91/3384991_image2_1.JPG",
    "백인제가옥": f"{_TONG}/92/3096392_image2_1.jpg",
    "운현궁": f"{_TONG}/06/3577706_image2_1.jpg",
    "경희궁": f"{_TONG}/98/3531398_image2_1.jpg",
    "길상사": f"{_TONG}/72/3376572_image2_1.jpg",
    "문래창작촌": f"{_TONG}/16/3466116_image2_1.jpg",
    "익선동 골목": f"{_TONG}/22/2947522_image2_1.jpg",
    "서울숲": f"{_TONG}/17/3442117_image2_1.JPG",
}

# (name, sigungu, cat1, cat2, cat3, category_name, tags, addr, lat, lng, image,
#  image_count, is_indoor, base_popularity, overview, highlight)
SPOTS = [
    # ── 과밀 대표 명소 ──────────────────────────────────────────────
    ("경복궁", 23, "A02", "A0201", "A02010100", "궁궐", ["역사", "포토스팟"],
     "서울 종로구 사직로 161", 37.5796, 126.9770, "forest", 12, False, 78.0,
     "조선 왕조의 법궁으로 근정전과 경회루 등 웅장한 전각이 남아 있는 서울 대표 고궁이다. 수문장 교대식과 한복 나들이 명소로 연중 방문객이 가장 많은 궁궐이다.",
     "근정전 회랑 북쪽 구역은 단체 동선에서 비켜나 있어 상대적으로 한적해요."),
    ("명동거리", 24, "A04", "A0401", "A04010200", "쇼핑거리", ["미식", "포토스팟"],
     "서울 중구 명동길", 37.5637, 126.9838, "cafe", 10, False, 76.0,
     "쇼핑과 길거리 음식이 밀집한 서울 대표 관광 상권으로 외국인 방문 1순위 거리다.",
     "본 거리보다 을지로 방향 골목이 대기 없이 둘러보기 좋아요."),
    ("N서울타워", 24, "A02", "A0202", "A02020600", "전망대", ["포토스팟", "자연"],
     "서울 용산구 남산공원길 105", 37.5512, 126.9882, "coast", 11, False, 72.0,
     "남산 정상에서 서울 전경을 내려다보는 랜드마크 전망대로 야경 시간대 집중도가 특히 높다.",
     "케이블카 대신 남산 북측순환로 산책로를 이용하면 줄을 피할 수 있어요."),
    ("북촌한옥마을", 23, "A02", "A0201", "A02010600", "한옥마을", ["역사", "포토스팟"],
     "서울 종로구 계동길 37", 37.5826, 126.9831, "lake", 9, False, 70.0,
     "경복궁과 창덕궁 사이 한옥 밀집 주거지역으로 골목 포토스팟이 많아 주민 정주권 보호를 위한 방문 시간 제한이 시행되는 곳이다.",
     "8경 포토존 대신 계동길 안쪽 골목이 훨씬 여유로워요."),
    ("홍대거리", 44, "A04", "A0401", "A04010100", "문화거리", ["미식", "포토스팟"],
     "서울 마포구 어울마당로", 37.5563, 126.9236, "cafe", 9, False, 68.0,
     "버스킹과 카페, 소품숍이 밀집한 젊음의 거리로 주말 저녁 유동인구가 가장 많다.",
     "메인 걷고싶은거리보다 연남동 경의선숲길 쪽이 체감 밀도가 낮아요."),
    ("창덕궁", 23, "A02", "A0201", "A02010100", "궁궐", ["역사", "자연"],
     "서울 종로구 율곡로 99", 37.5794, 126.9910, "forest", 10, False, 60.0,
     "유네스코 세계유산으로 지정된 조선 궁궐로 후원 특별관람이 유명하다.",
     "후원 예약 회차 직후 시간대의 전각 권역이 비교적 한산해요."),
    ("덕수궁", 24, "A02", "A0201", "A02010100", "궁궐", ["역사", "포토스팟"],
     "서울 중구 세종대로 99", 37.5658, 126.9752, "forest", 9, False, 54.0,
     "시청 앞 돌담길과 석조전으로 유명한 도심 속 궁궐로 야간 개장이 매력적이다.",
     "점심시간 직후보다 개장 직후 오전이 돌담길 산책에 여유로워요."),
    # ── 역사 테마 대안지 ────────────────────────────────────────────
    ("서울한양도성 낙산구간", 23, "A02", "A0201", "A02010200", "성곽길", ["역사", "자연", "포토스팟"],
     "서울 종로구 낙산길 41", 37.5822, 127.0064, "coast", 7, False, 30.0,
     "혜화문에서 흥인지문으로 이어지는 성곽길로 성벽 너머 서울 전경과 노을을 함께 볼 수 있는 구간이다.",
     "성곽 바깥길은 관광 동선에서 벗어나 있어 온전히 여유로운 산책이 가능해요."),
    ("백인제가옥", 23, "A02", "A0201", "A02010600", "근대가옥", ["역사"],
     "서울 종로구 북촌로7길 16", 37.5834, 126.9846, "lake", 6, True, 22.0,
     "1913년 지어진 근대 한옥의 정수로 북촌 한옥마을 안에 있지만 예약 해설 중심이라 조용히 관람할 수 있다.",
     "해설 시간 사이에는 사랑채 마당을 거의 독차지할 수 있어요."),
    ("운현궁", 23, "A02", "A0201", "A02010100", "궁집", ["역사"],
     "서울 종로구 삼일대로 464", 37.5745, 126.9855, "forest", 7, True, 26.0,
     "흥선대원군의 사저이자 고종이 즉위 전까지 살았던 곳으로 도심 한복판에서 고요한 한옥 정취를 느낄 수 있다.",
     "인사동 바로 옆인데도 관람객이 적어 툇마루에 앉아 쉬어가기 좋아요."),
    ("경희궁", 23, "A02", "A0201", "A02010100", "궁궐", ["역사"],
     "서울 종로구 새문안로 45", 37.5713, 126.9683, "forest", 6, False, 28.0,
     "서울 5대 궁궐 중 가장 한적한 궁으로 숭정전 월대에서 여유로운 산책을 즐길 수 있다.",
     "5대 궁궐 중 유일하게 무료 개방인데도 가장 조용한 궁이에요."),
    ("성균관 명륜당", 23, "A02", "A0201", "A02010700", "향교·서원", ["역사", "자연"],
     "서울 종로구 성균관로 31", 37.5865, 126.9966, "forest", 5, False, 24.0,
     "조선 최고 교육기관 성균관의 강학 공간으로 수령 500년 은행나무가 마당을 지키고 있다.",
     "가을 은행나무 시즌 외에는 사진 찍는 사람조차 드문 숨은 명소예요."),
    ("국립민속박물관", 23, "A02", "A0206", "A02060100", "박물관", ["역사"],
     "서울 종로구 삼청로 37", 37.5815, 126.9789, "lake", 8, True, 45.0,
     "한국인의 생활문화를 시대별로 보여주는 박물관으로 경복궁 안쪽에 있어 함께 둘러보기 좋다.",
     "야외 전시장 추억의 거리는 실내 전시보다 훨씬 한산해요."),
    # ── 자연·포토·미식 테마 대안지 ─────────────────────────────────
    ("길상사", 22, "A02", "A0201", "A02010800", "사찰", ["자연", "역사"],
     "서울 성북구 선잠로5길 68", 37.6013, 126.9885, "forest", 6, False, 25.0,
     "성북동 골짜기의 고요한 도심 사찰로 봄 진달래와 가을 단풍, 침묵의 집 명상 공간이 유명하다.",
     "일주문에서 극락전까지 오르는 숲길이 언제 가도 고요해요."),
    ("낙산공원", 23, "A01", "A0101", "A01010200", "공원", ["자연", "포토스팟"],
     "서울 종로구 낙산길 41", 37.5793, 127.0069, "coast", 6, False, 32.0,
     "한양도성과 맞닿은 언덕 공원으로 서울 야경을 한눈에 담는 노을 명소다.",
     "N서울타워와 같은 야경을 훨씬 여유롭게 볼 수 있는 반대편 전망대예요."),
    ("문래창작촌", 56, "A04", "A0401", "A04010100", "예술골목", ["포토스팟", "미식"],
     "서울 영등포구 문래동3가", 37.5172, 126.8950, "cafe", 7, False, 29.0,
     "철공소 골목에 예술가들이 스며들며 만들어진 창작촌으로 철문 벽화와 개성 있는 카페가 이어진다.",
     "홍대 감성의 카페·벽화를 대기 없이 즐길 수 있는 골목이에요."),
    ("서촌 통인시장", 23, "A04", "A0401", "A04010200", "전통시장", ["미식", "역사"],
     "서울 종로구 자하문로15길 18", 37.5806, 126.9689, "cafe", 6, False, 38.0,
     "엽전 도시락으로 유명한 서촌의 전통시장으로 골목마다 오래된 가게와 새 공방이 섞여 있다.",
     "점심 피크만 피하면 엽전 도시락 투어를 줄 없이 즐길 수 있어요."),
    ("익선동 골목", 23, "A04", "A0401", "A04010100", "한옥골목", ["미식", "포토스팟"],
     "서울 종로구 수표로28길", 37.5716, 126.9899, "cafe", 8, False, 48.0,
     "1920년대 한옥을 개조한 카페와 식당이 이어지는 골목으로 좁은 길 특유의 아기자기한 정취가 있다.",
     "저녁보다 오전에 가면 골목 사진을 온전히 담을 수 있어요."),
    ("서울숲", 20, "A01", "A0101", "A01010200", "공원", ["자연", "포토스팟"],
     "서울 성동구 뚝섬로 273", 37.5444, 127.0374, "lake", 8, False, 52.0,
     "은행나무길과 사슴 방사장이 있는 도심 대형 공원으로 피크닉 명소다.",
     "메인 잔디마당 대신 습지생태원 쪽은 주말에도 널널해요."),
]

# (spot_a, spot_b, similarity 0~1) — 연관 관광지 API(티맵 기반) 캐시를 대신하는 시드 엣지
RELATED_EDGES = [
    ("경복궁", "운현궁", 0.85),
    ("경복궁", "백인제가옥", 0.82),
    ("경복궁", "경희궁", 0.83),
    ("경복궁", "서울한양도성 낙산구간", 0.78),
    ("경복궁", "국립민속박물관", 0.88),
    ("경복궁", "창덕궁", 0.90),
    ("경복궁", "성균관 명륜당", 0.72),
    ("창덕궁", "운현궁", 0.86),
    ("창덕궁", "성균관 명륜당", 0.80),
    ("창덕궁", "익선동 골목", 0.74),
    ("덕수궁", "경희궁", 0.87),
    ("덕수궁", "서촌 통인시장", 0.66),
    ("북촌한옥마을", "백인제가옥", 0.90),
    ("북촌한옥마을", "익선동 골목", 0.80),
    ("북촌한옥마을", "서촌 통인시장", 0.84),
    ("북촌한옥마을", "길상사", 0.68),
    ("명동거리", "익선동 골목", 0.72),
    ("명동거리", "서촌 통인시장", 0.70),
    ("명동거리", "문래창작촌", 0.60),
    ("N서울타워", "낙산공원", 0.82),
    ("N서울타워", "서울한양도성 낙산구간", 0.76),
    ("N서울타워", "서울숲", 0.64),
    ("홍대거리", "문래창작촌", 0.84),
    ("홍대거리", "서울숲", 0.66),
    ("홍대거리", "익선동 골목", 0.62),
    ("길상사", "성균관 명륜당", 0.64),
    ("낙산공원", "서울한양도성 낙산구간", 0.92),
]

# 계수는 상한 포화(전부 '매우 붐빔')를 피하도록 보정 — 시연 서사(6장):
# 경복궁 토 오후 '매우 붐빔' / 토 저녁 '붐빔' / 일 오전 '보통'이 재현된다.
WEEKDAY_FACTOR = {0: 0.68, 1: 0.66, 2: 0.70, 3: 0.74, 4: 0.92, 5: 1.12, 6: 0.98}
SLOT_FACTOR = {"morning": 0.62, "afternoon": 1.00, "evening": 0.85}
TIME_SLOTS = ["morning", "afternoon", "evening"]


def deterministic_jitter(key: str, spread: int = 9) -> int:
    """시드 재실행에도 동일한 값이 나오는 ±(spread//2) 지터."""
    digest = hashlib.md5(key.encode()).digest()
    return digest[0] % spread - spread // 2


def snapshot_score(base: float, d: date, slot: str, name: str) -> float:
    score = base * WEEKDAY_FACTOR[d.weekday()] * SLOT_FACTOR[slot]
    if d in KR_HOLIDAYS:
        score *= 1.25
    score += deterministic_jitter(f"{name}:{d.isoformat()}:{slot}")
    return round(min(max(score, 3.0), 97.0), 1)


def seed_spots(db: Session) -> dict[str, models.TouristSpot]:
    spots: dict[str, models.TouristSpot] = {}
    for i, (name, sigungu, cat1, cat2, cat3, cat_name, tags, addr, lat, lng,
            _image, image_count, is_indoor, base_pop, overview, highlight) in enumerate(SPOTS):
        spot = models.TouristSpot(
            content_id=f"seed-{i + 1:04d}",
            name=name, region="서울", area_code=1, sigungu_code=sigungu,
            cat1=cat1, cat2=cat2, cat3=cat3, category_name=cat_name, tags=tags,
            addr=addr, lat=lat, lng=lng, image_url=SEED_IMAGES.get(name),
            image_count=image_count, overview=overview, overview_len=len(overview),
            highlight=highlight, is_indoor=is_indoor, base_popularity=base_pop,
        )
        db.add(spot)
        spots[name] = spot
    db.flush()
    return spots


def seed_related(db: Session, spots: dict[str, models.TouristSpot]) -> None:
    for a, b, sim in RELATED_EDGES:
        db.add(models.RelatedSpot(
            spot_id=spots[a].spot_id, related_spot_id=spots[b].spot_id, similarity_score=sim))
        db.add(models.RelatedSpot(
            spot_id=spots[b].spot_id, related_spot_id=spots[a].spot_id, similarity_score=sim))


def seed_snapshots(db: Session, spots: dict[str, models.TouristSpot], days: int = 31) -> int:
    today = date.today()
    count = 0
    for spot in spots.values():
        for offset in range(days):
            d = today + timedelta(days=offset)
            for slot in TIME_SLOTS:
                score = snapshot_score(spot.base_popularity, d, slot, spot.name)
                db.add(models.CongestionSnapshot(
                    spot_id=spot.spot_id, date=d, time_slot=slot,
                    congestion_score=score,
                    visitor_count=int(score * 40 + spot.base_popularity * 10),
                    source="seed",
                ))
                count += 1
    return count


def seed_region_stats(db: Session, days: int = 31) -> None:
    today = date.today()
    for offset in range(days):
        d = today + timedelta(days=offset)
        factor = WEEKDAY_FACTOR[d.weekday()] * (1.25 if d in KR_HOLIDAYS else 1.0)
        visitor_index = round(min(48.0 * factor + deterministic_jitter(f"region:{d}"), 100.0), 1)
        db.add(models.RegionStatDaily(
            area_code=1, date=d,
            visitor_count=int(320000 * factor),
            visitor_index=visitor_index,
            demand_intensity=round(min(52.0 * factor, 100.0), 1),
            source="seed",
        ))


# (title, description, base_spot, [(spot, stay_min, move_min, move_mode)],
#  relief_pct, theme_keep_pct, level, tag)
SEED_COURSES = [
    ("고궁 골목을 잇는 느린 반나절",
     "경복궁의 웅장함 대신 같은 역사·문화 테마의 한옥과 궁집을 조용히 잇는 코스예요.",
     "경복궁",
     [("운현궁", 50, 12, "도보"), ("백인제가옥", 45, 14, "도보"), ("서울한양도성 낙산구간", 70, 18, "차량")],
     63.0, 87.0, 1, "역사 산책"),
    ("성곽 노을과 사찰의 고요",
     "N서울타워의 야경을 낙산 성곽길에서 여유롭게 담고 성북동 사찰의 고요로 마무리해요.",
     "N서울타워",
     [("낙산공원", 45, 15, "차량"), ("서울한양도성 낙산구간", 60, 8, "도보"), ("길상사", 55, 20, "차량")],
     58.0, 82.0, 2, "노을 야경"),
    ("골목 창작촌 미식 산책",
     "홍대의 에너지를 문래 철공소 골목과 서촌 시장의 느린 미식으로 옮겨 담은 코스예요.",
     "홍대거리",
     [("문래창작촌", 60, 22, "차량"), ("서촌 통인시장", 55, 25, "차량"), ("익선동 골목", 50, 15, "차량")],
     51.0, 79.0, 2, "미식 골목"),
]


def seed_courses(db: Session, spots: dict[str, models.TouristSpot]) -> None:
    upcoming_saturday = date.today() + timedelta(days=(5 - date.today().weekday()) % 7)
    for title, desc, base_name, items, relief, theme_keep, level, _tag in SEED_COURSES:
        total_move = sum(m for _, _, m, _ in items)
        course = models.Course(
            title=title, description=desc, region="서울",
            base_spot_id=spots[base_name].spot_id, date=upcoming_saturday,
            level=level, relief_pct=relief, theme_keep_pct=theme_keep,
            total_move_min=total_move, total_distance_km=round(total_move * 0.42, 1),
            is_seed=True,
        )
        db.add(course)
        db.flush()
        for order_no, (name, stay, move, mode) in enumerate(items, start=1):
            spot = spots[name]
            db.add(models.CourseItem(
                course_id=course.course_id, spot_id=spot.spot_id, order_no=order_no,
                stay_min=stay, move_min=move,
                move_mode=mode if order_no < len(items) else "마무리",
                reason_text=spot.highlight,
            ))
            db.add(models.RecommendationEvidence(
                course_id=course.course_id, spot_id=spot.spot_id,
                theme_sim=round(0.9 - 0.04 * order_no, 2),
                relief_effect=round(relief * 0.6 - order_no, 1),
                travel_time=move,
                hidden_score=round(1 - spot.base_popularity / 100, 2),
            ))


def seed_logs(db: Session, spots: dict[str, models.TouristSpot]) -> None:
    """합성 시드 로그(9-2 보조 시드) — 전부 is_seed=True."""
    now = datetime.now()
    alternatives = [
        ("서울한양도성 낙산구간", 26, 7), ("백인제가옥", 18, 5), ("운현궁", 22, 6),
        ("경희궁", 14, 3), ("성균관 명륜당", 8, 2), ("길상사", 12, 3),
        ("낙산공원", 16, 4), ("문래창작촌", 14, 4), ("서촌 통인시장", 12, 3),
        ("익선동 골목", 20, 4), ("국립민속박물관", 10, 2), ("서울숲", 10, 2),
    ]
    origin = spots["경복궁"].spot_id
    for name, exposures, selections in alternatives:
        sid = spots[name].spot_id
        for i in range(exposures):
            db.add(models.RecommendationLog(
                spot_id=sid, origin_spot_id=origin,
                exposed_at=now - timedelta(hours=(i * 11) % (7 * 24)),
                selected=i < selections, is_seed=True,
            ))

    # 과밀 명소 2곳은 보정 임계치(30건)를 넘겨 '생각보다 붐볐다' 편향을 시연
    # (리스트는 최신순 — 최근 피드백에 0을 섞어 EWMA bias를 +0.6 수준으로 완만하게)
    feedback_plan = [
        ("경복궁", [0, 1, 0, 0, 1] + [1] * 20 + [0] * 6 + [-1] * 4),   # 35건 → 보정 적용
        ("명동거리", [0, 1, 1, 0] + [1] * 14 + [0] * 10 + [-1] * 4),   # 32건 → 보정 적용
        ("서울한양도성 낙산구간", [-1] * 6 + [0] * 3),  # 9건 → 미적용(콜드스타트 방지)
        ("운현궁", [-1] * 4 + [0] * 2),
    ]
    for name, perceived_list in feedback_plan:
        sid = spots[name].spot_id
        for i, p in enumerate(perceived_list):
            db.add(models.VisitFeedback(
                spot_id=sid, perceived=p, is_seed=True,
                created_at=now - timedelta(hours=i * 5),
            ))


def seed_reviews(db: Session, spots: dict[str, models.TouristSpot]) -> None:
    course = db.scalars(select(models.Course).where(models.Course.is_seed)).first()
    reviews = [
        ("민지", 5, ["한산했어요", "동선이 편해요"],
         "경복궁 쪽보다 훨씬 조용해서 골목 소리가 잘 들렸어요. 운현궁까지 이어지는 흐름도 자연스러웠습니다.", "운현궁"),
        ("도윤", 4, ["주차 쉬움", "재방문 의향"],
         "오후에는 조금 사람이 늘었지만 추천 시간대대로 움직이니 기다림이 거의 없었어요.", "서울한양도성 낙산구간"),
        ("하린", 5, ["사진보다 좋아요", "한산했어요"],
         "성곽길 노을이 타워 전망대 못지않은데 사람은 십분의 일이에요.", "낙산공원"),
        ("준호", 4, ["동선이 편해요"],
         "백인제가옥 해설 듣고 나오니 북촌 메인 골목 안 가길 잘했다 싶었어요.", "백인제가옥"),
    ]
    for nickname, rating, tags, text, spot_name in reviews:
        db.add(models.VisitReview(
            course_id=course.course_id if course else None,
            spot_id=spots[spot_name].spot_id,
            nickname=nickname, rating=rating, tags=tags, text=text, is_seed=True,
        ))


def run(db: Session, force: bool = False) -> dict[str, int]:
    """시드 적재(멱등). force=True면 전체 재적재."""
    existing = db.scalar(select(func.count()).select_from(models.TouristSpot))
    if existing and not force:
        return {"spots": existing, "skipped": 1}
    if existing and force:
        for table in [
            models.VisitReview, models.VisitFeedback, models.RecommendationLog,
            models.RecommendationEvidence, models.CourseItem, models.Course,
            models.SpotScoreDaily, models.CongestionSnapshot, models.RelatedSpot,
            models.RegionStatDaily, models.ApiIngestLog, models.TouristSpot,
        ]:
            db.query(table).delete()
        db.flush()

    spots = seed_spots(db)
    seed_related(db, spots)
    snapshot_count = seed_snapshots(db, spots)
    seed_region_stats(db)
    seed_courses(db, spots)
    seed_logs(db, spots)
    seed_reviews(db, spots)
    db.add(models.ApiIngestLog(api_name="seed_bootstrap", status="success",
                               records=snapshot_count))
    db.commit()
    return {"spots": len(spots), "snapshots": snapshot_count}
