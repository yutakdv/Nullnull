// API 응답 → 화면 표시용 데이터 변환(순수 함수)
import { imageUrl } from './image';

export function mapAlternative(item) {
  return {
    spotId: item.spot_id,
    title: item.name,
    image: imageUrl(item.image_url, item.name),
    decrease: `${item.decrease_pct}%`,
    move: `${item.travel_time_min}분`,
    similarity: `${item.similarity_pct}%`,
    level: item.level,
    reason: item.reason,
    score: item.score,
    hiddenGem: item.hidden_gem,
    breakdown: item.breakdown,
    loadPenalty: item.breakdown?.load_penalty ?? 0,
  };
}

// 시간대별 널널도 3회 조회 결과를 요일 히트맵(오전/오후/저녁)으로 병합
export function mergeCongestionChart(morning, afternoon, evening) {
  if (!afternoon?.weekday_comparison?.length) return [];
  const cellOf = (view, i, fallback) => {
    const item = view?.weekday_comparison?.[i] ?? fallback;
    return { risk: Math.round(item.risk), level: item.level ?? fallback.level };
  };
  return afternoon.weekday_comparison.slice(0, 7).map((item, i) => ({
    day: item.day,
    date: item.date,
    morning: cellOf(morning, i, item),
    afternoon: { risk: Math.round(item.risk), level: item.level },
    evening: cellOf(evening, i, item),
  }));
}

export function mapTimeSlotCards(response) {
  if (!response?.time_slots?.length) return [];
  return response.time_slots.map((slot) => ({
    slot: slot.slot,
    label: slot.slot_label,
    value: `${Math.round(slot.risk)}%`,
    note: slot.note,
  }));
}

// 생성한 코스를 '내 코스' 보관함 항목 형태로 요약
export function courseMemo(course) {
  return {
    course_id: course.course_id,
    title: course.title,
    level: course.level,
    relief_pct: course.summary?.relief_pct ?? 0,
    image_url: course.timeline?.[0]?.image_url ?? null,
    saved_at: new Date().toISOString(),
  };
}

// 코스 상세(CourseDetail)든 홈 인기 코스 카드든 '저장한 코스' 항목 형태로 정규화
export function savableCourse(course) {
  const stops = course.timeline?.length ?? 0;
  return {
    course_id: course.course_id,
    title: course.title,
    image_url: course.image_url ?? course.timeline?.[0]?.image_url ?? null,
    location: course.location ?? course.region ?? '서울',
    duration_text:
      course.duration_text ??
      (course.summary ? `${stops}곳 · 이동 ${course.summary.total_move_min}분` : ''),
    saved_at: new Date().toISOString(),
  };
}

// 위치 기반 근처 추천(MVP 목업) — spot_id 기반 결정적 거리로 정렬해
// 위치 기반 추천처럼 보여준다. 0.3 ~ 5.0km 결정적 분포.
function mockDistanceKm(spotId) {
  return Math.round(((spotId * 137) % 47) + 3) / 10;
}

export function withMockDistance(spots) {
  return spots
    .map((spot) => {
      const km = mockDistanceKm(spot.spot_id);
      return { ...spot, distance_km: km, walk_min: Math.round(km * 14) };
    })
    .sort((a, b) => a.distance_km - b.distance_km);
}
