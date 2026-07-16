// 앱 루트 — 화면 전환·서버 데이터·로컬 보관함 상태를 오케스트레이션한다.
// (표시는 screens/, 데이터 호출은 api/endpoints.js가 담당)
import { useEffect, useMemo, useState } from 'react';
import {
  aiRecommendCourse,
  checkHealth,
  createCourse,
  fetchCourse,
  fetchCourseAlternatives,
  fetchHomeSpots,
  fetchImpactSummary,
  fetchPopularCourses,
  fetchRegionSpots,
  fetchSpotContext,
  fetchVisitedSpots,
  postFeedback,
  postReview,
  recommendCourse,
  rerollCourse as rerollCourseApi,
  shareCourse as shareCourseApi,
  swapCourseSpot,
} from './api/endpoints';
import { Toast } from './components/common';
import { Header, BottomNavigation, ConnectionBanner } from './components/layout';
import ReasonModal from './components/ReasonModal';
import HomeScreen from './screens/HomeScreen';
import RegionScreen from './screens/RegionScreen';
import AiCourseScreen from './screens/AiCourseScreen';
import DetailScreen from './screens/DetailScreen';
import MyPageScreen from './screens/MyPageScreen';
import AlternativesScreen from './screens/AlternativesScreen';
import CourseScreen from './screens/CourseScreen';
import AdminScreen from './screens/AdminScreen';
import { defaultFreeSlots, NAV_ACTIVE_KEY } from './constants';
import { dateAfter, defaultSlotFor, todayInSeoul } from './utils/datetime';
import { courseMemo, savableCourse } from './utils/mappers';
import {
  loadActiveCourse,
  loadMyCourses,
  loadSavedCourses,
  loadSavedSpots,
  writeJson,
  STORAGE_KEYS,
} from './services/storage';
import './styles.css';

const TOAST_DURATION_MS = 2200;

