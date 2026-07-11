import React, { useEffect, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import {
  ArrowDown,
  ArrowRight,
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
  Leaf,
  Loader2,
  ImagePlus,
  LocateFixed,
  Map,
  MapPin,
  MessageSquareText,
  Navigation,
  RefreshCcw,
  Search,
  Share2,
  Shuffle,
  Sparkles,
  Star,
  ThumbsDown,
  ThumbsUp,
  Trees,
  UsersRound,
  X,
} from 'lucide-react';
import './styles.css';

const assets = {
  hero: '/assets/hero-coastal-path.png',
  forest: '/assets/forest-temple.png',
  lake: '/assets/lakeside-village.png',
  cafe: '/assets/cafe-alley.png',
};

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

function imageUrl(path, fallback = assets.hero) {
  if (!path) return fallback;
  if (path.startsWith('http') || path.startsWith('/')) return path;
  return `/${path}`;
}

function mapAlternative(item) {
  return {
    spotId: item.spot_id,
    title: item.name,
    image: imageUrl(item.image_url),
    decrease: `${item.decrease_pct}%`,
    move: `${item.travel_time_min}분`,
    similarity: `${item.similarity_pct}%`,
    level: item.level,
    reason: item.reason,
  };
}

function mergeCongestionChart(morning, afternoon, evening) {
  // 시간대별 널널도 3회 조회 결과를 요일 차트(오전/오후/저녁)로 병합
  if (!afternoon?.weekday_comparison?.length) return [];
  return afternoon.weekday_comparison.slice(0, 7).map((item, i) => ({
    day: item.day,
    morning: Math.round(morning?.weekday_comparison?.[i]?.risk ?? item.risk),
    afternoon: Math.round(item.risk),
    evening: Math.round(evening?.weekday_comparison?.[i]?.risk ?? item.risk),
  }));
}

function mapTimeSlotCards(response) {
  if (!response?.time_slots?.length) return [];
  return response.time_slots.map((slot) => ({
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

function todayInSeoul() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
}

function dateAfter(date, days) {
  const target = new Date(`${date}T00:00:00Z`);
  target.setUTCDate(target.getUTCDate() + days);
  return target.toISOString().slice(0, 10);
}

function homeSpotsPath({ date, theme }) {
  const params = new URLSearchParams({ region: '서울', date, limit: '6' });
  if (theme !== '전체') params.set('themes', theme);
  return `/api/spots/home?${params}`;
}

async function fetchSpotContext(spotId, date, theme) {
  const alternativeParams = new URLSearchParams({ date, limit: '3' });
  if (theme !== '전체') alternativeParams.set('themes', theme);
  const [detail, morningView, afternoonView, eveningView, alternativesData] = await Promise.all([
    apiFetch(`/api/spots/${spotId}`),
    apiFetch(`/api/spots/${spotId}/congestion?date=${date}&time_slot=morning`),
    apiFetch(`/api/spots/${spotId}/congestion?date=${date}&time_slot=afternoon`),
    apiFetch(`/api/spots/${spotId}/congestion?date=${date}&time_slot=evening`),
    apiFetch(`/api/spots/${spotId}/alternatives?${alternativeParams}`),
  ]);

  return {
    detail,
    congestionView: afternoonView,
    congestionChart: mergeCongestionChart(morningView, afternoonView, eveningView),
    alternativeView: alternativesData,
  };
}

function App() {
  const [screen, setScreen] = useState('home');
  const [selectedTheme, setSelectedTheme] = useState('전체');
  const [visitDate, setVisitDate] = useState(todayInSeoul);
  const [selectedSpotId, setSelectedSpotId] = useState(null);
  const [homeLoading, setHomeLoading] = useState(false);
  const [modal, setModal] = useState(null);
  const [toast, setToast] = useState('');
  const [saved, setSaved] = useState(false);
  const [apiReady, setApiReady] = useState(false);
  const [homeSpots, setHomeSpots] = useState([]);
  const [homeSpotTotal, setHomeSpotTotal] = useState(0);
  const [homeCourses, setHomeCourses] = useState([]);
  const [visitedSpots, setVisitedSpots] = useState([]);
  const [courseMode, setCourseMode] = useState('theme');       // theme(테마 유지) | free(자유여행)
  const [freeSlots, setFreeSlots] = useState(defaultFreeSlots);
  const [courseCreating, setCourseCreating] = useState(false);
  const [courseRerolling, setCourseRerolling] = useState(false);
  const [spot, setSpot] = useState(null);
  const [spotDetail, setSpotDetail] = useState(null);
  const [congestionView, setCongestionView] = useState(null);
  const [congestionChart, setCongestionChart] = useState(null);
  const [alternativeView, setAlternativeView] = useState(null);
  const [courseView, setCourseView] = useState(null);
  const [courseAlternatives, setCourseAlternatives] = useState(null);

  const screenTitle = useMemo(
    () => ({
      home: 'Nullnull',
      detail: '관광지 상세',
      alternatives: '대안 코스',
      course: '코스 상세',
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
    setCongestionView(context.congestionView);
    setCongestionChart(context.congestionChart);
    setAlternativeView(context.alternativeView);
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

  const openCourse = async (courseId) => {
    try {
      setCourseView(await apiFetch(`/api/courses/${courseId}`));
      setScreen('course');
    } catch (error) {
      console.warn(error);
      showToast('코스를 불러오지 못했어요');
    }
  };

  const openSpot = async (spotId) => {
    try {
      setSelectedSpotId(spotId);
      applySpotContext(await fetchSpotContext(spotId, visitDate, selectedTheme));
      setScreen('detail');
    } catch (error) {
      console.warn(error);
      showToast('관광지 정보를 불러오지 못했어요');
    }
  };

  useEffect(() => {
    let ignore = false;

    async function loadInitialData() {
      try {
        await apiFetch('/api/health');
        const [popularList, homeSpotResponse, visitedResponse] = await Promise.all([
          apiFetch('/api/courses/popular?limit=3'),
          apiFetch(homeSpotsPath({ date: visitDate, theme: selectedTheme })),
          apiFetch('/api/spots/visited?limit=6').catch(() => ({ items: [] })),
        ]);
        if (ignore) return;

        const firstSpot = homeSpotResponse.items[0] ?? null;
        setApiReady(true);
        applyHomeResponse(homeSpotResponse);
        setHomeCourses(popularList);
        setVisitedSpots(visitedResponse.items);
        setSpot(firstSpot);

        if (firstSpot) {
          const context = await fetchSpotContext(firstSpot.spot_id, visitDate, selectedTheme);
          if (ignore) return;
          applySpotContext(context);
        }
      } catch (error) {
        console.warn('Nullnull API 연결에 실패했습니다.', error);
        if (!ignore) setApiReady(false);
      }
    }

    loadInitialData();
    return () => {
      ignore = true;
    };
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
    try {
      applySpotContext(await fetchSpotContext(originSpotId, visitDate, selectedTheme));
      setScreen('alternatives');
    } catch (error) {
      console.warn(error);
      showToast('대안 코스를 불러오지 못했어요');
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
        }),
      });
      setCourseView(course);
      setScreen('course');
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
        }),
      });
      setCourseView(course);
      setScreen('course');
    } catch (error) {
      console.warn(error);
      showToast(error.message ?? '자유여행 코스를 만들지 못했어요');
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

  return (
    <main className="app-shell">
      <div className="app-frame">
        <Header title={screenTitle} screen={screen} setScreen={setScreen} />

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
            courseMode={courseMode}
            freeSlots={freeSlots}
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
          />
        )}
        {screen === 'detail' && (
          <DetailScreen
            saved={saved}
            setSaved={setSaved}
            setScreen={setScreen}
            showToast={showToast}
            spot={spotDetail ?? spot}
            congestionView={congestionView}
            congestionChart={congestionChart}
          />
        )}
        {screen === 'alternatives' && (
          <AlternativesScreen
            setModal={setModal}
            onCreateCourse={createCourseFromAlternatives}
            alternativeView={alternativeView}
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
          />
        )}

        <BottomNavigation active={screen} onChange={setScreen} />
      </div>

      {modal && <ReasonModal item={modal} onClose={() => setModal(null)} />}
      {toast && <Toast message={toast} />}
    </main>
  );
}

