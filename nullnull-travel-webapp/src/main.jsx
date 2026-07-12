import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import {
  ArrowDown,
  ArrowRight,
  Bell,
  Bookmark,
  CalendarDays,
  Camera,
  Check,
  ChevronRight,
  Clock3,
  Coffee,
  Compass,
  Heart,
  History,
  Home,
  Info,
  Leaf,
  Loader2,
  ImagePlus,
  LocateFixed,
  LogOut,
  Map as MapIcon,
  MapPin,
  MessageSquareText,
  Navigation,
  RefreshCcw,
  Route,
  Search,
  Settings,
  Share2,
  ShieldCheck,
  Shuffle,
  Sparkles,
  Star,
  ThumbsDown,
  ThumbsUp,
  Trash2,
  Trees,
  UserRound,
  UsersRound,
  X,
} from 'lucide-react';
import './styles.css';

// 홈 히어로 배경 — 광화문·경복궁 타임랩스 GIF(Mixkit 무료 라이선스, 출처표기 불요).
// 로드 전/실패 시에는 HeroScene(CSS 숲 풍경)이 뒤에서 배경을 채운다.
const HERO_GIF = '/assets/hero-gyeongbokgung.gif';

// 기본은 same-origin(/api/...) 호출:
//  - 로컬 dev: vite.config.js proxy → 127.0.0.1:8000
//  - docker-compose: nginx → backend:8000
//  - Vercel: vercel.json rewrites → 공개된 백엔드 URL
// 다른 주소로 직접 호출하려면 VITE_API_BASE_URL로 재정의(CORS 허용 필요).
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

async function apiFetch(path, options) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) },
    ...options,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail ?? `API 요청 실패 (${response.status})`);
  }
  return response.json();
}

const PLACEHOLDER_GRADIENTS = [
  ['#cfe9dd', '#a9d8ec'],
  ['#d8ecd2', '#bfe0d3'],
  ['#dfe7d2', '#cbe6dd'],
  ['#d1e3ef', '#c8e7db'],
];

function hashSeed(seed = '') {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  return hash;
}