export default function App() {
  const [screen, setScreen] = useState('home');
  const [selectedTheme, setSelectedTheme] = useState('전체');
  const [visitDate, setVisitDate] = useState(todayInSeoul);
  const [selectedSpotId, setSelectedSpotId] = useState(null);
  const [homeLoading, setHomeLoading] = useState(false);
  const [modal, setModal] = useState(null);
  const [toast, setToast] = useState('');
  const [savedSpots, setSavedSpots] = useState(loadSavedSpots); // 마이페이지 저장한 관광지
  const [selectedDistrict, setSelectedDistrict] = useState(''); // 검색 탭 구 선택('' = 전체)
  const [selectedCategory, setSelectedCategory] = useState('볼거리'); // 검색 탭 카테고리
  const [regionSpots, setRegionSpots] = useState([]); // 지역 탭 널널 추천 목록
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
  const [courseMode, setCourseMode] = useState('theme'); // theme(테마 유지) | free(자유여행)
  const [freeSlots, setFreeSlots] = useState(defaultFreeSlots);
  const [companion, setCompanion] = useState(''); // F1 동행 유형('' = 선택 안 함)
  const [courseCreating, setCourseCreating] = useState(false);
  const [courseRerolling, setCourseRerolling] = useState(false);
  const [spot, setSpot] = useState(null);
  const [spotDetail, setSpotDetail] = useState(null);
  const [slotViews, setSlotViews] = useState(null); // {morning, afternoon, evening}
  const [activeSlot, setActiveSlot] = useState(() => defaultSlotFor(todayInSeoul()));
  const [calendar, setCalendar] = useState(null); // 30일 널널 캘린더
  const [congestionChart, setCongestionChart] = useState(null);
  const [alternativeView, setAlternativeView] = useState(null);
  const [courseView, setCourseView] = useState(null);
  const [aiResults, setAiResults] = useState(null); // AI 코스 추천 결과 {source, courses}
  const [courseAlternatives, setCourseAlternatives] = useState(null);
  const [myCourses, setMyCourses] = useState(loadMyCourses);
  const [savedCourses, setSavedCourses] = useState(loadSavedCourses); // 북마크한 공유 코스
  const [activeCourse, setActiveCourse] = useState(loadActiveCourse); // 여행하기로 선택한 코스
  const [courseSharing, setCourseSharing] = useState(false); // 코스 공개 진행 표시
  const [adminMode, setAdminMode] = useState(window.location.hash === '#admin');
  const [booted, setBooted] = useState(false); // 첫 로드 시도 완료 여부
  const [navLoading, setNavLoading] = useState(false); // 화면 전환/조회 진행 표시
  const [heroScrolled, setHeroScrolled] = useState(false); // 홈 히어로를 벗어났는지(네비 노출용)

  // 홈 탭에서는 히어로 전체화면을 벗어나 스크롤했을 때만 하단 네비게이션을 노출한다.
  useEffect(() => {
    const onScroll = () => setHeroScrolled(window.scrollY > 60);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const congestionView = slotViews?.[activeSlot] ?? null;
  const currentSpotId = spot?.spot_id ?? null;
  const maxVisitDate = useMemo(() => dateAfter(todayInSeoul(), 30), []);

  const showToast = (message) => {
    setToast(message);
    window.setTimeout(() => setToast(''), TOAST_DURATION_MS);
  };

  // ── 로컬 보관함(저장한 관광지·코스, 여행 중 코스) ────────────────
  const isSpotSaved = (id) => savedSpots.some((s) => s.spot_id === id);

  // 관광지 저장/해제 — 마이페이지 '저장한 관광지'에 반영(localStorage 유지)
  const toggleSaveSpot = (target) => {
    if (!target?.spot_id) return;
    setSavedSpots((current) => {
      const exists = current.some((s) => s.spot_id === target.spot_id);
      const next = exists
        ? current.filter((s) => s.spot_id !== target.spot_id)
        : [
            {
              spot_id: target.spot_id,
              name: target.name,
              image_url: target.image_url ?? null,
              addr: target.addr ?? target.region ?? '서울',
              saved_at: new Date().toISOString(),
            },
            ...current,
          ];
      writeJson(STORAGE_KEYS.savedSpots, next);
      return next;
    });
    showToast(
      isSpotSaved(target.spot_id) ? '저장한 관광지에서 뺐어요' : '저장한 관광지에 담았어요',
    );
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
      writeJson(STORAGE_KEYS.savedCourses, next);
      return next;
    });
    showToast(
      isCourseSaved(target.course_id) ? '저장한 코스에서 뺐어요' : '저장한 코스에 담았어요',
    );
  };

  // 여행하기(코스 사용) — 여행 중인 코스로 지정하고 저장한 코스에도 담는다
  const startTravel = (course) => {
    if (!course?.course_id) return;
    const memo = savableCourse(course);
    setActiveCourse(memo);
    writeJson(STORAGE_KEYS.activeCourse, memo);
    setSavedCourses((current) => {
      if (current.some((c) => c.course_id === course.course_id)) return current;
      const next = [memo, ...current];
      writeJson(STORAGE_KEYS.savedCourses, next);
      return next;
    });
    showToast('여행을 시작했어요 — 마이페이지에서 이 코스를 볼 수 있어요');
  };

  const endTravel = () => {
    setActiveCourse(null);
    localStorage.removeItem(STORAGE_KEYS.activeCourse);
    showToast('여행을 마쳤어요 — 코스는 저장한 코스에 남아 있어요');
  };

  // 생성한 코스를 보관함에 저장하고 공유 가능한 해시(#course/id)를 단다
  const rememberCourse = (course) => {
    if (!course?.course_id) return;
    setMyCourses((current) => {
      // 같은 제목(=같은 조건에서 reroll/swap한 계열)은 최신 것만 보관 —
      // '다른 코스 추천'을 연타해도 보관함이 같은 제목 카드로 도배되지 않게
      const next = [
        courseMemo(course),
        ...current.filter((c) => c.course_id !== course.course_id && c.title !== course.title),
      ].slice(0, 10);
      writeJson(STORAGE_KEYS.myCourses, next);
      return next;
    });
    window.history.replaceState(null, '', `#course/${course.course_id}`);
  };

  // ── 서버 데이터 갱신 ─────────────────────────────────────────────
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
    setSelectedSpotId((current) =>
      response.items.some((item) => item.spot_id === current)
        ? current
        : (response.items[0]?.spot_id ?? null),
    );
  };

  const refreshHomeSpots = async (date, theme) => {
    setHomeLoading(true);
    try {
      const response = await fetchHomeSpots({ date, theme });
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
      const response = await fetchVisitedSpots();
      setVisitedSpots(response.items);
    } catch (error) {
      console.warn(error);
    }
  };

  // 홈 분산 임팩트 카운터(기획서 5장) — 코스 생성·피드백 직후 갱신
  const refreshImpact = async () => {
    try {
      setImpact(await fetchImpactSummary());
    } catch (error) {
      console.warn(error);
    }
  };

  // 검색 탭 — tourAPI 관광지 카탈로그를 구·카테고리로 페이지 단위 조회(무한스크롤)
  const loadRegionSpots = async (district, category, page = 1) => {
    setRegionLoading(true);
    try {
      const res = await fetchRegionSpots({ district, category, page });
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
    () =>
      ({
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

  // ── 화면 전환·조회 ──────────────────────────────────────────────
  const handleShare = async () => {
    const base = `${window.location.origin}${window.location.pathname}`;
    const url =
      screen === 'course' && courseView?.course_id
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
      setCourseView(await fetchCourse(courseId));
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
      // 상세 진입 시 헤드라인 시간대를 '지금'(당일) 기준으로 맞춘다 — 실시간 시간 기준
      setActiveSlot(defaultSlotFor(visitDate));
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
      await checkHealth();
      const [popularList, homeSpotResponse, visitedResponse, impactResponse] = await Promise.all([
        fetchPopularCourses(3),
        fetchHomeSpots({ date: visitDate, theme: selectedTheme }),
        fetchVisitedSpots().catch(() => ({ items: [] })),
        fetchImpactSummary().catch(() => null),
      ]);

      const firstSpot = homeSpotResponse.items[0] ?? null;
      setApiReady(true);
      applyHomeResponse(homeSpotResponse);
      setHomeCourses(popularList);
      setVisitedSpots(visitedResponse.items);
      setImpact(impactResponse);
      setSpot(firstSpot);

      if (firstSpot) {
        applySpotContext(
          await fetchSpotContext(firstSpot.spot_id, visitDate, selectedTheme, companion),
        );
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // '일정' 탭 직접 진입 시 인기 코스 1위의 상세를 불러온다(빈 화면 방지)
  useEffect(() => {
    if (screen === 'course' && !courseView && apiReady) {
      const firstCourseId = homeCourses[0]?.course_id;
      if (firstCourseId) {
        fetchCourse(firstCourseId)
          .then(setCourseView)
          .catch(() => {});
      }
    }
  }, [screen, apiReady, courseView, homeCourses]);

  // 선택한 날짜·장소·테마로 대안을 다시 조회(노출 로그 F8 기록)
  const findAlternatives = async () => {
    const originSpotId = selectedSpotId ?? spot?.spot_id;
    if (!apiReady || !originSpotId) {
      showToast('먼저 방문할 장소를 선택해주세요');
      return;
    }
    setNavLoading(true);
    try {
      applySpotContext(
        await fetchSpotContext(originSpotId, visitDate, selectedTheme, companion, true),
      );
      setScreen('alternatives');
    } catch (error) {
      console.warn(error);
      showToast('대안 코스를 불러오지 못했어요');
    } finally {
      setNavLoading(false);
    }
  };

  // '상세'·'코스' 탭 직접 진입 시에도 빈 화면이 나오지 않게 첫 장소 기준으로 채운다
  useEffect(() => {
    if (screen === 'detail' && !spotDetail && apiReady && homeSpots[0]) {
      openSpot(homeSpots[0].spot_id);
    }
    if (
      screen === 'alternatives' &&
      !alternativeView &&
      apiReady &&
      (selectedSpotId || homeSpots[0])
    ) {
      findAlternatives();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  // ── 코스 생성·조작 ──────────────────────────────────────────────
  const createCourseFromAlternatives = async () => {
    const alternativesFromApi = alternativeView?.alternatives ?? [];
    if (!apiReady || !alternativesFromApi.length) {
      setScreen('course');
      return;
    }

    try {
      const course = await createCourse({
        origin_spot_id: alternativeView.origin.spot_id,
        spot_ids: alternativesFromApi.map((item) => item.spot_id).slice(0, 4),
        date: alternativeView.origin.date,
        time_slot: alternativeView.origin.time_slot,
        companion: companion || null,
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
      const course = await recommendCourse({
        origin_spot_id: originSpotId,
        date: visitDate,
        theme_sequence: freeSlots,
        companion: companion || null,
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
      const res = await aiRecommendCourse({
        district: cond.district || null,
        stops: cond.stops,
        companion: cond.companion || null,
        date: cond.date,
        time_slot: cond.timeSlot,
        themes: cond.themes,
        pace: cond.pace,
        indoor_pref: cond.indoor,
        transport: cond.transport || null,
      });
      setAiResults(res); // { source, courses: [CourseDetail] }
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
      const course = await swapCourseSpot(courseView.course_id, orderNo, newSpotId);
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
      const course = await rerollCourseApi(courseView.course_id);
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

  // 코스 공개(F9) — 홈 '인기 널널 코스'에 노출시키고 목록을 갱신한다
  const shareCourse = async () => {
    if (!courseView?.course_id) return;
    setCourseSharing(true);
    try {
      const shared = await shareCourseApi(courseView.course_id);
      setCourseView(shared);
      setHomeCourses(await fetchPopularCourses(6));
      showToast('코스가 공개됐어요 — 홈 인기 코스에 노출돼요');
    } catch (error) {
      console.warn(error);
      showToast('코스 공개 중 문제가 생겼어요');
    } finally {
      setCourseSharing(false);
    }
  };

  // 코스가 바뀔 때마다 슬롯별 교체 후보를 불러온다(노출 로그 F8 기록)
  useEffect(() => {
    if (!apiReady || !courseView?.course_id) {
      setCourseAlternatives(null);
      return undefined;
    }
    let ignore = false;
    fetchCourseAlternatives(courseView.course_id)
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

  // ── 피드백·후기 ─────────────────────────────────────────────────
  const submitFeedback = async (perceived) => {
    if (!apiReady || !currentSpotId) return;
    await postFeedback({
      spot_id: courseView?.timeline?.[0]?.spot_id ?? currentSpotId,
      course_id: courseView?.course_id ?? null,
      perceived,
    });
    refreshVisitedSpots();
    refreshImpact();
  };

  const submitReview = async ({ rating, tags, text }) => {
    if (!apiReady || (!courseView && !currentSpotId)) return;
    await postReview({
      spot_id: courseView ? null : currentSpotId,
      course_id: courseView?.course_id ?? null,
      nickname: '익명',
      rating,
      tags,
      text,
    });
    refreshVisitedSpots();
    if (courseView?.course_id) {
      setCourseView(await fetchCourse(courseView.course_id));
    }
  };

  if (adminMode) {
    return (
      <main className="app-shell">
        <div className="app-frame">
          <AdminScreen
            onExit={() => {
              window.location.hash = '';
            }}
          />
        </div>
        {toast && <Toast message={toast} />}
      </main>
    );
  }

  return (
    <main className="app-shell">
      {navLoading && <div className="top-progress" aria-hidden="true" />}
      <div className="app-frame">
        <Header
          title={screenTitle}
          screen={screen}
          setScreen={changeScreen}
          onShare={handleShare}
        />

        {booted && !apiReady && <ConnectionBanner onRetry={bootstrap} loading={navLoading} />}

        {screen === 'home' && (
          <HomeScreen
            selectedSpotId={selectedSpotId}
            homeSpots={homeSpots}
            homeSpotTotal={homeSpotTotal}
            homeCourses={homeCourses}
            visitedSpots={visitedSpots}
            myCourses={myCourses}
            impact={impact}
            apiReady={apiReady}
            onOpenSpot={openSpot}
            onOpenCourse={openCourse}
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
            maxVisitDate={maxVisitDate}
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
            maxVisitDate={maxVisitDate}
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