function Header({ title, screen, setScreen }) {
  return (
    <header className="top-bar">
      <button className="brand-button" onClick={() => setScreen('home')} aria-label="홈으로 이동">
        <span className="brand-mark">
          <Leaf size={19} strokeWidth={2.5} />
        </span>
        <span>{screen === 'home' ? 'Nullnull' : title}</span>
      </button>
      <div className="top-actions">
        <IconButton label="공유">
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

  const handleChange = (value) => {
    setQuery(value);
    const picked = results.find((item) => item.name === value);
    if (picked) {
      setQuery('');
      setResults([]);
      onPick(picked.spot_id);
    }
  };

  return (
    <label className="search-bar">
      <Search size={20} />
      <input
        list="spot-search-options"
        value={query}
        placeholder="서울의 모든 장소 검색 (예: 창덕궁, 서울숲)"
        onChange={(event) => handleChange(event.target.value)}
        disabled={disabled}
      />
      <datalist id="spot-search-options">
        {results.map((item) => (
          <option key={item.spot_id} value={item.name}>
            {`${item.category_name} · ${item.addr ?? '서울'}`}
          </option>
        ))}
      </datalist>
    </label>
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
  courseMode,
  freeSlots,
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
}) {
  const featuredSpot = homeSpots.find((spot) => spot.spot_id === selectedSpotId) ?? homeSpots[0];
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
    <section className="screen home-screen">
      <div className="hero-panel">
        <img src={imageUrl(featuredSpot?.image_url)} alt={featuredSpot?.name ?? '서울 관광지'} />
        <div className="hero-overlay" />
        <div className="hero-content">
          <Tag icon={Sparkles}>
            {apiReady ? featuredSpot?.based_on ?? '서울 관광 데이터' : '데이터를 불러오는 중'}
          </Tag>
          <h1>{featuredSpot ? `${featuredSpot.name}부터 여유롭게 둘러보세요.` : '서울의 여유로운 장소를 찾고 있어요.'}</h1>
          <SpotSearch onPick={onOpenSpot} disabled={!apiReady} />
        </div>
      </div>

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
          <FilterControl icon={UsersRound} label="방문 장소">
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

      <div className="stats-grid">
        <StatCard
          label="TourAPI 서울 관광지"
          value={homeSpotTotal ? homeSpotTotal.toLocaleString() : '-'}
          icon={Compass}
          tone="green"
        />
        <StatCard
          label={featuredSpot ? `${featuredSpot.name} 현재 혼잡도` : '현재 혼잡도'}
          value={featuredSpot ? `${Math.round(featuredSpot.risk)}%` : '-'}
          icon={Map}
          tone="blue"
        />
      </div>

      <SectionHeader title={`${selectedTheme === '전체' ? '서울' : selectedTheme} 테마 추천 관광지`} />
      <div className="course-carousel">
        {homeSpots.length ? homeSpots.map((spot) => (
          <SpotCard
            key={spot.spot_id}
            spot={spot}
            selected={spot.spot_id === selectedSpotId}
            onClick={() => onOpenSpot(spot.spot_id)}
          />
        )) : <EmptyState />}
      </div>

      {homeCourses.length > 0 && (
        <>
          <SectionHeader title="인기 널널 코스" />
          <div className="course-carousel">
            {homeCourses.map((course) => (
              <PopularCourseCard
                key={course.course_id}
                course={course}
                onClick={() => onOpenCourse(course.course_id)}
              />
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
    </section>
  );
}

function PopularCourseCard({ course, onClick }) {
  return (
    <button className="course-card" onClick={onClick}>
      <img src={imageUrl(course.image_url)} alt={course.title} />
      <div className="course-card-body">
        <div className="course-card-top">
          <Tag>{course.tag}</Tag>
          <CrowdBadge level={course.level} />
        </div>
        <h3>{course.title}</h3>
        <p>{course.location}</p>
        <div className="mini-metrics">
          <span>혼잡 회피 {course.rate_pct}%</span>
          <span>{course.duration_text}</span>
        </div>
      </div>
    </button>
  );
}

function VisitedSpotCard({ spot, onClick }) {
  const note = spot.last_rating
    ? `★ ${spot.last_rating}.0 후기 남김`
    : spot.last_perceived_label ?? '피드백 남김';

  return (
    <button className="course-card visited-card" onClick={onClick}>
      <img src={imageUrl(spot.image_url)} alt={spot.name} />
      <div className="course-card-body">
        <div className="course-card-top">
          <Tag icon={History}>{spot.visited_text}</Tag>
          <CrowdBadge level={spot.level} />
        </div>
        <h3>{spot.name}</h3>
        <p>{note}</p>
        <div className="mini-metrics">
          <span>지금 {spot.label} {Math.round(spot.risk)}%</span>
          <span>방문 {spot.visit_count}회</span>
        </div>
      </div>
    </button>
  );
}

function DetailScreen({ saved, setSaved, setScreen, showToast, spot, congestionView, congestionChart }) {
  const chartData = congestionChart ?? [];
  const timeCards = mapTimeSlotCards(congestionView);
  const proof = spot?.proof ?? {};
  const reviewStats = spot?.review_stats ?? {};

  return (
    <section className="screen detail-screen">
      <div className="detail-hero">
        <img src={imageUrl(spot?.image_url)} alt={spot?.name ?? '추천 관광지'} />
        <div className="detail-actions">
          <IconButton label="지도 열기" className="glass">
            <Map size={19} />
          </IconButton>
          <IconButton
            label="즐겨찾기"
            className={`glass ${saved ? 'saved' : ''}`}
            onClick={() => {
              setSaved(!saved);
              showToast(saved ? '즐겨찾기에서 제거했어요' : '즐겨찾기에 담았어요');
            }}
          >
            <Heart size={19} fill={saved ? 'currentColor' : 'none'} />
          </IconButton>
        </div>
        <div className="place-title">
          <span>{spot?.addr ?? spot?.region ?? '서울'}</span>
          <h2>{spot?.name ?? '관광지 정보를 불러오는 중'}</h2>
        </div>
      </div>

      <Card className="null-score-card">
        <div>
          <span className="eyebrow">
            {congestionView ? `널널도 · ${congestionView.based_on}` : '널널도'}
          </span>
          <strong>{congestionView?.label ?? '정보 준비 중'}</strong>
          <p>{congestionView?.tip ?? '혼잡도 정보를 불러오고 있어요.'}</p>
        </div>
        <CrowdBadge level={congestionView?.level ?? 1} size="large" />
      </Card>

      <ReviewProofCard proof={proof} reviewStats={reviewStats} />

      <Card>
        <SectionHeader title="요일별 혼잡도" compact />
        {chartData.length ? <>
          <BarChart data={chartData} />
          <div className="chart-legend">
            <span><i className="morning" />오전</span>
            <span><i className="afternoon" />오후</span>
            <span><i className="evening" />저녁</span>
          </div>
        </> : <EmptyState />}
      </Card>

      <div className="compare-grid">
        {timeCards.map((item) => (
          <TimeCard key={item.label} label={item.label} value={item.value} note={item.note} />
        ))}
      </div>

      <Button full onClick={() => setScreen('alternatives')}>
        더 널널한 코스 보기
        <ArrowRight size={19} />
      </Button>
    </section>
  );
}

function AlternativesScreen({ setModal, onCreateCourse, alternativeView }) {
  const origin = alternativeView?.origin;
  const recommendationList = alternativeView?.alternatives?.map(mapAlternative) ?? [];
  const routeSummary = alternativeView?.route_summary;

  return (
    <section className="screen alternatives-screen">
      <div className="alternative-layout">
        <div className="recommendation-column">
          <Card className="original-card">
            <div className="mini-photo">
              <img src={imageUrl(origin?.image_url)} alt={origin?.name ?? '원래 관광지'} />
            </div>
            <div>
              <span className="eyebrow">원래 가려던 곳</span>
              <h2>{origin?.name ?? '선택한 관광지'}</h2>
              <p>{origin ? `예상 혼잡도 ${Math.round(origin.risk)}%` : '추천 정보를 불러오고 있어요.'}</p>
            </div>
            <ArrowDown className="down-arrow" size={20} />
          </Card>

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
        </div>

        <Card className="map-card">
          <div className="map-header">
            <div>
              <span className="eyebrow">경로 지도</span>
              <h2>{origin ? `${origin.name} 주변 여유 루트` : '여유 루트'}</h2>
            </div>
            <Navigation size={21} />
          </div>
          <RouteMap />
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
          <h1>{courseView?.title ?? '생성한 코스를 불러오고 있어요.'}</h1>
          <p>{courseView?.description ?? '대안 관광지를 선택하면 실제 데이터로 코스를 구성합니다.'}</p>
        </div>
        <CrowdBadge level={courseView?.level ?? 1} size="large" />
      </Card>

      <div className="timeline">
        {timelineItems.length ? timelineItems.map((item, index) => (
          <TimelineItem key={item.place} item={item} index={index} isLast={index === timelineItems.length - 1} />
        )) : <EmptyState />}
      </div>

      <Card className="summary-card">
        <SummaryMetric label="예상 혼잡 감소" value={summary ? `${summary.relief_pct}%` : '-'} />
        <SummaryMetric
          label={isFree ? '카테고리 일치율' : '테마 유지율'}
          value={summary ? `${summary.theme_keep_pct}%` : '-'}
        />
        <SummaryMetric label="총 이동시간" value={summary ? `${summary.total_move_min}분` : '-'} />
      </Card>

      {courseView?.course_id && (
        <Button full onClick={onReroll} disabled={rerolling}>
          {rerolling ? <>
            <Loader2 size={18} className="spin" />
            다른 조합을 찾는 중
          </> : <>
            <Shuffle size={18} />
            다른 코스 추천
          </>}
        </Button>
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
                    <img src={imageUrl(alt.image_url)} alt={alt.name} />
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

function SpotCard({ spot, selected = false, onClick }) {
  const tag = spot.tags?.[0] ?? spot.category_name ?? '서울 관광지';

  return (
    <button className={`course-card ${selected ? 'is-selected' : ''}`} onClick={onClick}>
      <img src={imageUrl(spot.image_url)} alt={spot.name} />
      <div className="course-card-body">
        <div className="course-card-top">
          <Tag>{tag}</Tag>
          <CrowdBadge level={spot.level} />
        </div>
        <h3>{spot.name}</h3>
        <p>{spot.addr ?? spot.region}</p>
        <div className="mini-metrics">
          <span>{spot.label} {Math.round(spot.risk)}%</span>
          <span>추천 {spot.best_time_slot_label}</span>
        </div>
      </div>
    </button>
  );
}

function BarChart({ data }) {
  return (
    <div className="bar-chart">
      {data.map((item) => (
        <div className="bar-day" key={item.day}>
          <div className="bar-stack">
            <span className="bar morning" style={{ height: `${item.morning}%` }} />
            <span className="bar afternoon" style={{ height: `${item.afternoon}%` }} />
            <span className="bar evening" style={{ height: `${item.evening}%` }} />
          </div>
          <strong>{item.day}</strong>
        </div>
      ))}
    </div>
  );
}

function TimeCard({ label, value, note }) {
  return (
    <Card className="time-card">
      <Clock3 size={19} />
      <small>{label}</small>
      <strong>{value}</strong>
      <span>{note}</span>
    </Card>
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

function RouteMap() {
  return (
    <div className="route-map" aria-label="추천 경로 지도">
      <svg viewBox="0 0 320 360" role="img">
        <path className="water" d="M0 0H320V360H0z" />
        <path className="park" d="M-20 250C50 200 96 225 144 176C188 131 219 91 340 122V380H-20Z" />
        <path className="road" d="M38 296C72 240 122 260 144 206C164 156 209 149 241 101" />
        <path className="route" d="M57 283C86 235 129 247 151 199C171 154 209 143 245 102" />
        {[{ x: 57, y: 283 }, { x: 151, y: 199 }, { x: 245, y: 102 }].map((p, i) => (
          <g key={`${p.x}-${p.y}`}>
            <circle className="marker-glow" cx={p.x} cy={p.y} r="17" />
            <circle className="marker" cx={p.x} cy={p.y} r="9" />
            <text x={p.x} y={p.y + 4}>{i + 1}</text>
          </g>
        ))}
      </svg>
      <Skeleton />
    </div>
  );
}

function TimelineItem({ item, index, isLast }) {
  return (
    <div className={`timeline-item ${isLast ? 'is-last' : ''}`}>
      <div className="timeline-marker">{index + 1}</div>
      <Card>
        <div className="timeline-top">
          <h3>
            {item.place}
            {item.slot_theme && <span className="slot-theme-chip">{item.slot_theme}</span>}
          </h3>
          <span>{item.meta}</span>
        </div>
        <p>{item.note}</p>
        <div className="timeline-move">
          <Navigation size={16} />
          {item.move}
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
      <Map size={28} />
      <p>조건에 맞는 코스를 준비하고 있어요.</p>
    </div>
  );
}

function Skeleton() {
  return <span className="skeleton-line" aria-hidden="true" />;
}

function ReasonModal({ item, onClose }) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true">
      <Card className="modal-card">
        <button className="modal-close" onClick={onClose} aria-label="닫기">
          <X size={18} />
        </button>
        <img src={item.image} alt={item.title} />
        <h2>{item.title}</h2>
        <p>{item.reason}</p>
        <Button full onClick={onClose}>
          확인
        </Button>
      </Card>
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

function BottomNavigation({ active, onChange }) {
  const items = [
    { key: 'home', label: '홈', icon: Home },
    { key: 'detail', label: '상세', icon: MapPin },
    { key: 'alternatives', label: '코스', icon: Compass },
    { key: 'course', label: '일정', icon: CalendarDays },
  ];

  return (
    <nav className="bottom-nav">
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