// 사진이 없는 장소를 위한 브랜드 그라디언트 자리표시(외부 요청 없는 data-URI SVG).
// generic 사진을 잘못 붙이는 대신 '사진 준비 중'으로 읽히는 핀 모티브 타일을 만든다.
function placeholderImage(seed = '') {
  const [from, to] = PLACEHOLDER_GRADIENTS[hashSeed(seed) % PLACEHOLDER_GRADIENTS.length];
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='400' height='300'>`
    + `<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>`
    + `<stop offset='0' stop-color='${from}'/><stop offset='1' stop-color='${to}'/>`
    + `</linearGradient></defs>`
    + `<rect width='400' height='300' fill='url(#g)'/>`
    + `<path d='M200 98c-23 0-42 19-42 42 0 30 42 68 42 68s42-38 42-68c0-23-19-42-42-42z'`
    + ` fill='rgba(255,255,255,0.68)'/>`
    + `<circle cx='200' cy='140' r='15' fill='rgba(61,133,103,0.5)'/>`
    + `</svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

function imageUrl(path, seed = '') {
  if (!path) return placeholderImage(seed);
  if (path.startsWith('http') || path.startsWith('/')) return path;
  return `/${path}`;
}

function mapAlternative(item) {
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

function mergeCongestionChart(morning, afternoon, evening) {
  // 시간대별 널널도 3회 조회 결과를 요일 히트맵(오전/오후/저녁)으로 병합
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

function mapTimeSlotCards(response) {
  if (!response?.time_slots?.length) return [];
  return response.time_slots.map((slot) => ({
    slot: slot.slot,
    label: slot.slot_label,
    value: `${Math.round(slot.risk)}%`,
    note: slot.note,
  }));
}

const themes = [
  { label: '전체', icon: Sparkles },
  { label: '자연', icon: Trees },
  { label: '역사', icon: History },
  { label: '미식', icon: Coffee },
  { label: '포토스팟', icon: Camera },
];

// 자유여행 슬롯 카테고리 — 백엔드 CourseRecommendRequest.theme_sequence와 동일 어휘
const slotThemeOptions = ['여행지', '자연', '역사', '미식', '포토스팟'];
const defaultFreeSlots = ['여행지', '미식', '포토스팟'];

const crowdLevels = [
  { label: '매우 널널', className: 'level-1', value: 1 },
  { label: '널널', className: 'level-2', value: 2 },
  { label: '보통', className: 'level-3', value: 3 },
  { label: '붐빔', className: 'level-4', value: 4 },
  { label: '매우 붐빔', className: 'level-5', value: 5 },
];

const reviewTags = ['한산했어요', '사진보다 좋아요', '동선이 편해요', '재방문 의향', '주차 쉬움'];

// 코스 시간대별 시작 시각 — 타임라인 도착 시각 계산용(BE REALTIME_SLOT_HOUR와 동일 기준)
const SLOT_START_HOUR = { morning: 10, afternoon: 14, evening: 19 };

function todayInSeoul() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
}

function dateAfter(date, days) {
  const target = new Date(`${date}T00:00:00Z`);
  target.setUTCDate(target.getUTCDate() + days);
  return target.toISOString().slice(0, 10);
}

function homeSpotsPath({ date, theme }) {
  const params = new URLSearchParams({ region: '서울', date, limit: '8' });
  if (theme !== '전체') params.set('themes', theme);
  return `/api/spots/home?${params}`;
}

// logExposure: 대안 화면에 실제 진입할 때만 true — 상세 프리페치가 F8 노출 부하를
// 부풀리지 않게 한다
async function fetchSpotContext(spotId, date, theme, companion = '', logExposure = false) {
  const alternativeParams = new URLSearchParams({
    date, limit: '3', log_exposure: String(logExposure),
  });
  if (theme !== '전체') alternativeParams.set('themes', theme);
  if (companion) alternativeParams.set('companion', companion);
  const [detail, morningView, afternoonView, eveningView, alternativesData, calendarData] =
    await Promise.all([
      apiFetch(`/api/spots/${spotId}`),
      apiFetch(`/api/spots/${spotId}/congestion?date=${date}&time_slot=morning`),
      apiFetch(`/api/spots/${spotId}/congestion?date=${date}&time_slot=afternoon`),
      apiFetch(`/api/spots/${spotId}/congestion?date=${date}&time_slot=evening`),
      apiFetch(`/api/spots/${spotId}/alternatives?${alternativeParams}`),
      apiFetch(`/api/spots/${spotId}/calendar`).catch(() => null),
    ]);

  return {
    detail,
    slotViews: { morning: morningView, afternoon: afternoonView, evening: eveningView },
    congestionChart: mergeCongestionChart(morningView, afternoonView, eveningView),
    alternativeView: alternativesData,
    calendar: calendarData,
  };
}

// ── 내 코스 보관함(익명 MVP — localStorage) ─────────────────────
const MY_COURSES_KEY = 'nullnull.my-courses';

function loadMyCourses() {
  try {
    return JSON.parse(localStorage.getItem(MY_COURSES_KEY)) ?? [];
  } catch {
    return [];
  }
}

// ── 저장한 관광지(마이페이지) — 익명 MVP localStorage ─────────────
const SAVED_SPOTS_KEY = 'nullnull.saved-spots';

function loadSavedSpots() {
  try {
    return JSON.parse(localStorage.getItem(SAVED_SPOTS_KEY)) ?? [];
  } catch {
    return [];
  }
}

// 주소에서 구(district)만 뽑아낸다(예: "서울 종로구 사직로 161" → "종로구")
function districtOf(addr) {
  return (addr || '').match(/(\S+?구)(\s|$)/)?.[1] ?? null;
}

// 서울 25개 자치구 — 검색 탭 지역 선택용
const SEOUL_DISTRICTS = [
  '강남구', '강동구', '강북구', '강서구', '관악구', '광진구', '구로구', '금천구',
  '노원구', '도봉구', '동대문구', '동작구', '마포구', '서대문구', '서초구', '성동구',
  '성북구', '송파구', '양천구', '영등포구', '용산구', '은평구', '종로구', '중구', '중랑구',
];

// 검색 탭 카테고리 칩 — '볼거리' 기본, 백엔드 cat1 그룹과 1:1
const SEARCH_CATEGORIES = ['볼거리', '문화·역사', '자연·공원', '미식', '쇼핑'];

// ── 저장한 코스(마이페이지) — 익명 MVP localStorage ───────────────
const SAVED_COURSES_KEY = 'nullnull.saved-courses';

function loadSavedCourses() {
  try {
    return JSON.parse(localStorage.getItem(SAVED_COURSES_KEY)) ?? [];
  } catch {
    return [];
  }
}

// ── 위치 기반 근처 관광지(MVP 목업) ───────────────────────────────
// 실제 GPS 대신 '경복궁 근처(종로)'를 가상 현재 위치로 두고, spot_id 기반
// 결정적 거리로 정렬해 위치 기반 추천처럼 보여준다.
const MOCK_LOCATION_LABEL = '서울 종로구 인근 (예시 위치)';   // 괄호 안은 줄바꿈 없이 한 덩어리로

function mockDistanceKm(spotId) {
  return Math.round(((spotId * 137) % 47) + 3) / 10;   // 0.3 ~ 5.0km 결정적 분포
}

function withMockDistance(spots) {
  return spots
    .map((spot) => {
      const km = mockDistanceKm(spot.spot_id);
      return { ...spot, distance_km: km, walk_min: Math.round(km * 14) };
    })
    .sort((a, b) => a.distance_km - b.distance_km);
}

// ── 이미지 폴백: API 이미지가 없으면 위키백과에서 장소 이름으로 검색 ──
const WIKI_IMG_KEY = 'nullnull.wiki-images';

function loadWikiCache() {
  try {
    return JSON.parse(localStorage.getItem(WIKI_IMG_KEY)) ?? {};
  } catch {
    return {};
  }
}

const wikiImageCache = loadWikiCache();
const wikiImagePending = new Map();   // 같은 이름 동시 요청 합치기

async function searchWikiImage(name) {
  if (name in wikiImageCache) return wikiImageCache[name];
  if (wikiImagePending.has(name)) return wikiImagePending.get(name);
  const task = (async () => {
    try {
      const params = new URLSearchParams({
        action: 'query', format: 'json', origin: '*',
        prop: 'pageimages', piprop: 'thumbnail', pithumbsize: '800',
        generator: 'search', gsrsearch: name, gsrlimit: '1', gsrnamespace: '0',
      });
      const response = await fetch(`https://ko.wikipedia.org/w/api.php?${params}`);
      const data = await response.json();
      const pages = data?.query?.pages ?? {};
      const url = Object.values(pages)[0]?.thumbnail?.source ?? null;
      wikiImageCache[name] = url;
      localStorage.setItem(WIKI_IMG_KEY, JSON.stringify(wikiImageCache));
      return url;
    } catch {
      return null;    // 실패는 캐시하지 않음 — 다음에 재시도
    } finally {
      wikiImagePending.delete(name);
    }
  })();
  wikiImagePending.set(name, task);
  return task;
}

// API 이미지 → (없거나 로드 실패 시) 위키백과 검색 → 플레이스홀더 순서로 표시
function SmartImage({ src, name, alt, className }) {
  const [failed, setFailed] = useState(false);
  const [wikiSrc, setWikiSrc] = useState(undefined);
  const needsFallback = !src || failed;

  useEffect(() => {
    if (!needsFallback || !name) return undefined;
    let alive = true;
    searchWikiImage(name).then((url) => { if (alive) setWikiSrc(url); });
    return () => { alive = false; };
  }, [needsFallback, name]);

  const resolved = !needsFallback
    ? (src.startsWith('http') || src.startsWith('/') ? src : `/${src}`)
    : (wikiSrc ?? placeholderImage(name));
  return (
    <img
      src={resolved}
      alt={alt ?? name ?? ''}
      className={className}
      loading="lazy"
      onError={() => {
        if (!needsFallback) setFailed(true);
        else if (wikiSrc) setWikiSrc(null);   // 위키 이미지도 깨지면 플레이스홀더로
      }}
    />
  );
}

function courseMemo(course) {
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
function savableCourse(course) {
  const stops = course.timeline?.length ?? 0;
  return {
    course_id: course.course_id,
    title: course.title,
    image_url: course.image_url ?? course.timeline?.[0]?.image_url ?? null,
    location: course.location ?? course.region ?? '서울',
    duration_text: course.duration_text
      ?? (course.summary ? `${stops}곳 · 이동 ${course.summary.total_move_min}분` : ''),
    saved_at: new Date().toISOString(),
  };
}

// ── 여행 중인 코스(여행하기 버튼) — 익명 MVP localStorage ─────────
const ACTIVE_COURSE_KEY = 'nullnull.active-course';

function loadActiveCourse() {
  try {
    return JSON.parse(localStorage.getItem(ACTIVE_COURSE_KEY));
  } catch {
    return null;
  }
}

function App() {
  const [screen, setScreen] = useState('home');
  const [selectedTheme, setSelectedTheme] = useState('전체');
  const [visitDate, setVisitDate] = useState(todayInSeoul);
  const [selectedSpotId, setSelectedSpotId] = useState(null);
  const [homeLoading, setHomeLoading] = useState(false);
  const [modal, setModal] = useState(null);
  const [toast, setToast] = useState('');
  const [savedSpots, setSavedSpots] = useState(loadSavedSpots);   // 마이페이지 저장한 관광지
  const [selectedDistrict, setSelectedDistrict] = useState('');   // 검색 탭 구 선택('' = 전체)
  const [selectedCategory, setSelectedCategory] = useState('볼거리'); // 검색 탭 카테고리
  const [regionSpots, setRegionSpots] = useState([]);             // 지역 탭 널널 추천 목록
  const [regionPage, setRegionPage] = useState(1);
  const [regionTotal, setRegionTotal] = useState(0);
  const [regionHasMore, setRegionHasMore] = useState(false);
  const [regionLoading, setRegionLoading] = useState(false);
  const [apiReady, setApiReady] = useState(false);
  const [homeSpots, setHomeSpots] = useState([]);
  const [homeSpotTotal, setHomeSpotTotal] = useState(0);
  const [homeCourses, setHomeCourses] = useState([]);
  const [visitedSpots, setVisitedSpots] = useState([]);
  const [impact, setImpact] = useState(null);
  const [courseMode, setCourseMode] = useState('theme');       // theme(테마 유지) | free(자유여행)
  const [freeSlots, setFreeSlots] = useState(defaultFreeSlots);
  const [companion, setCompanion] = useState('');               // F1 동행 유형('' = 선택 안 함)
  const [courseCreating, setCourseCreating] = useState(false);
  const [courseRerolling, setCourseRerolling] = useState(false);
  const [spot, setSpot] = useState(null);
  const [spotDetail, setSpotDetail] = useState(null);
  const [slotViews, setSlotViews] = useState(null);             // {morning, afternoon, evening}
  const [activeSlot, setActiveSlot] = useState('afternoon');
  const [calendar, setCalendar] = useState(null);               // 30일 널널 캘린더
  const [congestionChart, setCongestionChart] = useState(null);
  const [alternativeView, setAlternativeView] = useState(null);
  const [courseView, setCourseView] = useState(null);
  const [aiResults, setAiResults] = useState(null);   // AI 코스 추천 결과 {source, courses}
  const [courseAlternatives, setCourseAlternatives] = useState(null);
  const [myCourses, setMyCourses] = useState(loadMyCourses);
  const [savedCourses, setSavedCourses] = useState(loadSavedCourses);  // 북마크한 공유 코스
  const [activeCourse, setActiveCourse] = useState(loadActiveCourse);  // 여행하기로 선택한 코스
  const [courseSharing, setCourseSharing] = useState(false);          // 코스 공개 진행 표시
  const [adminMode, setAdminMode] = useState(window.location.hash === '#admin');
  const [booted, setBooted] = useState(false);      // 첫 로드 시도 완료 여부
  const [navLoading, setNavLoading] = useState(false);  // 화면 전환/조회 진행 표시
  const [heroScrolled, setHeroScrolled] = useState(false);  // 홈 히어로를 벗어났는지(네비 노출용)

  // 홈 탭에서는 히어로 전체화면을 벗어나 스크롤했을 때만 하단 네비게이션을 노출한다.
  useEffect(() => {
    const onScroll = () => setHeroScrolled(window.scrollY > 60);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const congestionView = slotViews?.[activeSlot] ?? null;
  const isSpotSaved = (id) => savedSpots.some((s) => s.spot_id === id);

  // 관광지 저장/해제 — 마이페이지 '저장한 관광지'에 반영(localStorage 유지)
  const toggleSaveSpot = (target) => {
    if (!target?.spot_id) return;
    setSavedSpots((current) => {
      const exists = current.some((s) => s.spot_id === target.spot_id);
      const next = exists
        ? current.filter((s) => s.spot_id !== target.spot_id)
        : [{
            spot_id: target.spot_id,
            name: target.name,
            image_url: target.image_url ?? null,
            addr: target.addr ?? target.region ?? '서울',
            saved_at: new Date().toISOString(),
          }, ...current];
      localStorage.setItem(SAVED_SPOTS_KEY, JSON.stringify(next));
      return next;
    });
    showToast(isSpotSaved(target.spot_id) ? '저장한 관광지에서 뺐어요' : '저장한 관광지에 담았어요');
  };

  const isCourseSaved = (id) => savedCourses.some((c) => c.course_id === id);

  // 코스 북마크/해제 — 마이페이지 '저장한 코스'에 반영(localStorage 유지)
  // 홈 인기 코스 카드·AI 추천 결과(CourseDetail) 모두 savableCourse로 정규화해 담는다.
  const toggleSaveCourse = (target) => {
    if (!target?.course_id) return;
    setSavedCourses((current) => {
      const exists = current.some((c) => c.course_id === target.course_id);
      const next = exists
        ? current.filter((c) => c.course_id !== target.course_id)
        : [savableCourse(target), ...current];
      localStorage.setItem(SAVED_COURSES_KEY, JSON.stringify(next));
      return next;
    });
    showToast(isCourseSaved(target.course_id) ? '저장한 코스에서 뺐어요' : '저장한 코스에 담았어요');
  };

  // 여행하기(코스 사용) — 여행 중인 코스로 지정하고 저장한 코스에도 담는다
  const startTravel = (course) => {
    if (!course?.course_id) return;
    const memo = savableCourse(course);
    setActiveCourse(memo);
    localStorage.setItem(ACTIVE_COURSE_KEY, JSON.stringify(memo));
    setSavedCourses((current) => {
      if (current.some((c) => c.course_id === course.course_id)) return current;
      const next = [memo, ...current];
      localStorage.setItem(SAVED_COURSES_KEY, JSON.stringify(next));
      return next;
    });
    showToast('여행을 시작했어요 — 마이페이지에서 이 코스를 볼 수 있어요');
  };

  const endTravel = () => {
    setActiveCourse(null);
    localStorage.removeItem(ACTIVE_COURSE_KEY);
    showToast('여행을 마쳤어요 — 코스는 저장한 코스에 남아 있어요');
  };

  // 코스 공개(F9) — 홈 '인기 널널 코스'에 노출시키고 목록을 갱신한다
  const shareCourse = async () => {
    if (!courseView?.course_id) return;
    setCourseSharing(true);
    try {
      const shared = await apiFetch(`/api/courses/${courseView.course_id}/share`, { method: 'POST' });
      setCourseView(shared);
      setHomeCourses(await apiFetch('/api/courses/popular?limit=6'));
      showToast('코스가 공개됐어요 — 홈 인기 코스에 노출돼요');
    } catch (error) {
      console.warn(error);
      showToast('코스 공개 중 문제가 생겼어요');
    } finally {
      setCourseSharing(false);
    }
  };

  // 검색 탭 — tourAPI 관광지 카탈로그를 구·카테고리로 페이지 단위 조회(무한스크롤)
  const loadRegionSpots = async (district, category, page = 1) => {
    setRegionLoading(true);
    try {
      const params = new URLSearchParams({
        region: '서울', page: String(page), size: '24',
        category: category || '볼거리',
      });
      if (district) params.set('district', district);
      const res = await apiFetch(`/api/spots?${params}`);
      setRegionSpots((prev) => (page === 1 ? res.items : [...prev, ...res.items]));
      setRegionTotal(res.total);
      setRegionPage(page);
      setRegionHasMore(page * res.size < res.total);
    } catch (error) {
      console.warn(error);
      showToast('지역 관광지를 불러오지 못했어요');
    } finally {
      setRegionLoading(false);
    }
  };

  // 검색 탭 진입/구·카테고리 변경 시 첫 페이지부터 다시 로드
  useEffect(() => {
    if (screen === 'region') loadRegionSpots(selectedDistrict, selectedCategory, 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [screen, selectedDistrict, selectedCategory]);

  const screenTitle = useMemo(
    () => ({
      home: 'Nullnull',
      region: '검색',
      detail: '검색',
      'course-ai': 'AI 코스',
      alternatives: 'AI 코스',
      mypage: '마이페이지',
      course: '코스',
    })[screen],
    [screen],
  );

  const currentSpotId = spot?.spot_id ?? null;

  const showToast = (message) => {
    setToast(message);
    window.setTimeout(() => setToast(''), 2200);
  };

  const applySpotContext = (context) => {
    setSpot(context.detail);
    setSpotDetail(context.detail);
    setSlotViews(context.slotViews);
    setCongestionChart(context.congestionChart);
    setAlternativeView(context.alternativeView);
    setCalendar(context.calendar);
  };

  const applyHomeResponse = (response) => {
    setHomeSpots(response.items);
    setHomeSpotTotal(response.total);
    setSelectedSpotId((current) => (
      response.items.some((item) => item.spot_id === current)
        ? current
        : response.items[0]?.spot_id ?? null
    ));
  };

  const refreshHomeSpots = async (date, theme) => {
    setHomeLoading(true);
    try {
      const response = await apiFetch(homeSpotsPath({ date, theme }));
      applyHomeResponse(response);
      return response;
    } catch (error) {
      console.warn(error);
      showToast('조건에 맞는 관광지를 불러오지 못했어요');
      return null;
    } finally {
      setHomeLoading(false);
    }
  };

  // 홈 '최근 방문한 장소' — 피드백·후기 등록 직후에도 갱신한다
  const refreshVisitedSpots = async () => {
    try {
      const response = await apiFetch('/api/spots/visited?limit=6');
      setVisitedSpots(response.items);
    } catch (error) {
      console.warn(error);
    }
  };

  // 홈 분산 임팩트 카운터(기획서 5장) — 코스 생성·피드백 직후 갱신
  const refreshImpact = async () => {
    try {
      setImpact(await apiFetch('/api/impact/summary'));
    } catch (error) {
      console.warn(error);
    }
  };

  // 시간 분산(F3)의 행동화 — 제안 칩/캘린더 탭이 실제로 날짜·시간대를 옮긴다
  const applyTimeShift = async (suggestion) => {
    if (suggestion.kind === 'slot') {
      setActiveSlot(suggestion.time_slot);
      return;
    }
    const targetId = currentSpotId ?? selectedSpotId;
    if (!targetId) return;
    setVisitDate(suggestion.date);
    setActiveSlot(suggestion.time_slot ?? activeSlot);
    try {
      applySpotContext(await fetchSpotContext(targetId, suggestion.date, selectedTheme, companion));
      refreshHomeSpots(suggestion.date, selectedTheme);
      showToast('널널한 시간으로 옮겨서 다시 조회했어요');
    } catch (error) {
      console.warn(error);
      showToast('날짜를 옮기는 중 문제가 생겼어요');
    }
  };

  // 생성한 코스를 보관함에 저장하고 공유 가능한 해시(#course/id)를 단다
  const rememberCourse = (course) => {
    if (!course?.course_id) return;
    setMyCourses((current) => {
      // 같은 제목(=같은 조건에서 reroll/swap한 계열)은 최신 것만 보관 —
      // '다른 코스 추천'을 연타해도 보관함이 같은 제목 카드로 도배되지 않게
      const next = [courseMemo(course),
        ...current.filter((c) =>
          c.course_id !== course.course_id && c.title !== course.title)].slice(0, 10);
      localStorage.setItem(MY_COURSES_KEY, JSON.stringify(next));
      return next;
    });
    window.history.replaceState(null, '', `#course/${course.course_id}`);
  };

  const handleShare = async () => {
    const base = `${window.location.origin}${window.location.pathname}`;
    const url = screen === 'course' && courseView?.course_id
      ? `${base}#course/${courseView.course_id}`
      : base;
    try {
      await navigator.clipboard.writeText(url);
      showToast('공유 링크를 복사했어요');
    } catch {
      showToast(url);
    }
  };

  // 하단 탭 이동 — 코스 화면이 아니면 공유용 해시를 정리한다
  const changeScreen = (key) => {
    if (key !== 'course') {
      window.history.replaceState(null, '', window.location.pathname + window.location.search);
    }
    setScreen(key);
  };

  const openCourse = async (courseId) => {
    setNavLoading(true);
    try {
      setCourseView(await apiFetch(`/api/courses/${courseId}`));
      setScreen('course');
      window.history.replaceState(null, '', `#course/${courseId}`);
    } catch (error) {
      console.warn(error);
      showToast('코스를 불러오지 못했어요');
    } finally {
      setNavLoading(false);
    }
  };

  const openSpot = async (spotId) => {
    setNavLoading(true);
    try {
      setSelectedSpotId(spotId);
      applySpotContext(await fetchSpotContext(spotId, visitDate, selectedTheme, companion));
      setScreen('detail');
    } catch (error) {
      console.warn(error);
      showToast('관광지 정보를 불러오지 못했어요');
    } finally {
      setNavLoading(false);
    }
  };

  // 초기 데이터 로드 — 연결 실패 시 배너의 '다시 시도'가 재호출한다
  const bootstrap = async () => {
    setNavLoading(true);
    try {
      await apiFetch('/api/health');
      const [popularList, homeSpotResponse, visitedResponse, impactResponse] = await Promise.all([
        apiFetch('/api/courses/popular?limit=3'),
        apiFetch(homeSpotsPath({ date: visitDate, theme: selectedTheme })),
        apiFetch('/api/spots/visited?limit=6').catch(() => ({ items: [] })),
        apiFetch('/api/impact/summary').catch(() => null),
      ]);

      const firstSpot = homeSpotResponse.items[0] ?? null;
      setApiReady(true);
      applyHomeResponse(homeSpotResponse);
      setHomeCourses(popularList);
      setVisitedSpots(visitedResponse.items);
      setImpact(impactResponse);
      setSpot(firstSpot);

      if (firstSpot) {
        applySpotContext(await fetchSpotContext(firstSpot.spot_id, visitDate, selectedTheme, companion));
      }

      const courseHash = window.location.hash.match(/^#course\/(\d+)$/);
      if (courseHash) openCourse(Number(courseHash[1]));
      // 관광지 상세·특정 탭 딥링크(공유/시연용): #spot/123, #tab/course-ai
      const spotHash = window.location.hash.match(/^#spot\/(\d+)$/);
      if (spotHash) openSpot(Number(spotHash[1]));
      const tabHash = window.location.hash.match(/^#tab\/([a-z-]+)$/);
      if (tabHash) setScreen(tabHash[1]);
    } catch (error) {
      console.warn('Nullnull API 연결에 실패했습니다.', error);
      setApiReady(false);
    } finally {
      setBooted(true);
      setNavLoading(false);
    }
  };

  useEffect(() => {
    bootstrap();
  }, []);

  // '일정' 탭 직접 진입 시 인기 코스 1위의 상세를 불러온다(빈 화면 방지)
  useEffect(() => {
    if (screen === 'course' && !courseView && apiReady) {
      const firstCourseId = homeCourses[0]?.course_id;
      if (firstCourseId) {
        apiFetch(`/api/courses/${firstCourseId}`).then(setCourseView).catch(() => {});
      }
    }
  }, [screen, apiReady, courseView, homeCourses]);

  // '상세'·'코스' 탭 직접 진입 시에도 빈 화면이 나오지 않게 첫 장소 기준으로 채운다
  useEffect(() => {
    if (screen === 'detail' && !spotDetail && apiReady && homeSpots[0]) {
      openSpot(homeSpots[0].spot_id);
    }
    if (screen === 'alternatives' && !alternativeView && apiReady
        && (selectedSpotId || homeSpots[0])) {
      findAlternatives();
    }
  }, [screen, apiReady]);

  // #admin 해시로 관리자 화면(F8 로테이션·수집 상태) 진입
  useEffect(() => {
    const onHash = () => setAdminMode(window.location.hash === '#admin');
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const handleVisitDateChange = (date) => {
    setVisitDate(date);
    refreshHomeSpots(date, selectedTheme);
  };

  const handleThemeChange = (theme) => {
    setSelectedTheme(theme);
    refreshHomeSpots(visitDate, theme);
  };

  // 선택한 날짜·장소·테마로 대안을 다시 조회(노출 로그 F8 기록)
  const findAlternatives = async () => {
    const originSpotId = selectedSpotId ?? spot?.spot_id;
    if (!apiReady || !originSpotId) {
      showToast('먼저 방문할 장소를 선택해주세요');
      return;
    }
    setNavLoading(true);
    try {
      applySpotContext(await fetchSpotContext(originSpotId, visitDate, selectedTheme, companion, true));
      setScreen('alternatives');
    } catch (error) {
      console.warn(error);
      showToast('대안 코스를 불러오지 못했어요');
    } finally {
      setNavLoading(false);
    }
  };

  const createCourseFromAlternatives = async () => {
    const alternativesFromApi = alternativeView?.alternatives ?? [];
    if (!apiReady || !alternativesFromApi.length) {
      setScreen('course');
      return;
    }

    try {
      const course = await apiFetch('/api/courses', {
        method: 'POST',
        body: JSON.stringify({
          origin_spot_id: alternativeView.origin.spot_id,
          spot_ids: alternativesFromApi.map((item) => item.spot_id).slice(0, 4),
          date: alternativeView.origin.date,
          time_slot: alternativeView.origin.time_slot,
          companion: companion || null,
        }),
      });
      setCourseView(course);
      setScreen('course');
      rememberCourse(course);
      refreshImpact();
    } catch (error) {
      console.warn(error);
      showToast('코스 생성 중 문제가 생겼어요');
      setScreen('course');
    }
  };

  // 자유여행 코스(카테고리 시퀀스) — 백엔드가 슬롯별 최적 장소를 골라 코스를 만든다
  const createFreeCourse = async () => {
    const originSpotId = selectedSpotId ?? spot?.spot_id;
    if (!apiReady || !originSpotId) {
      showToast('먼저 방문할 장소를 선택해주세요');
      return;
    }
    setCourseCreating(true);
    try {
      const course = await apiFetch('/api/courses/recommend', {
        method: 'POST',
        body: JSON.stringify({
          origin_spot_id: originSpotId,
          date: visitDate,
          theme_sequence: freeSlots,
          companion: companion || null,
        }),
      });
      setCourseView(course);
      setScreen('course');
      rememberCourse(course);
      refreshImpact();
    } catch (error) {
      console.warn(error);
      showToast(error.message ?? '자유여행 코스를 만들지 못했어요');
    } finally {
      setCourseCreating(false);
    }
  };

  // AI 코스 추천(코스 탭) — 조건을 넘기면 알고리즘이 후보를 추리고 LLM(가능 시)이
  // 혼잡·날씨·동선을 고려해 여러 코스를 구성한다. 키 없으면 알고리즘 다중 코스 폴백.
  const createAiCourse = async (cond) => {
    setCourseCreating(true);
    setAiResults(null);
    try {
      const res = await apiFetch('/api/courses/ai-recommend', {
        method: 'POST',
        body: JSON.stringify({
          district: cond.district || null,
          stops: cond.stops,
          companion: cond.companion || null,
          date: cond.date,
          time_slot: cond.timeSlot,
          themes: cond.themes,
          pace: cond.pace,
          indoor_pref: cond.indoor,
          transport: cond.transport || null,
        }),
      });
      setAiResults(res);                         // { source, courses: [CourseDetail] }
      res.courses.forEach(rememberCourse);
      refreshImpact();
    } catch (error) {
      console.warn(error);
      showToast(error.message ?? 'AI 코스를 만들지 못했어요');
    } finally {
      setCourseCreating(false);
    }
  };

  // 일정 화면의 슬롯 교체 — 원본 코스는 남기고 교체본 새 코스를 보여준다
  const swapCourseItem = async (orderNo, newSpotId) => {
    if (!apiReady || !courseView?.course_id) return;
    try {
      const course = await apiFetch(`/api/courses/${courseView.course_id}/swap`, {
        method: 'POST',
        body: JSON.stringify({ order_no: orderNo, new_spot_id: newSpotId }),
      });
      setCourseView(course);
      showToast('대안 장소로 코스를 다시 만들었어요');
      rememberCourse(course);
      refreshImpact();
    } catch (error) {
      console.warn(error);
      showToast('코스 교체 중 문제가 생겼어요');
    }
  };

  const rerollCourse = async () => {
    if (!apiReady || !courseView?.course_id) return;
    setCourseRerolling(true);
    try {
      const course = await apiFetch(`/api/courses/${courseView.course_id}/reroll`, {
        method: 'POST',
      });
      setCourseView(course);
      showToast('같은 조건으로 다른 조합을 추천했어요');
      rememberCourse(course);
      refreshImpact();
    } catch (error) {
      console.warn(error);
      showToast(error.message ?? '다른 코스를 만들지 못했어요');
    } finally {
      setCourseRerolling(false);
    }
  };

  // 코스가 바뀔 때마다 슬롯별 교체 후보를 불러온다(노출 로그 F8 기록)
  useEffect(() => {
    if (!apiReady || !courseView?.course_id) {
      setCourseAlternatives(null);
      return undefined;
    }
    let ignore = false;
    apiFetch(`/api/courses/${courseView.course_id}/alternatives?limit=2`)
      .then((response) => {
        if (!ignore) setCourseAlternatives(response);
      })
      .catch(() => {
        if (!ignore) setCourseAlternatives(null);
      });
    return () => {
      ignore = true;
    };
  }, [apiReady, courseView?.course_id]);

  const submitFeedback = async (perceived) => {
    if (!apiReady || !currentSpotId) return;
    await apiFetch('/api/feedback', {
      method: 'POST',
      body: JSON.stringify({
        spot_id: courseView?.timeline?.[0]?.spot_id ?? currentSpotId,
        course_id: courseView?.course_id ?? null,
        perceived,
      }),
    });
    refreshVisitedSpots();
    refreshImpact();
  };

  const submitReview = async ({ rating, tags, text }) => {
    if (!apiReady || (!courseView && !currentSpotId)) return;
    await apiFetch('/api/reviews', {
      method: 'POST',
      body: JSON.stringify({
        spot_id: courseView ? null : currentSpotId,
        course_id: courseView?.course_id ?? null,
        nickname: '익명',
        rating,
        tags,
        text,
      }),
    });
    refreshVisitedSpots();
    if (courseView?.course_id) {
      setCourseView(await apiFetch(`/api/courses/${courseView.course_id}`));
    }
  };

  if (adminMode) {
    return (
      <main className="app-shell">
        <div className="app-frame">
          <AdminScreen onExit={() => { window.location.hash = ''; }} />
        </div>
        {toast && <Toast message={toast} />}
      </main>
    );
  }

  return (
    <main className="app-shell">
      {navLoading && <div className="top-progress" aria-hidden="true" />}
      <div className="app-frame">
        <Header title={screenTitle} screen={screen} setScreen={changeScreen} onShare={handleShare} />

        {booted && !apiReady && (
          <ConnectionBanner onRetry={bootstrap} loading={navLoading} />
        )}

        {screen === 'home' && (
          <HomeScreen
            selectedTheme={selectedTheme}
            visitDate={visitDate}
            maxVisitDate={dateAfter(todayInSeoul(), 30)}
            selectedSpotId={selectedSpotId}
            homeSpots={homeSpots}
            homeSpotTotal={homeSpotTotal}
            homeCourses={homeCourses}
            visitedSpots={visitedSpots}
            myCourses={myCourses}
            impact={impact}
            courseMode={courseMode}
            freeSlots={freeSlots}
            companion={companion}
            onCompanionChange={setCompanion}
            courseCreating={courseCreating}
            apiReady={apiReady}
            homeLoading={homeLoading}
            onFind={findAlternatives}
            onCreateFreeCourse={createFreeCourse}
            onCourseModeChange={setCourseMode}
            onFreeSlotsChange={setFreeSlots}
            onOpenSpot={openSpot}
            onOpenCourse={openCourse}
            onVisitDateChange={handleVisitDateChange}
            onSpotChange={setSelectedSpotId}
            onThemeChange={handleThemeChange}
            savedIds={savedSpots.map((s) => s.spot_id)}
            onToggleSaveSpot={toggleSaveSpot}
            savedCourseIds={savedCourses.map((c) => c.course_id)}
            onToggleSaveCourse={toggleSaveCourse}
          />
        )}
        {screen === 'region' && (
          <RegionScreen
            selectedDistrict={selectedDistrict}
            selectedCategory={selectedCategory}
            spots={regionSpots}
            total={regionTotal}
            hasMore={regionHasMore}
            loading={regionLoading}
            onSelectDistrict={setSelectedDistrict}
            onSelectCategory={setSelectedCategory}
            onLoadMore={() => loadRegionSpots(selectedDistrict, selectedCategory, regionPage + 1)}
            onOpenSpot={openSpot}
            apiReady={apiReady}
          />
        )}
        {screen === 'course-ai' && (
          <AiCourseScreen
            visitDate={visitDate}
            maxVisitDate={dateAfter(todayInSeoul(), 30)}
            companion={companion}
            onCompanionChange={setCompanion}
            creating={courseCreating}
            apiReady={apiReady}
            onCreate={createAiCourse}
            results={aiResults}
            myCourses={myCourses}
            onOpenCourse={openCourse}
            savedCourseIds={savedCourses.map((c) => c.course_id)}
            onToggleSaveCourse={toggleSaveCourse}
          />
        )}
        {screen === 'detail' && (
          <DetailScreen
            isSaved={isSpotSaved((spotDetail ?? spot)?.spot_id)}
            onToggleSave={() => toggleSaveSpot(spotDetail ?? spot)}
            onFindAlternatives={findAlternatives}
            spot={spotDetail ?? spot}
            congestionView={congestionView}
            congestionChart={congestionChart}
            calendar={calendar}
            activeSlot={activeSlot}
            onTimeShift={applyTimeShift}
          />
        )}
        {screen === 'mypage' && (
          <MyPageScreen
            savedSpots={savedSpots}
            savedCourses={savedCourses}
            myCourses={myCourses}
            activeCourse={activeCourse}
            onEndTravel={endTravel}
            onOpenSpot={openSpot}
            onOpenCourse={openCourse}
            onRemoveSaved={toggleSaveSpot}
            onRemoveSavedCourse={toggleSaveCourse}
            onNotice={showToast}
          />
        )}
        {screen === 'alternatives' && (
          <AlternativesScreen
            setModal={setModal}
            onCreateCourse={createCourseFromAlternatives}
            alternativeView={alternativeView}
            companion={companion}
            selectedTheme={selectedTheme}
            visitDate={visitDate}
            maxVisitDate={dateAfter(todayInSeoul(), 30)}
            selectedSpotId={selectedSpotId}
            homeSpots={homeSpots}
            courseMode={courseMode}
            freeSlots={freeSlots}
            courseCreating={courseCreating}
            apiReady={apiReady}
            homeLoading={homeLoading}
            onFind={findAlternatives}
            onCreateFreeCourse={createFreeCourse}
            onCourseModeChange={setCourseMode}
            onFreeSlotsChange={setFreeSlots}
            onCompanionChange={setCompanion}
            onVisitDateChange={handleVisitDateChange}
            onSpotChange={setSelectedSpotId}
            onThemeChange={handleThemeChange}
          />
        )}
        {screen === 'course' && (
          <CourseScreen
            courseView={courseView}
            courseAlternatives={courseAlternatives}
            showToast={showToast}
            onSwap={swapCourseItem}
            onReroll={rerollCourse}
            rerolling={courseRerolling}
            onSubmitFeedback={submitFeedback}
            onSubmitReview={submitReview}
            onShareCourse={shareCourse}
            sharing={courseSharing}
            onStartTravel={startTravel}
            activeCourseId={activeCourse?.course_id ?? null}
          />
        )}

        <BottomNavigation
          active={NAV_ACTIVE_KEY[screen] ?? screen}
          onChange={changeScreen}
          hidden={screen === 'home' && !heroScrolled}
        />
      </div>

      {modal && <ReasonModal item={modal} onClose={() => setModal(null)} />}
      {toast && <Toast message={toast} />}
    </main>
  );
}

function Header({ title, screen, setScreen, onShare }) {
  return (
    <header className="top-bar">
      <button className="brand-button" onClick={() => setScreen('home')} aria-label="홈으로 이동">
        <span className="brand-mark">
          <Leaf size={19} strokeWidth={2.5} />
        </span>
        <span>{screen === 'home' ? 'Nullnull' : title}</span>
      </button>
      <div className="top-actions">
        <IconButton label="공유 링크 복사" onClick={onShare}>
          <Share2 size={19} />
        </IconButton>
        <IconButton label="내 위치">
          <LocateFixed size={19} />
        </IconButton>
      </div>
    </header>
  );
}

function SpotSearch({ onPick, disabled }) {
  // TourAPI로 수집된 모든 장소를 키워드로 검색해 기준 장소로 선택한다(동적 추천의 입구)
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);

  useEffect(() => {
    const keyword = query.trim();
    if (keyword.length < 2) {
      setResults([]);
      return undefined;
    }
    const timer = window.setTimeout(async () => {
      try {
        const response = await apiFetch(
          `/api/spots?keyword=${encodeURIComponent(keyword)}&size=8`,
        );
        setResults(response.items);
      } catch {
        setResults([]);
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const pick = (item) => {
    setQuery('');
    setResults([]);
    onPick(item.spot_id);
  };

  return (
    <div className="search-wrap">
      <label className="search-bar">
        <Search size={20} />
        <input
          value={query}
          placeholder="서울의 모든 장소 검색 (예: 창덕궁, 서울숲)"
          onChange={(event) => setQuery(event.target.value)}
          onBlur={() => window.setTimeout(() => setResults([]), 150)}
          disabled={disabled}
        />
      </label>
      {results.length > 0 && (
        <ul className="search-results" role="listbox">
          {results.map((item) => (
            <li key={item.spot_id}>
              {/* onMouseDown: input blur보다 먼저 실행돼 선택이 씹히지 않는다 */}
              <button onMouseDown={() => pick(item)}>
                <strong>{item.name}</strong>
                <small>{item.category_name} · {item.addr ?? '서울'}</small>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const companionOptions = [
  { value: '', label: '선택 안 함' },
  { value: 'solo', label: '혼자' },
  { value: 'couple', label: '둘이서' },
  { value: 'family', label: '가족과' },
];

// 동행을 고르면 추천을 '거르는' 게 아니라 그 동행에 맞는 곳을 먼저 보여주는 우선정렬 안내
const companionHints = {
  solo: '혼자 여행에 맞춰 한적하고 덜 알려진 곳을 먼저 보여드려요',
  couple: '둘이서 여행에 맞춰 포토스팟·자연·뷰가 좋은 곳을 먼저 보여드려요',
  family: '가족 여행에 맞춰 실내·이동이 편한 곳을 먼저 보여드려요',
};

// 홈 히어로 — '널널함'을 그린 살아있는 숲 풍경(3D 레이어드 씬).
// 원경 능선→중경 숲→근경 나무 순의 깊이 레이어에, 바람에 흔들리는 나무·
// 흐르는 안개·떨어지는 잎·숲길을 산책하는 사람을 CSS 애니메이션으로 움직인다.
function HeroScene() {
  const trees = [
    { x: 40, s: 1.15, d: 0 }, { x: 150, s: 0.85, d: 1.2 }, { x: 265, s: 1.3, d: 0.5 },
    { x: 420, s: 0.9, d: 1.8 }, { x: 560, s: 1.2, d: 0.9 }, { x: 700, s: 1.0, d: 0.2 },
    { x: 830, s: 1.35, d: 1.5 }, { x: 950, s: 0.8, d: 0.7 },
  ];
  return (
    <div className="hero-scene" aria-hidden="true">
      <div className="scene-sky" />
      <div className="scene-sun" />
      <div className="scene-cloud cloud-a" />
      <div className="scene-cloud cloud-b" />
      <svg className="scene-layer scene-far" viewBox="0 0 1000 240" preserveAspectRatio="xMidYMax slice">
        <path d="M0 240V150c60-40 130-70 210-64 90 7 150-42 240-40 100 3 160 50 260 46 110-4 180-55 290-40v188z" fill="currentColor" />
      </svg>
      <svg className="scene-layer scene-mid" viewBox="0 0 1000 220" preserveAspectRatio="xMidYMax slice">
        <path d="M0 220V120c80-25 140-52 220-48 90 5 150-30 250-26 100 5 170 36 270 30 90-6 170-30 260-18v162z" fill="currentColor" />
        {[90, 250, 430, 620, 810, 940].map((x, i) => (
          <g key={x} transform={`translate(${x} 130)`}>
            <g className="tree-sway mid-tree" style={{ '--sway-delay': `${i * 0.7}s` }}>
              <path d="M0-58C-15-40-22-20-22-4c0 14 10 24 22 24s22-10 22-24c0-16-7-36-22-54z" fill="currentColor" opacity="0.85" />
              <rect x="-2.4" y="16" width="4.8" height="20" rx="2" fill="currentColor" opacity="0.65" />
            </g>
          </g>
        ))}
      </svg>
      <div className="scene-mist mist-a" />
      <div className="scene-mist mist-b" />
      <svg className="scene-layer scene-near" viewBox="0 0 1000 200" preserveAspectRatio="xMidYMax slice">
        {/* 숲길 — 산책자가 걷는 길 */}
        <path d="M0 200V168c150-14 320-22 500-22s350 8 500 22v32z" fill="rgba(236, 244, 235, 0.2)" />
        {trees.map(({ x, s, d }) => (
          <g key={x} transform={`translate(${x} 172) scale(${s})`}>
            <g className="tree-sway near-tree" style={{ '--sway-delay': `${d}s` }}>
              <path d="M0-96C-22-70-34-40-34-14c0 22 15 36 34 36s34-14 34-36c0-26-12-56-34-82z" fill="currentColor" />
              <rect x="-3.5" y="20" width="7" height="26" rx="3" fill="currentColor" opacity="0.8" />
            </g>
          </g>
        ))}
      </svg>
      {/* 숲길을 산책하는 사람 — 왼쪽에서 오른쪽으로 여유롭게 */}
      <svg className="scene-person" viewBox="0 0 40 80">
        <g className="person-body">
          <circle cx="20" cy="12" r="7.5" fill="currentColor" />
          <rect x="14.5" y="21" width="11" height="26" rx="5.5" fill="currentColor" />
          <g className="person-leg leg-l"><rect x="15" y="45" width="5" height="24" rx="2.5" fill="currentColor" /></g>
          <g className="person-leg leg-r"><rect x="20" y="45" width="5" height="24" rx="2.5" fill="currentColor" /></g>
          <g className="person-arm"><rect x="12" y="23" width="4.5" height="19" rx="2.25" fill="currentColor" /></g>
        </g>
      </svg>
      {[0, 1, 2, 3, 4].map((i) => (
        <span key={i} className={`scene-leaf leaf-${i}`} />
      ))}
    </div>
  );
}

function HomeScreen({
  selectedTheme,
  visitDate,
  maxVisitDate,
  selectedSpotId,
  homeSpots,
  homeSpotTotal,
  homeCourses,
  visitedSpots,
  myCourses,
  impact,
  courseMode,
  freeSlots,
  companion,
  onCompanionChange,
  courseCreating,
  apiReady,
  homeLoading,
  onFind,
  onCreateFreeCourse,
  onCourseModeChange,
  onFreeSlotsChange,
  onOpenSpot,
  onOpenCourse,
  onVisitDateChange,
  onSpotChange,
  onThemeChange,
  savedIds,
  onToggleSaveSpot,
  savedCourseIds,
  onToggleSaveCourse,
}) {
  const featuredSpot = homeSpots.find((spot) => spot.spot_id === selectedSpotId) ?? homeSpots[0];
  const [heroCollapse, setHeroCollapse] = useState(0);
  const isFree = courseMode === 'free';
  const busy = homeLoading || courseCreating;

  useEffect(() => {
    const updateHeroCollapse = () => {
      const collapsedHeight = Math.max(460, Math.min(window.innerHeight * 0.67, 560));
      const expandedHeight = Math.max(collapsedHeight, window.innerHeight - 48);
      const progress = Math.min(window.scrollY / 180, 1);
      setHeroCollapse(Math.round((expandedHeight - collapsedHeight) * progress));
    };
    updateHeroCollapse();
    window.addEventListener('scroll', updateHeroCollapse, { passive: true });
    window.addEventListener('resize', updateHeroCollapse);
    return () => {
      window.removeEventListener('scroll', updateHeroCollapse);
      window.removeEventListener('resize', updateHeroCollapse);
    };
  }, []);

  const updateSlot = (index, value) => {
    onFreeSlotsChange(freeSlots.map((slot, i) => (i === index ? value : slot)));
  };
  const removeSlot = (index) => {
    if (freeSlots.length > 2) onFreeSlotsChange(freeSlots.filter((_, i) => i !== index));
  };
  const addSlot = () => {
    if (freeSlots.length < 4) onFreeSlotsChange([...freeSlots, '여행지']);
  };

  return (
    <section className="screen home-screen">
      <div className="hero-panel" style={{ '--hero-collapse': `${heroCollapse}px` }}>
        <HeroScene />
        <img className="hero-media" src={HERO_GIF} alt="" aria-hidden="true" />
        <div className="hero-overlay" />
        <div className="hero-content">
          <Tag icon={Sparkles}>
            {apiReady ? '실측 혼잡 데이터 기반 추천' : '데이터를 불러오는 중'}
          </Tag>
          <p className="hero-tagline">붐비는 곳 말고, 널널한 여행 — Null crowd, Full trip.</p>
          <h1>오늘은 어디로 떠나볼까요?</h1>
          <div className="hero-pillars">
            <span><Clock3 size={14} />시간 분산</span>
            <span><Compass size={14} />공간 분산</span>
            <span><Shuffle size={14} />추천 분산</span>
          </div>
          <SpotSearch onPick={onOpenSpot} disabled={!apiReady} />
        </div>
      </div>

      {/* 코스 조건 입력은 테마 탭 상단의 CourseFinder에서 제공한다. */}
      {/*
      <Card className="search-card">
        <div className="form-grid">
          <FilterControl icon={MapPin} label="지역">
            <strong>서울</strong>
          </FilterControl>
          <FilterControl icon={CalendarDays} label="기준일">
            <input
              type="date"
              value={visitDate}
              min={todayInSeoul()}
              max={maxVisitDate}
              onChange={(event) => onVisitDateChange(event.target.value)}
              disabled={homeLoading}
            />
          </FilterControl>
          <FilterControl icon={Navigation} label="기준 장소 (어디부터 시작할까요?)">
            <select
              value={selectedSpotId ?? ''}
              onChange={(event) => onSpotChange(Number(event.target.value))}
              disabled={homeLoading || !homeSpots.length}
            >
              {homeSpots.map((spot) => (
                <option key={spot.spot_id} value={spot.spot_id}>{spot.name}</option>
              ))}
            </select>
          </FilterControl>
          <FilterControl icon={Heart} label="동행">
            <select
              value={companion}
              onChange={(event) => onCompanionChange(event.target.value)}
              disabled={busy}
            >
              {companionOptions.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </FilterControl>
        </div>
        <div className="mode-row" role="tablist" aria-label="코스 스타일">
          <button
            className={`mode-chip ${!isFree ? 'is-active' : ''}`}
            onClick={() => onCourseModeChange('theme')}
            disabled={busy}
          >
            <Compass size={17} />
            <span>
              테마 유지 코스
              <small>같은 테마의 한적한 대안</small>
            </span>
          </button>
          <button
            className={`mode-chip ${isFree ? 'is-active' : ''}`}
            onClick={() => onCourseModeChange('free')}
            disabled={busy}
          >
            <Shuffle size={17} />
            <span>
              자유여행 코스
              <small>카테고리 섞어 일정 만들기</small>
            </span>
          </button>
        </div>

        {isFree ? (
          <div className="slot-builder">
            {freeSlots.map((slot, index) => (
              // eslint-disable-next-line react/no-array-index-key
              <div className="slot-item" key={index}>
                <span className="slot-no">{index + 1}</span>
                <select
                  value={slot}
                  onChange={(event) => updateSlot(index, event.target.value)}
                  disabled={busy}
                  aria-label={`${index + 1}번째 카테고리`}
                >
                  {slotThemeOptions.map((option) => (
                    <option key={option} value={option}>{option}</option>
                  ))}
                </select>
                {freeSlots.length > 2 && (
                  <button
                    className="slot-remove"
                    onClick={() => removeSlot(index)}
                    disabled={busy}
                    aria-label={`${index + 1}번째 단계 삭제`}
                  >
                    <X size={14} />
                  </button>
                )}
              </div>
            ))}
            {freeSlots.length < 4 && (
              <button className="slot-add" onClick={addSlot} disabled={busy}>
                + 단계 추가
              </button>
            )}
          </div>
        ) : (
          <div className="theme-row">
            {themes.map(({ label, icon: Icon }) => (
              <button
                className={`theme-chip ${selectedTheme === label ? 'is-active' : ''}`}
                key={label}
                onClick={() => onThemeChange(label)}
                disabled={busy}
              >
                <Icon size={17} />
                {label}
              </button>
            ))}
          </div>
        )}

        <Button
          full
          onClick={isFree ? onCreateFreeCourse : onFind}
          disabled={busy || !selectedSpotId}
        >
          {busy ? <>
            <Loader2 size={19} className="spin" />
            {courseCreating ? '자유여행 코스를 만드는 중' : '조건에 맞는 장소를 찾는 중'}
          </> : <>
            {isFree ? '자유여행 코스 만들기' : '이 조건으로 대안 코스 찾기'}
            <ArrowRight size={19} />
          </>}
        </Button>
      </Card>
      */}

      {/* 위치 기반 근처 추천 — MVP: 실제 GPS 대신 예시 위치 기준 목업 거리 */}
      <SectionHeader title="내 주변 널널 관광지" action={MOCK_LOCATION_LABEL} />
      <div className="course-carousel">
        {homeSpots.length ? withMockDistance(homeSpots).map((spot) => (
          <SpotCard
            key={spot.spot_id}
            spot={spot}
            selected={spot.spot_id === selectedSpotId}
            saved={savedIds.includes(spot.spot_id)}
            onToggleSave={() => onToggleSaveSpot(spot)}
            onClick={() => onOpenSpot(spot.spot_id)}
          />
        )) : <EmptyState />}
      </div>

      {homeCourses.length > 0 && (
        <>
          <SectionHeader title="인기 널널 코스" action="여행자들이 공유한 코스" />
          <div className="course-carousel">
            {homeCourses.map((course) => (
              <PopularCourseCard
                key={course.course_id}
                course={course}
                saved={savedCourseIds.includes(course.course_id)}
                onToggleSave={() => onToggleSaveCourse(course)}
                onClick={() => onOpenCourse(course.course_id)}
              />
            ))}
          </div>
        </>
      )}

      {myCourses.length > 0 && (
        <>
          <SectionHeader
            title="내 코스"
            action={`평균 혼잡 회피 ${Math.round(
              myCourses.reduce((sum, c) => sum + (c.relief_pct ?? 0), 0) / myCourses.length,
            )}% · ${myCourses.length}개`}
          />
          <div className="course-carousel">
            {myCourses.map((course) => (
              <button
                className="course-card discovery-card my-course-card"
                key={course.course_id}
                onClick={() => onOpenCourse(course.course_id)}
              >
                <span className="card-media">
                  <img src={imageUrl(course.image_url, course.title)} alt={course.title} />
                  <Bookmark className="card-bookmark" size={24} aria-hidden="true" />
                </span>
                <div className="course-card-body">
                  <h3>{course.title}</h3>
                  <div className="card-location"><span>{course.location ?? '서울'}</span><small>저장한 코스</small></div>
                  <p className="card-tags">#{course.tag ?? '널널여행'} #나만의코스</p>
                  <div className="mini-metrics">
                    <span>혼잡 회피 {Math.round(course.relief_pct ?? 0)}%</span>
                    <span>{course.duration_text ?? '추천 코스'}</span>
                  </div>
                </div>
              </button>
            ))}
          </div>
        </>
      )}

      {visitedSpots.length > 0 && (
        <>
          <SectionHeader title="최근 방문한 장소" />
          <div className="course-carousel visited-carousel">
            {visitedSpots.map((visited) => (
              <VisitedSpotCard
                key={visited.spot_id}
                spot={visited}
                onClick={() => onOpenSpot(visited.spot_id)}
              />
            ))}
          </div>
        </>
      )}

      <footer className="home-footer">
        <HomeStats
          impact={impact}
          homeSpotTotal={homeSpotTotal}
          featuredSpot={featuredSpot}
        />
      </footer>
    </section>
  );
}

function HomeStats({ impact, homeSpotTotal, featuredSpot }) {
  return (
    <div className="stats-grid">
      <StatCard
        label={`이번 주 덜 붐비게 다녀온 비율${impact?.includes_seed ? ' · 예시 포함' : ''}`}
        value={impact ? `${impact.avoid_rate_avg_pct}%` : '-'}
        icon={Leaf}
        tone="green"
      />
      <StatCard
        label={`이번 주 새로 발견한 덜 알려진 곳${impact?.includes_seed ? ' · 예시 포함' : ''}`}
        value={impact ? `${impact.hidden_pick_count.toLocaleString()}곳` : '-'}
        icon={Sparkles}
        tone="blue"
      />
      <StatCard label="골라 담을 수 있는 서울 관광지" value={homeSpotTotal ? homeSpotTotal.toLocaleString() : '-'} icon={Compass} tone="green" />
      <StatCard
        label={featuredSpot ? `${featuredSpot.name} 지금 얼마나 붐벼요` : '지금 얼마나 붐벼요'}
        value={featuredSpot ? `${Math.round(featuredSpot.risk)}%` : '-'}
        icon={MapIcon}
        tone="blue"
      />
    </div>
  );
}

function PopularCourseCard({ course, saved = false, onToggleSave, onClick }) {
  const handleBookmark = (event) => {
    event.stopPropagation();
    onToggleSave?.();
  };
  return (
    <button className="course-card discovery-card" onClick={onClick}>
      <span className="card-media">
        <SmartImage src={course.image_url} name={course.title} alt={course.title} />
        <span
          className={`card-bookmark ${saved ? 'is-saved' : ''}`}
          role="button"
          tabIndex={0}
          aria-label={saved ? '저장한 코스에서 빼기' : '저장한 코스에 담기'}
          aria-pressed={saved}
          onClick={handleBookmark}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              handleBookmark(event);
            }
          }}
        >
          <Bookmark size={20} fill={saved ? 'currentColor' : 'none'} />
        </span>
      </span>
      <div className="course-card-body">
        <h3>{course.title}</h3>
        <div className="card-location"><span>{course.location ?? '서울'}</span><small>혼잡 회피 {course.rate_pct}%</small></div>
        <p className="card-tags">#{course.tag ?? '널널여행'} #서울여행</p>
        <div className="mini-metrics">
          <span>여유로운 추천</span><span>{course.duration_text}</span>
        </div>
      </div>
    </button>
  );
}

// 최근 방문한 장소 — 관광지/코스 블럭(discovery-card)과 동일한 구조·이미지 비율
function VisitedSpotCard({ spot, onClick }) {
  const note = spot.last_rating
    ? `★ ${spot.last_rating}.0 후기 남김`
    : spot.last_perceived_label ?? '피드백 남김';

  return (
    <button className="course-card discovery-card visited-card" onClick={onClick}>
      <span className="card-media">
        <SmartImage src={spot.image_url} name={spot.name} alt={spot.name} />
        <span className="card-distance">
          <History size={12} />
          {spot.visited_text}
        </span>
      </span>
      <div className="course-card-body">
        <h3>{spot.name}</h3>
        <div className="card-location">
          <span>{spot.addr ?? spot.region ?? '서울'}</span>
          <small>방문 {spot.visit_count}회</small>
        </div>
        <p className="card-tags">{note}</p>
        <div className="mini-metrics">
          <span>지금 혼잡도</span>
          <span>{spot.label} {Math.round(spot.risk)}%</span>
        </div>
      </div>
    </button>
  );
}

// 관광지 소개 — 이미지 바로 아래, TourAPI overview(관광지별 상이)를 보여준다.
// overview가 없는 장소는 카테고리·주소로 만든 기본 소개 문장으로 대신한다.
function SpotIntro({ spot }) {
  const [expanded, setExpanded] = useState(false);
  if (!spot?.name) return null;
  const fallback = `${spot.name}은(는) ${spot.addr ?? spot.region ?? '서울'}에 있는 `
    + `${spot.category_name ?? '관광'} 명소예요. `
    + (spot.tags?.length ? `#${spot.tags.slice(0, 3).join(' #')} 테마로 둘러보기 좋아요.` : '');
  const text = spot.overview?.trim() || fallback;
  const long = text.length > 150;

  return (
    <Card className="spot-intro-card">
      <SectionHeader title="관광지 소개" compact />
      <p className={`spot-intro-text ${long && !expanded ? 'is-clamped' : ''}`}>{text}</p>
      {long && (
        <button className="spot-intro-more" onClick={() => setExpanded((v) => !v)}>
          {expanded ? '접기' : '더 보기'}
          <ChevronRight size={15} className={expanded ? 'is-open' : ''} />
        </button>
      )}
      {spot.highlight && (
        <p className="spot-intro-highlight">
          <Sparkles size={14} />
          {spot.highlight}
        </p>
      )}
    </Card>
  );
}

function DetailScreen({
  isSaved, onToggleSave, onFindAlternatives, spot,
  congestionView, congestionChart, calendar, activeSlot, onTimeShift,
}) {
  // 요일별(이번 주) 히트맵과 한 달 캘린더는 같은 정보의 기간 차이라 탭으로 합쳤다
  const [heatRange, setHeatRange] = useState('week');
  const chartData = congestionChart ?? [];
  const timeCards = mapTimeSlotCards(congestionView);
  const proof = spot?.proof ?? {};
  const reviewStats = spot?.review_stats ?? {};
  const suggestions = congestionView?.time_shift_suggestions ?? [];
  const hasCalendar = (calendar?.days?.length ?? 0) > 0;

  return (
    <section className="screen detail-screen">
      <div className="detail-hero">
        <SmartImage src={spot?.image_url} name={spot?.name} alt={spot?.name ?? '추천 관광지'} />
        <div className="detail-actions">
          <IconButton label="지도 열기" className="glass">
            <MapIcon size={19} />
          </IconButton>
          <IconButton
            label={isSaved ? '저장 해제' : '저장하기'}
            className={`glass ${isSaved ? 'saved' : ''}`}
            onClick={onToggleSave}
          >
            <Heart size={19} fill={isSaved ? 'currentColor' : 'none'} />
          </IconButton>
        </div>
        <div className="place-title">
          <span>{spot?.addr ?? spot?.region ?? '서울'}</span>
          <h2>{spot?.name ?? '관광지 정보를 불러오는 중'}</h2>
        </div>
      </div>

      <SpotIntro spot={spot} />

      <Card className="null-score-card">
        <div>
          <span className="eyebrow">
            {congestionView ? `널널도 · ${congestionView.based_on}` : '널널도'}
            {congestionView?.adjusted && (
              <em className="adjusted-chip">방문자 피드백 반영</em>
            )}
          </span>
          <strong>{congestionView?.label ?? '정보 준비 중'}</strong>
          <p>{congestionView?.tip ?? '혼잡도 정보를 불러오고 있어요.'}</p>
        </div>
        <CrowdBadge level={congestionView?.level ?? 1} size="large" />
      </Card>

      {suggestions.length > 0 && (
        <div className="suggestion-row">
          {suggestions.map((item) => (
            <button
              key={`${item.kind}-${item.date}-${item.time_slot}`}
              className="suggestion-chip"
              onClick={() => onTimeShift(item)}
            >
              <span className="suggestion-icon"><Clock3 size={17} /></span>
              <span className="suggestion-text">
                {item.text}
                <small>탭하면 이 시간으로 바꿔서 봐요</small>
              </span>
              <span className="suggestion-drop">붐빔 {item.decrease_pct}%↓</span>
              <ChevronRight size={18} className="suggestion-arrow" />
            </button>
          ))}
        </div>
      )}

      <ReviewProofCard proof={proof} reviewStats={reviewStats} />

      {/* 요일별 혼잡도 — 제목 아래 오전/오후/저녁 현재 혼잡도, 그 아래 요일 히트맵.
          한 달 캘린더는 같은 정보의 기간 확장이라 별도 카드 대신 탭으로 통합했다. */}
      <Card className="congestion-card">
        <SectionHeader title="요일별 혼잡도" compact />
        <div className="compare-grid">
          {timeCards.map((item) => (
            <TimeCard
              key={item.label}
              label={item.label}
              value={item.value}
              note={item.note}
              active={item.slot === activeSlot}
              onClick={() => onTimeShift({ kind: 'slot', time_slot: item.slot })}
            />
          ))}
        </div>

        {hasCalendar && (
          <div className="heat-range-tabs" role="tablist" aria-label="혼잡도 기간">
            <button
              role="tab"
              aria-selected={heatRange === 'week'}
              className={heatRange === 'week' ? 'is-active' : ''}
              onClick={() => setHeatRange('week')}
            >
              이번 주
            </button>
            <button
              role="tab"
              aria-selected={heatRange === 'month'}
              className={heatRange === 'month' ? 'is-active' : ''}
              onClick={() => setHeatRange('month')}
            >
              한 달
            </button>
          </div>
        )}

        {heatRange === 'week' || !hasCalendar ? (
          chartData.length ? <WeekdayHeat data={chartData} /> : <EmptyState />
        ) : (
          <>
            <p className="calendar-note">
              향후 30일 예측 기준 · 날짜를 탭하면 그 날로 이동해요
            </p>
            <CalendarHeat
              days={calendar.days}
              selectedDate={congestionView?.date}
              onPick={(day) => onTimeShift({
                kind: 'date', date: day.date, time_slot: activeSlot,
              })}
            />
          </>
        )}
      </Card>

      <Button full onClick={onFindAlternatives}>
        더 널널한 코스 보기
        <ArrowRight size={19} />
      </Button>
    </section>
  );
}

// 검색 탭 — 키워드 검색 + 서울 25개 자치구 + 카테고리 필터로 tourAPI 관광지 카탈로그 탐색
function RegionScreen({
  selectedDistrict, selectedCategory, spots, total, hasMore, loading,
  onSelectDistrict, onSelectCategory, onLoadMore, onOpenSpot, apiReady,
}) {
  const firstLoad = loading && spots.length === 0;
  return (
    <section className="screen region-screen">
      <div className="region-hero">
        <span className="eyebrow">서울 관광지 검색</span>
        <h1>어디로 떠나볼까요?</h1>
        <p className="region-note">이름으로 찾거나, 지역·카테고리를 골라 둘러보세요.</p>
        <SpotSearch onPick={onOpenSpot} disabled={!apiReady} />
        <label className="district-select">
          <MapPin size={17} />
          <select
            value={selectedDistrict}
            onChange={(event) => onSelectDistrict(event.target.value)}
            aria-label="지역(구) 선택"
          >
            <option value="">서울 전체</option>
            {SEOUL_DISTRICTS.map((gu) => (
              <option key={gu} value={gu}>{gu}</option>
            ))}
          </select>
          <ChevronRight size={16} className="district-caret" />
        </label>
        <div className="category-chips">
          {SEARCH_CATEGORIES.map((c) => (
            <button
              key={c}
              type="button"
              className={`category-chip ${selectedCategory === c ? 'is-active' : ''}`}
              onClick={() => onSelectCategory(c)}
            >
              {c}
            </button>
          ))}
        </div>
      </div>

      <div className="section-header">
        <h2>{selectedDistrict || '서울'} · {selectedCategory}</h2>
        {!firstLoad && total > 0 && <button type="button">{total.toLocaleString()}곳</button>}
      </div>

      {firstLoad ? (
        <div className="region-loading">
          <Loader2 size={22} className="spin" />
          관광지를 불러오는 중
        </div>
      ) : spots.length ? (
        <>
          <div className="region-results">
            {spots.map((spot) => (
              <RegionSpotCard key={spot.spot_id} spot={spot} onClick={() => onOpenSpot(spot.spot_id)} />
            ))}
          </div>
          {hasMore && (
            <button className="region-more" onClick={onLoadMore} disabled={loading}>
              {loading ? <><Loader2 size={17} className="spin" />불러오는 중</> : '더 보기'}
            </button>
          )}
        </>
      ) : (
        <EmptyState />
      )}
    </section>
  );
}

// AI 코스 탭 — 지역·코스 길이·동행·날짜만 고르면 널널한 일정을 만들어준다
const AI_DURATIONS = [
  { key: '3h', label: '3시간', desc: '가볍게 두 곳', stops: 2 },
  { key: 'half', label: '반나절', desc: '여유롭게 세 곳', stops: 3 },
  { key: 'day', label: '하루', desc: '느긋하게 네 곳', stops: 4 },
];
const AI_TIMESLOTS = [
  { key: 'morning', label: '오전' },
  { key: 'afternoon', label: '오후' },
  { key: 'evening', label: '저녁' },
];
const AI_THEMES = ['역사', '자연', '미식', '포토스팟', '쇼핑', '힐링'];
const AI_PACE = ['여유', '보통'];
const AI_INDOOR = [
  { key: '상관없음', label: '상관없음' },
  { key: '실내', label: '실내 위주' },
  { key: '실외', label: '실외 위주' },
];
// 이동 방식 — 도보면 도보권 후보로 좁혀 걷기 좋은 동선, 차량이면 넓은 반경 허용
const AI_TRANSPORT = [
  { key: 'walk', label: '도보', desc: '걸어서 이어지는 동선' },
  { key: 'car', label: '차량', desc: '차로 넓게 둘러보기' },
];

function AiCourseScreen({
  visitDate, maxVisitDate, companion, onCompanionChange,
  creating, apiReady, onCreate, results, myCourses, onOpenCourse,
  savedCourseIds = [], onToggleSaveCourse,
}) {
  const [district, setDistrict] = useState('종로구');
  const [duration, setDuration] = useState('half');
  const [date, setDate] = useState(visitDate);
  const [timeSlot, setTimeSlot] = useState('afternoon');
  const [themes, setThemes] = useState([]);          // 관심 테마(다중, 빈 배열=전체)
  const [pace, setPace] = useState('여유');
  const [indoor, setIndoor] = useState('상관없음');
  const [transport, setTransport] = useState('walk'); // 이동 방식(도보|차량)
  const selected = AI_DURATIONS.find((d) => d.key === duration);

  const toggleTheme = (t) =>
    setThemes((prev) => (prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]));

  const submit = () => onCreate({
    district, stops: selected?.stops ?? 3, companion, date,
    timeSlot, themes, pace, indoor, transport,
  });

  return (
    <section className="screen ai-course-screen">
      <div className="region-hero ai-hero">
        <span className="eyebrow"><Sparkles size={14} /> AI 코스 추천</span>
        <h1>조건만 고르면, 코스는 AI가.</h1>
        <p className="region-note">
          혼잡·날씨 데이터로 후보를 추리고, AI가 동선까지 고려해 여러 코스를 제안해요.
        </p>
      </div>

      <Card className="ai-form-card">
        <div className="ai-field">
          <span className="ai-field-label"><Clock3 size={16} />코스 길이</span>
          <div className="ai-duration-row">
            {AI_DURATIONS.map((option) => (
              <button
                key={option.key}
                className={`ai-duration ${duration === option.key ? 'is-active' : ''}`}
                onClick={() => setDuration(option.key)}
                disabled={creating}
              >
                <strong>{option.label}</strong>
                <small>{option.desc}</small>
              </button>
            ))}
          </div>
        </div>

        <div className="ai-field">
          <span className="ai-field-label"><Navigation size={16} />이동 방식</span>
          <div className="ai-duration-row two">
            {AI_TRANSPORT.map((option) => (
              <button
                key={option.key}
                className={`ai-duration ${transport === option.key ? 'is-active' : ''}`}
                onClick={() => setTransport(option.key)}
                disabled={creating}
              >
                <strong>{option.label}</strong>
                <small>{option.desc}</small>
              </button>
            ))}
          </div>
        </div>

        <div className="ai-field-grid">
          <div className="ai-field">
            <span className="ai-field-label"><MapPin size={16} />둘러볼 지역</span>
            <label className="district-select">
              <select
                value={district}
                onChange={(event) => setDistrict(event.target.value)}
                disabled={creating}
                aria-label="둘러볼 지역"
              >
                <option value="">서울 전체</option>
                {SEOUL_DISTRICTS.map((gu) => <option key={gu} value={gu}>{gu}</option>)}
              </select>
              <ChevronRight size={16} className="district-caret" />
            </label>
          </div>
          <div className="ai-field">
            <span className="ai-field-label"><UsersRound size={16} />동행</span>
            <label className="district-select">
              <select
                value={companion}
                onChange={(event) => onCompanionChange(event.target.value)}
                disabled={creating}
                aria-label="동행 유형"
              >
                {companionOptions.map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
              <ChevronRight size={16} className="district-caret" />
            </label>
          </div>
          <div className="ai-field">
            <span className="ai-field-label"><CalendarDays size={16} />날짜</span>
            <label className="district-select">
              <input
                type="date"
                value={date}
                min={todayInSeoul()}
                max={maxVisitDate}
                onChange={(event) => setDate(event.target.value)}
                disabled={creating}
                aria-label="여행 날짜"
              />
            </label>
          </div>
        </div>

        <div className="ai-field">
          <span className="ai-field-label"><Clock3 size={16} />시작 시간대</span>
          <div className="ai-chip-row">
            {AI_TIMESLOTS.map((slot) => (
              <button
                key={slot.key}
                className={`ai-chip ${timeSlot === slot.key ? 'is-active' : ''}`}
                onClick={() => setTimeSlot(slot.key)}
                disabled={creating}
              >
                {slot.label}
              </button>
            ))}
          </div>
        </div>

        <div className="ai-field">
          <span className="ai-field-label"><Heart size={16} />관심 테마 <small>(여러 개 선택 가능)</small></span>
          <div className="ai-chip-row wrap">
            {AI_THEMES.map((t) => (
              <button
                key={t}
                className={`ai-chip ${themes.includes(t) ? 'is-active' : ''}`}
                onClick={() => toggleTheme(t)}
                disabled={creating}
              >
                {t}
              </button>
            ))}
          </div>
        </div>

        <div className="ai-field-grid two">
          <div className="ai-field">
            <span className="ai-field-label"><Leaf size={16} />여행 페이스</span>
            <div className="ai-chip-row">
              {AI_PACE.map((p) => (
                <button
                  key={p}
                  className={`ai-chip ${pace === p ? 'is-active' : ''}`}
                  onClick={() => setPace(p)}
                  disabled={creating}
                >
                  {p}
                </button>
              ))}
            </div>
          </div>
          <div className="ai-field">
            <span className="ai-field-label"><Home size={16} />실내외</span>
            <div className="ai-chip-row">
              {AI_INDOOR.map((o) => (
                <button
                  key={o.key}
                  className={`ai-chip ${indoor === o.key ? 'is-active' : ''}`}
                  onClick={() => setIndoor(o.key)}
                  disabled={creating}
                >
                  {o.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <Button full disabled={creating || !apiReady} onClick={submit}>
          {creating ? <>
            <Loader2 size={19} className="spin" />
            AI가 널널한 동선을 계산하는 중
          </> : <>
            <Sparkles size={18} />
            {district || '서울'} {selected?.label} 코스 추천받기
          </>}
        </Button>
        <p className="ai-hint">
          <ShieldCheck size={14} />
          혼잡 실측·예측 데이터 기반 — 조건을 바꿔 다시 추천받을 수 있어요.
        </p>
      </Card>

      {results?.courses?.length > 0 && (
        <>
          <div className="section-header">
            <h2>AI가 제안한 코스</h2>
            <span className={`ai-source-badge ${results.source}`}>
              {results.source === 'llm' ? <><Sparkles size={13} />AI 추천</> : <><ShieldCheck size={13} />널널 알고리즘</>}
            </span>
          </div>
          <p className="ai-results-hint">
            <Bookmark size={14} />
            마음에 드는 코스는 북마크로 마이페이지 &lsquo;저장한 코스&rsquo;에 담을 수 있어요.
          </p>
          <div className="ai-results">
            {results.courses.map((course) => (
              <AiCourseCard
                key={course.course_id}
                course={course}
                saved={savedCourseIds.includes(course.course_id)}
                onToggleSave={() => onToggleSaveCourse?.(course)}
                onClick={() => onOpenCourse(course.course_id)}
              />
            ))}
          </div>
        </>
      )}

      {myCourses.length > 0 && (
        <>
          <div className="section-header">
            <h2>최근 만든 코스</h2>
          </div>
          <div className="region-results">
            {myCourses.slice(0, 3).map((course) => (
              <button key={course.course_id} className="region-spot-card ai-recent" onClick={() => onOpenCourse(course.course_id)}>
                <span className="region-spot-main">
                  <SmartImage src={course.image_url} name={course.title} alt={course.title} />
                  <span className="region-spot-body">
                    <span className="region-spot-top"><strong>{course.title}</strong></span>
                    <span className="region-spot-addr">혼잡 회피 {Math.round(course.relief_pct ?? 0)}%</span>
                  </span>
                  <ChevronRight size={20} className="region-spot-arrow" />
                </span>
              </button>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

// AI 추천 결과 카드 — 제목·컨셉·혼잡회피·동선 미리보기 + 마이페이지 저장 북마크
function AiCourseCard({ course, saved = false, onToggleSave, onClick }) {
  const stops = (course.timeline ?? []).map((t) => t.place);
  const handleBookmark = (event) => {
    event.stopPropagation();      // 카드 열기와 분리 — 북마크만 토글
    onToggleSave?.();
  };
  return (
    <button className="ai-course-card" onClick={onClick}>
      <div className="ai-course-top">
        <strong>{course.title}</strong>
        <span className="ai-course-actions">
          <span
            className={`ai-course-bookmark ${saved ? 'is-saved' : ''}`}
            role="button"
            tabIndex={0}
            aria-label={saved ? '저장한 코스에서 빼기' : '저장한 코스에 담기'}
            aria-pressed={saved}
            onClick={handleBookmark}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                handleBookmark(event);
              }
            }}
          >
            <Bookmark size={18} fill={saved ? 'currentColor' : 'none'} />
          </span>
          <ChevronRight size={20} />
        </span>
      </div>
      {course.description && <p className="ai-course-desc">{course.description}</p>}
      <div className="ai-course-route">
        <Route size={15} />
        <span>{stops.join(' → ')}</span>
      </div>
      <div className="ai-course-meta">
        <em className="relief"><ShieldCheck size={13} />혼잡 회피 {Math.round(course.summary?.relief_pct ?? 0)}%</em>
        <em><Clock3 size={13} />이동 {course.summary?.total_move_min ?? 0}분</em>
        <em><MapPin size={13} />{stops.length}곳</em>
      </div>
    </button>
  );
}

// 지역/저장 목록에 쓰는 가로형(리스트) 관광지 카드
function RegionSpotCard({ spot, onClick, onRemove }) {
  return (
    <div className="region-spot-card">
      <button className="region-spot-main" onClick={onClick}>
        <SmartImage src={spot.image_url} name={spot.name} alt={spot.name} />
        <span className="region-spot-body">
          <span className="region-spot-top">
            <strong>{spot.name}</strong>
            {typeof spot.level === 'number' && <CrowdBadge level={spot.level} />}
          </span>
          <span className="region-spot-addr">{spot.addr ?? spot.region ?? '서울'}</span>
          {(spot.best_time_slot_label || spot.tags?.[0]) && (
            <span className="region-spot-meta">
              {spot.best_time_slot_label && (
                <em><Clock3 size={13} />추천 {spot.best_time_slot_label}</em>
              )}
              {spot.tags?.[0] && <em>#{spot.tags[0]}</em>}
            </span>
          )}
        </span>
        {onRemove ? null : <ChevronRight size={20} className="region-spot-arrow" />}
      </button>
      {onRemove && (
        <button className="region-spot-remove" onClick={onRemove} aria-label="저장 해제">
          <Trash2 size={17} />
        </button>
      )}
    </div>
  );
}

// 마이페이지 — 일반적인 디지털 서비스의 프로필/메뉴 + 저장한 관광지·코스
function MyPageScreen({
  savedSpots, savedCourses, myCourses, activeCourse, onEndTravel,
  onOpenSpot, onOpenCourse, onRemoveSaved, onRemoveSavedCourse, onNotice,
}) {
  const menuItems = [
    { key: 'courses', icon: Compass, label: '내 코스', desc: `${myCourses.length}개 보관 중` },
    { key: 'alerts', icon: Bell, label: '알림 설정', desc: '혼잡 알림 받기' },
    { key: 'about', icon: Info, label: '서비스 소개', desc: 'Null crowd, Full trip' },
    { key: 'logout', icon: LogOut, label: '로그아웃', desc: '' },
  ];

  return (
    <section className="screen mypage-screen">
      <Card className="profile-card">
        <span className="profile-avatar"><UserRound size={30} /></span>
        <div className="profile-meta">
          <h1>널널한 여행자</h1>
          <p>붐비는 곳 말고, 널널하게 즐기는 중</p>
        </div>
        <button className="profile-settings" aria-label="프로필 설정" onClick={() => onNotice('준비 중인 기능이에요')}>
          <Settings size={19} />
        </button>
      </Card>

      <div className="mypage-stats">
        <div><strong>{savedSpots.length}</strong><span>저장한 관광지</span></div>
        <div><strong>{savedCourses.length}</strong><span>저장한 코스</span></div>
        <div><strong>{myCourses.length}</strong><span>내 코스</span></div>
      </div>

      {/* 여행하기로 선택한 코스 — 지금 사용 중인 코스를 맨 위에서 바로 연다 */}
      {activeCourse && (
        <>
          <div className="section-header compact">
            <h2>여행 중인 코스</h2>
            <button onClick={onEndTravel}>여행 마치기</button>
          </div>
          <div className="region-spot-card active-course-card">
            <button className="region-spot-main" onClick={() => onOpenCourse(activeCourse.course_id)}>
              <SmartImage src={activeCourse.image_url} name={activeCourse.title} alt={activeCourse.title} />
              <span className="region-spot-body">
                <span className="region-spot-top"><strong>{activeCourse.title}</strong></span>
                <span className="region-spot-addr">{activeCourse.location}</span>
                <span className="region-spot-meta">
                  <em><Navigation size={13} />여행 중</em>
                  {activeCourse.duration_text && <em><Clock3 size={13} />{activeCourse.duration_text}</em>}
                </span>
              </span>
              <ChevronRight size={20} className="region-spot-arrow" />
            </button>
          </div>
        </>
      )}

      <div className="section-header compact">
        <h2>저장한 관광지</h2>
      </div>
      {savedSpots.length ? (
        <div className="region-results">
          {savedSpots.map((spot) => (
            <RegionSpotCard
              key={spot.spot_id}
              spot={spot}
              onClick={() => onOpenSpot(spot.spot_id)}
              onRemove={() => onRemoveSaved(spot)}
            />
          ))}
        </div>
      ) : (
        <div className="mypage-empty">
          <Bookmark size={26} />
          <p>아직 저장한 관광지가 없어요.<br />관광지 상세에서 하트를 눌러 담아보세요.</p>
        </div>
      )}

      <div className="section-header compact">
        <h2>저장한 코스</h2>
      </div>
      {savedCourses.length ? (
        <div className="region-results">
          {savedCourses.map((course) => (
            <div className="region-spot-card" key={course.course_id}>
              <button className="region-spot-main" onClick={() => onOpenCourse(course.course_id)}>
                <SmartImage src={course.image_url} name={course.title} alt={course.title} />
                <span className="region-spot-body">
                  <span className="region-spot-top"><strong>{course.title}</strong></span>
                  <span className="region-spot-addr">{course.location}</span>
                  {course.duration_text && (
                    <span className="region-spot-meta"><em><Clock3 size={13} />{course.duration_text}</em></span>
                  )}
                </span>
              </button>
              <button className="region-spot-remove" onClick={() => onRemoveSavedCourse(course)} aria-label="코스 저장 해제">
                <Trash2 size={17} />
              </button>
            </div>
          ))}
        </div>
      ) : (
        <div className="mypage-empty">
          <Route size={26} />
          <p>아직 저장한 코스가 없어요.<br />홈 인기 코스에서 북마크를 눌러 담아보세요.</p>
        </div>
      )}

      <Card className="mypage-menu">
        {menuItems.map(({ key, icon: Icon, label, desc }) => (
          <button
            key={key}
            className="mypage-menu-item"
            onClick={() => (key === 'courses' && myCourses[0]
              ? onOpenCourse(myCourses[0].course_id)
              : onNotice('준비 중인 기능이에요'))}
          >
            <span className="mypage-menu-icon"><Icon size={18} /></span>
            <span className="mypage-menu-text">
              <strong>{label}</strong>
              {desc && <small>{desc}</small>}
            </span>
            <ChevronRight size={18} />
          </button>
        ))}
      </Card>
    </section>
  );
}

function CourseFinder({
  selectedTheme, visitDate, maxVisitDate, selectedSpotId, homeSpots,
  courseMode, freeSlots, companion, courseCreating, apiReady, homeLoading,
  onFind, onCreateFreeCourse, onCourseModeChange, onFreeSlotsChange,
  onCompanionChange, onVisitDateChange, onSpotChange, onThemeChange,
}) {
  const isFree = courseMode === 'free';
  const busy = homeLoading || courseCreating;
  const updateSlot = (index, value) => {
    onFreeSlotsChange(freeSlots.map((slot, i) => (i === index ? value : slot)));
  };
  const removeSlot = (index) => {
    if (freeSlots.length > 2) onFreeSlotsChange(freeSlots.filter((_, i) => i !== index));
  };
  const addSlot = () => {
    if (freeSlots.length < 4) onFreeSlotsChange([...freeSlots, '여행지']);
  };

  return (
    <Card className="search-card course-finder">
      <div className="finder-heading">
        <span className="eyebrow">테마별 추천 코스</span>
        <h1>오늘의 여유로운 코스를 찾아볼까요?</h1>
      </div>
      <div className="form-grid">
        <FilterControl icon={MapPin} label="지역"><strong>서울</strong></FilterControl>
        <FilterControl icon={CalendarDays} label="기준일">
          <input type="date" value={visitDate} min={todayInSeoul()} max={maxVisitDate}
            onChange={(event) => onVisitDateChange(event.target.value)} disabled={homeLoading} />
        </FilterControl>
        <FilterControl icon={Navigation} label="기준 장소">
          <select value={selectedSpotId ?? ''} onChange={(event) => onSpotChange(Number(event.target.value))}
            disabled={homeLoading || !homeSpots.length}>
            {homeSpots.map((spot) => <option key={spot.spot_id} value={spot.spot_id}>{spot.name}</option>)}
          </select>
        </FilterControl>
        <FilterControl icon={Heart} label="동행">
          <select value={companion} onChange={(event) => onCompanionChange(event.target.value)} disabled={busy}>
            {companionOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </FilterControl>
      </div>
      <div className="mode-row" role="tablist" aria-label="코스 스타일">
        <button className={`mode-chip ${!isFree ? 'is-active' : ''}`} onClick={() => onCourseModeChange('theme')} disabled={busy}>
          <Compass size={17} /><span>테마 유지 코스<small>같은 테마의 한적한 대안</small></span>
        </button>
        <button className={`mode-chip ${isFree ? 'is-active' : ''}`} onClick={() => onCourseModeChange('free')} disabled={busy}>
          <Shuffle size={17} /><span>자유여행 코스<small>카테고리 섞어 일정 만들기</small></span>
        </button>
      </div>
      {isFree ? (
        <div className="slot-builder">
          {freeSlots.map((slot, index) => (
            <div className="slot-item" key={`${slot}-${index}`}>
              <span className="slot-no">{index + 1}</span>
              <select value={slot} onChange={(event) => updateSlot(index, event.target.value)} disabled={busy} aria-label={`${index + 1}번째 카테고리`}>
                {slotThemeOptions.map((option) => <option key={option} value={option}>{option}</option>)}
              </select>
              {freeSlots.length > 2 && <button className="slot-remove" onClick={() => removeSlot(index)} disabled={busy} aria-label={`${index + 1}번째 단계 삭제`}><X size={14} /></button>}
            </div>
          ))}
          {freeSlots.length < 4 && <button className="slot-add" onClick={addSlot} disabled={busy}>+ 단계 추가</button>}
        </div>
      ) : (
        <div className="theme-row">
          {themes.map(({ label, icon: Icon }) => (
            <button className={`theme-chip ${selectedTheme === label ? 'is-active' : ''}`} key={label}
              onClick={() => onThemeChange(label)} disabled={busy}><Icon size={17} />{label}</button>
          ))}
        </div>
      )}
      <Button full onClick={isFree ? onCreateFreeCourse : onFind} disabled={busy || !selectedSpotId}>
        {busy ? <><Loader2 size={19} className="spin" />{courseCreating ? '자유여행 코스를 만드는 중' : '조건에 맞는 장소를 찾는 중'}</> : <>
          {isFree ? '자유여행 코스 만들기' : '이 조건으로 추천 코스 찾기'}<ArrowRight size={19} />
        </>}
      </Button>
    </Card>
  );
}

function AlternativesScreen({
  setModal, onCreateCourse, alternativeView, companion, ...finderProps
}) {
  const origin = alternativeView?.origin;
  const recommendationList = alternativeView?.alternatives?.map(mapAlternative) ?? [];
  const routeSummary = alternativeView?.route_summary;

  return (
    <section className="screen alternatives-screen">
      <CourseFinder companion={companion} {...finderProps} />
      <div className="alternative-layout">
        <div className="recommendation-column">
          <Card className="original-card">
            <div className="mini-photo">
              <img src={imageUrl(origin?.image_url, origin?.name)} alt={origin?.name ?? '원래 관광지'} />
            </div>
            <div>
              <span className="eyebrow">원래 가려던 곳</span>
              <h2>{origin?.name ?? '선택한 관광지'}</h2>
              <p>{origin ? `예상 혼잡도 ${Math.round(origin.risk)}%` : '추천 정보를 불러오고 있어요.'}</p>
            </div>
            <ArrowDown className="down-arrow" size={20} />
          </Card>

          {companion && companionHints[companion] && (
            <div className="companion-hint">
              <UsersRound size={16} />
              {companionHints[companion]}
            </div>
          )}

          <CrowdLegend />

          <div className="recommendation-list">
            {recommendationList.length ? recommendationList.map((item) => (
              <AlternativeCard
                key={item.title}
                item={item}
                onReason={() => setModal(item)}
                onSelect={onCreateCourse}
              />
            )) : <EmptyState />}
          </div>

          {recommendationList.length > 0 && (
            <div className="alt-cta">
              <p>위 {recommendationList.length}곳을 이동 동선에 맞춰 하나의 코스로 묶어드려요.</p>
              <Button full onClick={onCreateCourse}>
                이 대안들로 코스 만들기
                <ArrowRight size={19} />
              </Button>
            </div>
          )}
        </div>

        <Card className="map-card">
          <div className="map-header">
            <div>
              <span className="eyebrow">경로 지도</span>
              <h2>{origin ? `${origin.name} 주변 여유 루트` : '여유 루트'}</h2>
            </div>
            <Navigation size={21} />
          </div>
          <LeafletPointsMap
            points={origin?.lat ? [
              {
                lat: origin.lat, lng: origin.lng,
                pin: '출발', className: 'is-origin', tooltip: origin.name,
              },
              ...(alternativeView?.alternatives ?? []).map((alt, index) => ({
                lat: alt.lat, lng: alt.lng, pin: String(index + 1),
                className: `is-level-${alt.level}`,
                tooltip: `${alt.name} · ${alt.label}`,
              })),
            ] : []}
          />
          <div className="map-summary">
            <span>총 {routeSummary?.total_distance_km ?? '-'}km</span>
            <span>차량 {routeSummary?.total_drive_min ?? '-'}분</span>
            <span>도보 {routeSummary?.total_walk_km ?? '-'}km</span>
          </div>
        </Card>
      </div>
    </section>
  );
}

function CourseScreen({
  courseView,
  courseAlternatives,
  showToast,
  onSwap,
  onReroll,
  rerolling,
  onSubmitFeedback,
  onSubmitReview,
  onShareCourse,
  sharing = false,
  onStartTravel,
  activeCourseId = null,
}) {
  const [feedback, setFeedback] = useState('');
  const [rating, setRating] = useState(4);
  const [selectedTags, setSelectedTags] = useState(['한산했어요']);
  const [reviewText, setReviewText] = useState('');
  const timelineItems = courseView?.timeline ?? [];
  const summary = courseView?.summary;
  const reviewItems = courseView?.reviews?.recent ?? [];
  const isFree = courseView?.mode === 'free';
  const swapSlots = (courseAlternatives?.items ?? []).filter(
    (slot) => slot.alternatives.length,
  );

  // 코스 시간대 기준 도착 시각 — 체류·이동 시간을 누적해 계산
  const minutesOf = (text) => Number(text?.match(/(\d+)\s*분/)?.[1] ?? 0);
  let clock = (SLOT_START_HOUR[courseView?.time_slot] ?? 14) * 60;
  const timedItems = timelineItems.map((item) => {
    const arrival = `${Math.floor(clock / 60)}:${String(clock % 60).padStart(2, '0')}`;
    clock += minutesOf(item.meta) + minutesOf(item.move);
    return { ...item, arrival };
  });

  const toggleTag = (tag) => {
    setSelectedTags((current) =>
      current.includes(tag) ? current.filter((item) => item !== tag) : [...current, tag],
    );
  };

  const handleFeedback = async (label, perceived) => {
    setFeedback(label);
    try {
      await onSubmitFeedback(perceived);
      showToast('피드백이 반영됐어요');
    } catch (error) {
      console.warn(error);
      showToast('피드백 저장 중 문제가 생겼어요');
    }
  };

  const handleReviewSubmit = async () => {
    try {
      await onSubmitReview({ rating, tags: selectedTags, text: reviewText });
      setReviewText('');
      showToast('후기가 저장됐어요');
    } catch (error) {
      console.warn(error);
      showToast('후기 저장 중 문제가 생겼어요');
    }
  };

  return (
    <section className="screen course-screen">
      <Card className="course-summary-hero">
        <div>
          <Tag icon={isFree ? Shuffle : Leaf}>{isFree ? '자유여행 코스' : '추천 코스'}</Tag>
          {courseView?.companion_label && (
            <Tag icon={UsersRound}>{courseView.companion_label}</Tag>
          )}
          <h1>{courseView?.title ?? '생성한 코스를 불러오고 있어요.'}</h1>
          <p>{courseView?.description ?? '대안 관광지를 선택하면 실제 데이터로 코스를 구성합니다.'}</p>
        </div>
        <CrowdBadge level={courseView?.level ?? 1} size="large" />
      </Card>

      <div className="timeline">
        {timedItems.length ? timedItems.map((item, index) => (
          <TimelineItem key={item.place} item={item} index={index} isLast={index === timedItems.length - 1} />
        )) : <EmptyState />}
      </div>

      {(courseView?.map_points?.length ?? 0) > 1 && (
        <Card className="course-map-card">
          <SectionHeader title="코스 동선" compact />
          <LeafletPointsMap
            points={courseView.map_points.map((p) => ({
              lat: p.lat, lng: p.lng,
              pin: p.order_no === 0 ? '출발' : String(p.order_no),
              className: p.order_no === 0 ? 'is-origin' : '',
              tooltip: p.name,
            }))}
          />
        </Card>
      )}

      <Card className="summary-card">
        <SummaryMetric label="예상 혼잡 감소" value={summary ? `${summary.relief_pct}%` : '-'} />
        <SummaryMetric
          label={isFree ? '카테고리 일치 정도' : '테마 유지 정도'}
          value={summary ? `${summary.theme_keep_pct}%` : '-'}
        />
        <SummaryMetric label="총 이동시간" value={summary ? `${summary.total_move_min}분` : '-'} />
        <SummaryMetric
          label="총 이동거리"
          value={summary?.total_distance_km ? `${summary.total_distance_km}km` : '-'}
        />
      </Card>

      {/* 여행하기 — 이 코스를 사용하겠다는 선택. 여행 중인 코스로 지정 + 마이페이지 저장 */}
      {courseView?.course_id && (
        courseView.course_id === activeCourseId ? (
          <div className="share-done travel-active">
            <Navigation size={17} />
            지금 이 코스로 여행 중이에요 — 마이페이지에서 확인할 수 있어요
          </div>
        ) : (
          <Button full onClick={() => onStartTravel?.(courseView)}>
            <Navigation size={18} />
            이 코스로 여행하기
          </Button>
        )
      )}

      {courseView?.course_id && (
        <button className="button button-full share-course-button" onClick={onReroll} disabled={rerolling}>
          {rerolling ? <>
            <Loader2 size={18} className="spin" />
            다른 조합을 찾는 중
          </> : <>
            <Shuffle size={18} />
            다른 코스 추천
          </>}
        </button>
      )}

      {/* F9 코스 공유 — 공개하면 홈 '인기 널널 코스'에 노출된다 */}
      {courseView?.course_id && (
        courseView.is_shared ? (
          <div className="share-done">
            <Check size={17} />
            공개된 코스예요 — 홈 인기 널널 코스에서 다른 여행자에게 보여요
          </div>
        ) : (
          <button className="button button-full share-course-button" onClick={onShareCourse} disabled={sharing}>
            {sharing ? <>
              <Loader2 size={18} className="spin" />
              코스를 공개하는 중
            </> : <>
              <Share2 size={18} />
              이 코스를 다른 여행자에게 공유하기
            </>}
          </button>
        )
      )}

      {swapSlots.length > 0 && (
        <Card className="swap-card">
          <SectionHeader title="이 코스의 대안" compact />
          <p className="swap-hint">
            마음에 안 드는 장소는 탭 한 번으로 바꿔보세요. 원래 코스도 그대로 남아요.
          </p>
          {swapSlots.map((slot) => (
            <div className="swap-slot" key={slot.order_no}>
              <div className="swap-current">
                <span className="swap-order">{slot.order_no}</span>
                <strong>{slot.name}</strong>
                {slot.slot_theme && <Tag>{slot.slot_theme}</Tag>}
                <RefreshCcw size={15} />
              </div>
              <div className="swap-options">
                {slot.alternatives.map((alt) => (
                  <button
                    key={alt.spot_id}
                    className="swap-option"
                    onClick={() => onSwap(slot.order_no, alt.spot_id)}
                    title={alt.reason}
                  >
                    <img src={imageUrl(alt.image_url, alt.name)} alt={alt.name} />
                    <span className="swap-body">
                      <span className="swap-name">
                        {alt.name}
                        <CrowdBadge level={alt.level} />
                      </span>
                      <span className="swap-metrics">
                        혼잡 -{alt.decrease_pct}% · 이동 {alt.travel_time_min}분
                        {alt.hidden_gem ? ' · 숨은 명소' : ''}
                      </span>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </Card>
      )}

      <Card className="feedback-card">
        <SectionHeader title="방문 후 피드백" compact />
        <div className="feedback-grid">
          {[
            { label: '생각보다 한산했어요', icon: ThumbsUp, perceived: -1 },
            { label: '예상과 비슷했어요', icon: Check, perceived: 0 },
            { label: '생각보다 붐볐어요', icon: ThumbsDown, perceived: 1 },
          ].map(({ label, icon: Icon, perceived }) => (
            <button
              key={label}
              className={`feedback-button ${feedback === label ? 'is-selected' : ''}`}
              onClick={() => handleFeedback(label, perceived)}
            >
              <Icon size={19} />
              {label}
            </button>
          ))}
        </div>
      </Card>

      <ReviewComposer
        rating={rating}
        setRating={setRating}
        selectedTags={selectedTags}
        toggleTag={toggleTag}
        reviewText={reviewText}
        setReviewText={setReviewText}
        onSubmit={handleReviewSubmit}
      />

      <RecentReviews reviews={reviewItems} />
    </section>
  );
}

function ReviewProofCard({ proof, reviewStats }) {
  const avgRating = reviewStats.avg_rating ?? 0;
  const reviewCount = reviewStats.count ?? 0;

  return (
    <Card className="review-proof-card">
      <div className="review-proof-head">
        <span className="review-score">{Number(avgRating).toFixed(1)}</span>
        <div>
          <StarRating rating={Math.round(avgRating)} readonly compact />
          <p>방문 후기 {reviewCount.toLocaleString()}개 기반</p>
        </div>
      </div>
      <div className="proof-bars">
        <ProofBar label="한산함 예측 정확도" value={proof.prediction_accuracy_pct ?? 0} />
        <ProofBar label="동선 편안함" value={proof.route_comfort_pct ?? 0} />
        <ProofBar label="테마 만족도" value={proof.theme_satisfaction_pct ?? 0} />
      </div>
    </Card>
  );
}

function ReviewComposer({
  rating,
  setRating,
  selectedTags,
  toggleTag,
  reviewText,
  setReviewText,
  onSubmit,
}) {
  return (
    <Card className="review-composer">
      <SectionHeader title="방문 후기 작성" compact />
      <div className="rating-panel">
        <span className="eyebrow">여행 만족도</span>
        <StarRating rating={rating} onChange={setRating} />
        <strong>{rating}.0</strong>
      </div>

      <div className="review-tags">
        {reviewTags.map((tag) => (
          <button
            key={tag}
            className={selectedTags.includes(tag) ? 'is-selected' : ''}
            onClick={() => toggleTag(tag)}
          >
            {tag}
          </button>
        ))}
      </div>

      <label className="review-textarea">
        <MessageSquareText size={18} />
        <textarea
          value={reviewText}
          onChange={(event) => setReviewText(event.target.value)}
          placeholder="이 코스가 얼마나 여유로웠는지 알려주세요."
        />
      </label>

      <div className="review-actions">
        <button className="photo-button">
          <ImagePlus size={18} />
          사진 추가
        </button>
        <Button onClick={onSubmit}>
          후기 등록
          <ArrowRight size={18} />
        </Button>
      </div>
    </Card>
  );
}

function RecentReviews({ reviews }) {
  return (
    <Card className="recent-reviews">
      <SectionHeader title="최근 방문 후기" compact />
      {reviews.length ? <div className="review-list">
        {reviews.map((review) => {
          const name = review.name ?? review.nickname;
          const date = review.date ?? review.date_text;
          return (
          <article className="review-item" key={`${name}-${date}-${review.text}`}>
            <div className="review-item-head">
              <div className="avatar">{name.slice(0, 1)}</div>
              <div>
                <strong>{name}</strong>
                <span>{date}</span>
              </div>
              <StarRating rating={review.rating} readonly compact />
            </div>
            <p>{review.text}</p>
            <div className="review-tag-list">
              {review.tags.map((tag) => (
                <span key={tag}>{tag}</span>
              ))}
            </div>
          </article>
          );
        })}
      </div> : <EmptyState />}
    </Card>
  );
}

function StarRating({ rating, onChange, readonly = false, compact = false }) {
  return (
    <div className={`star-rating ${compact ? 'is-compact' : ''}`} aria-label={`별점 ${rating}점`}>
      {[1, 2, 3, 4, 5].map((value) => (
        <button
          key={value}
          className={value <= rating ? 'is-filled' : ''}
          onClick={() => onChange?.(value)}
          disabled={readonly}
          aria-label={`${value}점`}
        >
          <Star size={compact ? 14 : 24} fill="currentColor" />
        </button>
      ))}
    </div>
  );
}

function ProofBar({ label, value }) {
  return (
    <div className="proof-bar">
      <div>
        <span>{label}</span>
        <strong>{value}%</strong>
      </div>
      <i>
        <b style={{ width: `${value}%` }} />
      </i>
    </div>
  );
}

function FilterControl({ icon: Icon, label, children }) {
  return (
    <div className="filter-field">
      <span>
        <Icon size={18} />
      </span>
      <label>
        <small>{label}</small>
        {children}
      </label>
    </div>
  );
}

function Button({ children, full = false, onClick, disabled = false }) {
  return (
    <button className={`button ${full ? 'button-full' : ''}`} onClick={onClick} disabled={disabled}>
      {children}
    </button>
  );
}

function Card({ children, className = '' }) {
  return <div className={`card ${className}`}>{children}</div>;
}

function Tag({ children, icon: Icon }) {
  return (
    <span className="tag">
      {Icon && <Icon size={15} />}
      {children}
    </span>
  );
}

function CrowdBadge({ level, size = 'normal' }) {
  const item = crowdLevels[level - 1];
  return (
    <span className={`crowd-badge ${item.className} ${size === 'large' ? 'is-large' : ''}`}>
      <span>{item.value}</span>
      {item.label}
    </span>
  );
}

function CrowdLegend() {
  // 널널도 5단계 색 범례 — 초록(널널)에서 빨강(붐빔)까지 뜻을 한눈에
  return (
    <div className="crowd-legend" aria-label="널널도 5단계 안내">
      {crowdLevels.map((lv) => (
        <span key={lv.value} className={`legend-item ${lv.className}`}>
          <i />
          {lv.label}
        </span>
      ))}
    </div>
  );
}

function StatCard({ label, value, icon: Icon, tone }) {
  return (
    <Card className={`stat-card ${tone}`}>
      <span>
        <Icon size={22} />
      </span>
      <div>
        <strong>{value}</strong>
        <p>{label}</p>
      </div>
    </Card>
  );
}

function SectionHeader({ title, action, compact = false }) {
  return (
    <div className={`section-header ${compact ? 'compact' : ''}`}>
      <h2>{title}</h2>
      {action && <button>{action}</button>}
    </div>
  );
}

function SpotCard({ spot, selected = false, saved = false, onToggleSave, onClick }) {
  const tag = spot.tags?.[0] ?? spot.category_name ?? '서울 관광지';
  const handleBookmark = (event) => {
    event.stopPropagation();      // 카드 열기(onClick)와 분리 — 북마크만 토글
    onToggleSave?.();
  };

  return (
    <button className={`course-card discovery-card ${selected ? 'is-selected' : ''}`} onClick={onClick}>
      <span className="card-media">
        <SmartImage src={spot.image_url} name={spot.name} alt={spot.name} />
        {spot.distance_km != null && (
          <span className="card-distance">
            <Navigation size={12} />
            {spot.distance_km}km · 도보 {spot.walk_min}분
          </span>
        )}
        <span
          className={`card-bookmark ${saved ? 'is-saved' : ''}`}
          role="button"
          tabIndex={0}
          aria-label={saved ? '저장한 관광지에서 빼기' : '저장한 관광지에 담기'}
          aria-pressed={saved}
          onClick={handleBookmark}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              handleBookmark(event);
            }
          }}
        >
          <Bookmark size={20} fill={saved ? 'currentColor' : 'none'} />
        </span>
      </span>
      <div className="course-card-body">
        <h3>{spot.name}</h3>
        <div className="card-location"><span>{spot.addr ?? spot.region ?? '서울'}</span><small>{spot.label} {Math.round(spot.risk)}%</small></div>
        <p className="card-tags">#{tag} #서울나들이</p>
        <div className="mini-metrics">
          <span>추천 시간</span><span>{spot.best_time_slot_label ?? '확인 중'}</span>
        </div>
      </div>
    </button>
  );
}

// 요일 × 시간대(오전/오후/저녁) 혼잡도를 색으로 한눈에 보는 히트맵(그래프 대체)
const HEAT_SLOT_ROWS = [
  { key: 'morning', label: '오전' },
  { key: 'afternoon', label: '오후' },
  { key: 'evening', label: '저녁' },
];

function WeekdayHeat({ data }) {
  return (
    <div className="weekday-heat-wrap">
      <div className="weekday-heat" role="table" aria-label="요일별·시간대별 혼잡도">
        <div className="weekday-heat-row weekday-heat-head" role="row">
          <span className="wh-slot" aria-hidden="true" />
          {data.map((row) => (
            <span key={row.day} className="wh-day" role="columnheader">{row.day}</span>
          ))}
        </div>
        {HEAT_SLOT_ROWS.map((slot) => (
          <div className="weekday-heat-row" role="row" key={slot.key}>
            <span className="wh-slot" role="rowheader">{slot.label}</span>
            {data.map((row) => {
              const cell = row[slot.key] ?? { risk: 0, level: 1 };
              return (
                <span
                  key={row.day}
                  className={`wh-cell heat-${cell.level}`}
                  title={`${row.day}요일 ${slot.label} · ${crowdLevels[cell.level - 1]?.label ?? ''} ${cell.risk}%`}
                >
                  {cell.risk}
                </span>
              );
            })}
          </div>
        ))}
      </div>
      <div className="weekday-heat-legend">
        <span>여유</span>
        <i className="heat-1" /><i className="heat-2" /><i className="heat-3" /><i className="heat-4" /><i className="heat-5" />
        <span>혼잡</span>
      </div>
    </div>
  );
}

function TimeCard({ label, value, note, active = false, onClick }) {
  return (
    <button className={`card time-card ${active ? 'is-active' : ''}`} onClick={onClick}>
      <Clock3 size={19} />
      <small>{label}</small>
      <strong>{value}</strong>
      <span>{note}</span>
    </button>
  );
}

function CalendarHeat({ days, selectedDate, onPick }) {
  // 첫 주 시작 요일에 맞춰 빈 칸을 채워 실제 달력 형태로 그린다(월요일 시작)
  const firstWeekday = new Date(`${days[0].date}T00:00:00`).getDay();  // 0=일
  const leadingBlanks = (firstWeekday + 6) % 7;
  return (
    <div className="calendar-heat" role="grid" aria-label="30일 널널도 캘린더">
      {['월', '화', '수', '목', '금', '토', '일'].map((day) => (
        <span className="heat-head" key={day}>{day}</span>
      ))}
      {Array.from({ length: leadingBlanks }, (_, i) => (
        <span className="heat-cell is-blank" key={`blank-${i}`} />
      ))}
      {days.map((day) => (
        <button
          key={day.date}
          className={[
            'heat-cell', `heat-${day.level}`,
            day.date === selectedDate ? 'is-selected' : '',
            day.is_holiday ? 'is-holiday' : '',
          ].join(' ')}
          onClick={() => onPick(day)}
          title={`${day.date} · ${day.label}`}
        >
          {Number(day.date.slice(-2))}
        </button>
      ))}
    </div>
  );
}

function AlternativeCard({ item, onReason, onSelect }) {
  return (
    <Card className="alternative-card">
      <button className="alt-main" onClick={onSelect}>
        <img src={item.image} alt={item.title} />
        <div className="alt-content">
          <div className="alt-title-row">
            <h3>{item.title}</h3>
            <CrowdBadge level={item.level} />
          </div>
          <div className="alt-metrics">
            <Metric label="혼잡 감소율" value={item.decrease} />
            <Metric label="이동시간" value={item.move} />
            <Metric label="테마 유사도" value={item.similarity} />
          </div>
          {(item.hiddenGem || item.loadPenalty > 0) && (
            <div className="alt-chips">
              {item.hiddenGem && <span className="chip chip-gem">숨은 명소</span>}
              {item.loadPenalty > 0 && (
                <span className="chip chip-rotation" title="한 곳에 추천이 몰리지 않게 여러 장소를 번갈아 보여드려요">
                  번갈아 추천
                </span>
              )}
            </div>
          )}
        </div>
      </button>
      <button className="reason-button" onClick={onReason}>
        추천 이유 보기
        <ChevronRight size={17} />
      </button>
    </Card>
  );
}

function Metric({ label, value }) {
  return (
    <span>
      <small>{label}</small>
      <strong>{value}</strong>
    </span>
  );
}

function LeafletPointsMap({ points }) {
  // CARTO Voyager 타일 — 무료·키/도메인 등록 불필요이고 앱 파스텔 톤과 어울린다.
  // 카카오맵 키(도메인 등록) 확정 후 이 컴포넌트만 교체하면 된다.
  // points: [{ lat, lng, pin, className, tooltip }] — 대안 경로·코스 동선이 공유한다.
  const hostRef = useRef(null);
  const pointsKey = points.map((p) => `${p.lat},${p.lng}`).join('|');

  useEffect(() => {
    if (!hostRef.current || !points.length) return undefined;
    // 드래그·줌 버튼·더블클릭 확대는 켜고, 휠 줌만 꺼 페이지 스크롤과 충돌을 막는다
    // (정적 이미지가 아니라 실제로 조작되는 지도로 보이게).
    const map = L.map(hostRef.current, {
      scrollWheelZoom: false,
      zoomControl: true,
      attributionControl: true,
    });
    L.tileLayer(
      'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png',
      {
        maxZoom: 20,
        subdomains: 'abcd',
        detectRetina: true,
        attribution: '© OpenStreetMap · © CARTO',
      },
    ).addTo(map);

    const pin = (label, className) => L.divIcon({
      className: '',
      html: `<span class="map-pin ${className ?? ''}">${label}</span>`,
      iconSize: [30, 30],
      iconAnchor: [15, 15],
    });
    points.forEach((p) => {
      const marker = L.marker([p.lat, p.lng], { icon: pin(p.pin, p.className) }).addTo(map);
      if (p.tooltip) marker.bindTooltip(p.tooltip, { direction: 'top' });
    });

    const coords = points.map((p) => [p.lat, p.lng]);
    if (coords.length > 1) {
      L.polyline(coords, {
        color: '#3d8567', weight: 4, opacity: 0.85, dashArray: '9 11',
      }).addTo(map);
    }
    map.fitBounds(L.latLngBounds(coords).pad(0.3));
    // 그리드 레이아웃 안에서 마운트 직후 컨테이너 크기가 0으로 잡히면 지도가
    // 회색으로 비어 보인다 — 레이아웃 확정 후 크기를 재계산한다.
    const resizeTimer = window.setTimeout(() => {
      map.invalidateSize();
      map.fitBounds(L.latLngBounds(coords).pad(0.3));
    }, 150);
    return () => {
      window.clearTimeout(resizeTimer);
      map.remove();
    };
  }, [pointsKey]);

  if (!points.length) {
    return <div className="route-map"><Skeleton /></div>;
  }
  // 둥근 클립은 바깥 래퍼(.route-map)가 맡고, Leaflet 루트(.leaflet-host)는 사각형 자식으로 둔다.
  // 라운드+overflow가 걸린 요소가 Leaflet 컨테이너를 겸하면 타일 합성 레이어가 단색 블록으로 깨진다.
  return (
    <div className="route-map map-clip">
      <div className="leaflet-host" ref={hostRef} aria-label="경로 지도" />
    </div>
  );
}

function TimelineItem({ item, index, isLast }) {
  return (
    <div className={`timeline-item ${isLast ? 'is-last' : ''}`}>
      <div className="timeline-marker">{index + 1}</div>
      <Card>
        <div className="timeline-body">
          {item.image_url && (
            <img className="timeline-thumb" src={imageUrl(item.image_url, item.place)} alt={item.place} />
          )}
          <div className="timeline-main">
            <div className="timeline-top">
              <h3>
                {item.place}
                {item.slot_theme && <span className="slot-theme-chip">{item.slot_theme}</span>}
              </h3>
              <span>{item.arrival ? `${item.arrival} 도착 · ${item.meta}` : item.meta}</span>
            </div>
            <p>{item.note}</p>
            <div className="timeline-move">
              <Navigation size={16} />
              {item.move}
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
}

function SummaryMetric({ label, value }) {
  return (
    <div>
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

function EmptyState() {
  return (
    <div className="empty-state">
      <MapIcon size={28} />
      <p>조건에 맞는 코스를 준비하고 있어요.</p>
    </div>
  );
}

function Skeleton() {
  return <span className="skeleton-line" aria-hidden="true" />;
}

// AlternativeScore(9-2) 항목별 표시 정의 — "추천 근거 수치화"를 그대로 보여준다
const BREAKDOWN_ROWS = [
  { key: 'theme_similarity', label: '테마 유사도' },
  { key: 'relief', label: '혼잡 완화 효과' },
  { key: 'mobility', label: '이동 편의성' },
  { key: 'hidden', label: '덜 알려진 곳' },
  { key: 'weather', label: '날씨 적합성' },
];

function ReasonModal({ item, onClose }) {
  const breakdown = item.breakdown ?? {};
  const loadPenalty = breakdown.load_penalty ?? 0;
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <Card className="modal-card">
        <button className="modal-close" onClick={onClose} aria-label="닫기">
          <X size={18} />
        </button>
        <img src={item.image} alt={item.title} />
        <h2>{item.title}</h2>
        <p>{item.reason}</p>

        {item.breakdown && (
          <div className="breakdown">
            <span className="eyebrow">추천 점수 구성 (종합 {item.score?.toFixed(2)})</span>
            {BREAKDOWN_ROWS.map(({ key, label }) => {
              const value = breakdown[key];
              if (value === null || value === undefined) {
                return (
                  <div className="proof-bar is-muted" key={key}>
                    <div><span>{label}</span><strong>예보 범위 밖 — 제외</strong></div>
                    <i><b style={{ width: 0 }} /></i>
                  </div>
                );
              }
              const negative = value < 0;
              return (
                <div className={`proof-bar ${negative ? 'is-negative' : ''}`} key={key}>
                  <div>
                    <span>{label}</span>
                    <strong>{negative ? '' : '+'}{Math.round(value * 100)}%</strong>
                  </div>
                  <i><b style={{ width: `${Math.min(Math.abs(value) * 100, 100)}%` }} /></i>
                </div>
              );
            })}
            <div className={`proof-bar ${loadPenalty > 0 ? 'is-negative' : 'is-muted'}`}>
              <div>
                <span>추천 쏠림 조정</span>
                <strong>{loadPenalty > 0 ? `−${Math.round(loadPenalty * 100)}%` : '없음'}</strong>
              </div>
              <i><b style={{ width: `${Math.min(loadPenalty * 1000, 100)}%` }} /></i>
            </div>
          </div>
        )}

        <Button full onClick={onClose}>
          확인
        </Button>
      </Card>
    </div>
  );
}

function AdminScreen({ onExit }) {
  // F8 로테이션·수집 상태 시연 화면(데모 시나리오 ⑦) — #admin 해시 + 토큰으로 진입
  const [token, setToken] = useState(() => sessionStorage.getItem('nullnull.admin-token') ?? '');
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const response = await apiFetch('/api/admin/ingest-log', {
        headers: { 'X-Admin-Token': token },
      });
      sessionStorage.setItem('nullnull.admin-token', token);
      setData(response);
    } catch (err) {
      setData(null);
      setError(err.message ?? '불러오지 못했어요');
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="screen admin-screen">
      <Card className="admin-head">
        <div>
          <Tag icon={ShieldCheck}>관리자</Tag>
          <h1>수집 상태 · 추천 부하 분포(F8)</h1>
          <p>공사 OpenAPI 수집 로그와 대안지 로테이션 현황을 확인합니다.</p>
        </div>
        <button className="reason-button" onClick={onExit}>서비스 화면으로</button>
      </Card>

      <Card className="admin-token-card">
        <label className="search-bar">
          <ShieldCheck size={18} />
          <input
            type="password"
            value={token}
            placeholder="X-Admin-Token"
            onChange={(event) => setToken(event.target.value)}
            onKeyDown={(event) => event.key === 'Enter' && load()}
          />
        </label>
        <Button onClick={load} disabled={loading || !token}>
          {loading ? <Loader2 size={17} className="spin" /> : '불러오기'}
        </Button>
      </Card>
      {error && <p className="admin-error">{error}</p>}

      {data && (
        <>
          <Card>
            <SectionHeader title="대안지 추천 부하(최근 7일)" compact />
            <p className="calendar-note">
              노출 + 선택×2를 후보군 내 최대값으로 정규화한 값 — 부하가 높을수록
              다음 추천에서 페널티를 받아 자연 로테이션됩니다.
            </p>
            <div className="proof-bars">
              {data.load_distribution.slice(0, 10).map((row) => (
                <div className="proof-bar" key={row.spot_id}>
                  <div>
                    <span>{row.name} · 노출 {row.exposures} / 선택 {row.selections}</span>
                    <strong>{Math.round(row.load * 100)}%</strong>
                  </div>
                  <i><b style={{ width: `${row.load * 100}%` }} /></i>
                </div>
              ))}
            </div>
          </Card>

          <Card>
            <SectionHeader title="공사 API 수집 로그" compact />
            <div className="ingest-table">
              {data.ingest.map((log, index) => (
                <div className={`ingest-row is-${log.status}`} key={`${log.api_name}-${index}`}>
                  <strong>{log.api_name}</strong>
                  <span>{log.status}{log.records ? ` · ${log.records}건` : ''}</span>
                  <small>{log.last_synced_at.replace('T', ' ').slice(0, 16)}</small>
                  {log.error_message && <p>{log.error_message}</p>}
                </div>
              ))}
            </div>
          </Card>
        </>
      )}
    </section>
  );
}

function ConnectionBanner({ onRetry, loading }) {
  return (
    <div className="connection-banner" role="alert">
      <div>
        <strong>백엔드에 연결하지 못했어요</strong>
        <p>서버가 켜져 있는지 확인 후 다시 시도해주세요. (심사장 오프라인 시 데모 모드로 기동)</p>
      </div>
      <button onClick={onRetry} disabled={loading}>
        {loading ? <Loader2 size={16} className="spin" /> : <RefreshCcw size={16} />}
        다시 시도
      </button>
    </div>
  );
}

function Toast({ message }) {
  return (
    <div className="toast">
      <Check size={18} />
      {message}
    </div>
  );
}

function IconButton({ children, label, onClick, className = '' }) {
  return (
    <button className={`icon-button ${className}`} onClick={onClick} aria-label={label} title={label}>
      {children}
    </button>
  );
}

// 하단 탭이 아닌 하위 화면(관광지 상세·코스 결과)에서 강조할 탭 매핑
const NAV_ACTIVE_KEY = {
  detail: 'region',            // 관광지 상세는 '검색'에서 진입
  alternatives: 'course-ai',   // 대안 보기는 코스 생성 계열
  course: 'course-ai',         // 코스 결과는 'AI 코스' 계열
};

function BottomNavigation({ active, onChange, hidden = false }) {
  const items = [
    { key: 'home', label: '홈', icon: Home },
    { key: 'region', label: '검색', icon: Search },
    { key: 'course-ai', label: 'AI 코스', icon: Route },
    { key: 'mypage', label: '마이페이지', icon: UserRound },
  ];

  return (
    <nav className={`bottom-nav${hidden ? ' is-hidden' : ''}`} aria-hidden={hidden}>
      {items.map(({ key, label, icon: Icon }) => (
        <button key={key} className={active === key ? 'is-active' : ''} onClick={() => onChange(key)}>
          <Icon size={20} />
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
}

createRoot(document.getElementById('root')).render(<App />);
