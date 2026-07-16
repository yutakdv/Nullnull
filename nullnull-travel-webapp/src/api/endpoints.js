// 백엔드 API 엔드포인트 모음 — 화면(UI) 코드는 이 모듈만 호출한다.
// 응답 형태는 기획서 12장 API 명세를 그대로 따른다.
import { apiFetch } from './client';
import { mergeCongestionChart } from '../utils/mappers';

export const checkHealth = () => apiFetch('/api/health');

// ── 관광지(spots) ────────────────────────────────────────────────
export function fetchHomeSpots({ date, theme }) {
  const params = new URLSearchParams({ region: '서울', date, limit: '8' });
  if (theme !== '전체') params.set('themes', theme);
  return apiFetch(`/api/spots/home?${params}`);
}

export const fetchSpotDetail = (spotId) => apiFetch(`/api/spots/${spotId}`);

export const fetchSpotCongestion = (spotId, date, timeSlot) =>
  apiFetch(`/api/spots/${spotId}/congestion?date=${date}&time_slot=${timeSlot}`);

export const fetchSpotCalendar = (spotId) => apiFetch(`/api/spots/${spotId}/calendar`);

export function fetchSpotAlternatives(spotId, { date, theme, companion, logExposure }) {
  const params = new URLSearchParams({ date, limit: '3', log_exposure: String(logExposure) });
  if (theme !== '전체') params.set('themes', theme);
  if (companion) params.set('companion', companion);
  return apiFetch(`/api/spots/${spotId}/alternatives?${params}`);
}

export const searchSpots = (keyword) =>
  apiFetch(`/api/spots?keyword=${encodeURIComponent(keyword)}&size=8`);

// 검색 탭 — tourAPI 관광지 카탈로그를 구·카테고리로 페이지 단위 조회
export function fetchRegionSpots({ district, category, page }) {
  const params = new URLSearchParams({
    region: '서울',
    page: String(page),
    size: '24',
    category: category || '볼거리',
  });
  if (district) params.set('district', district);
  return apiFetch(`/api/spots?${params}`);
}

export const fetchVisitedSpots = () => apiFetch('/api/spots/visited?limit=6');

// logExposure: 대안 화면에 실제 진입할 때만 true — 상세 프리페치가 F8 노출 부하를
// 부풀리지 않게 한다
export async function fetchSpotContext(spotId, date, theme, companion = '', logExposure = false) {
  const [detail, morningView, afternoonView, eveningView, alternativesData, calendarData] =
    await Promise.all([
      fetchSpotDetail(spotId),
      fetchSpotCongestion(spotId, date, 'morning'),
      fetchSpotCongestion(spotId, date, 'afternoon'),
      fetchSpotCongestion(spotId, date, 'evening'),
      fetchSpotAlternatives(spotId, { date, theme, companion, logExposure }),
      fetchSpotCalendar(spotId).catch(() => null),
    ]);

  return {
    detail,
    slotViews: { morning: morningView, afternoon: afternoonView, evening: eveningView },
    congestionChart: mergeCongestionChart(morningView, afternoonView, eveningView),
    alternativeView: alternativesData,
    calendar: calendarData,
  };
}

// ── 코스(courses) ────────────────────────────────────────────────
export const fetchPopularCourses = (limit) => apiFetch(`/api/courses/popular?limit=${limit}`);

export const fetchCourse = (courseId) => apiFetch(`/api/courses/${courseId}`);

export const createCourse = (payload) =>
  apiFetch('/api/courses', { method: 'POST', body: JSON.stringify(payload) });

export const recommendCourse = (payload) =>
  apiFetch('/api/courses/recommend', { method: 'POST', body: JSON.stringify(payload) });

export const aiRecommendCourse = (payload) =>
  apiFetch('/api/courses/ai-recommend', { method: 'POST', body: JSON.stringify(payload) });

export const swapCourseSpot = (courseId, orderNo, newSpotId) =>
  apiFetch(`/api/courses/${courseId}/swap`, {
    method: 'POST',
    body: JSON.stringify({ order_no: orderNo, new_spot_id: newSpotId }),
  });

export const rerollCourse = (courseId) =>
  apiFetch(`/api/courses/${courseId}/reroll`, { method: 'POST' });

export const shareCourse = (courseId) =>
  apiFetch(`/api/courses/${courseId}/share`, { method: 'POST' });

export const fetchCourseAlternatives = (courseId) =>
  apiFetch(`/api/courses/${courseId}/alternatives?limit=2`);

// ── 피드백·후기·임팩트·관리자 ───────────────────────────────────
export const postFeedback = (payload) =>
  apiFetch('/api/feedback', { method: 'POST', body: JSON.stringify(payload) });

export const postReview = (payload) =>
  apiFetch('/api/reviews', { method: 'POST', body: JSON.stringify(payload) });

export const fetchImpactSummary = () => apiFetch('/api/impact/summary');

export const fetchIngestLog = (token) =>
  apiFetch('/api/admin/ingest-log', { headers: { 'X-Admin-Token': token } });
