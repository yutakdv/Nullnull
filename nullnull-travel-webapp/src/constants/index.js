import { Camera, Coffee, History, Sparkles, Trees } from 'lucide-react';

// 홈 히어로 배경 — 광화문·경복궁 타임랩스 GIF(Mixkit 무료 라이선스, 출처표기 불요).
// 로드 전/실패 시에는 HeroScene(CSS 숲 풍경)이 뒤에서 배경을 채운다.
export const HERO_GIF = '/assets/hero-gyeongbokgung.gif';

export const themes = [
  { label: '전체', icon: Sparkles },
  { label: '자연', icon: Trees },
  { label: '역사', icon: History },
  { label: '미식', icon: Coffee },
  { label: '포토스팟', icon: Camera },
];

// 자유여행 슬롯 카테고리 — 백엔드 CourseRecommendRequest.theme_sequence와 동일 어휘
export const slotThemeOptions = ['여행지', '자연', '역사', '미식', '포토스팟'];
export const defaultFreeSlots = ['여행지', '미식', '포토스팟'];

export const crowdLevels = [
  { label: '매우 널널', className: 'level-1', value: 1 },
  { label: '널널', className: 'level-2', value: 2 },
  { label: '보통', className: 'level-3', value: 3 },
  { label: '붐빔', className: 'level-4', value: 4 },
  { label: '매우 붐빔', className: 'level-5', value: 5 },
];

export const reviewTags = [
  '한산했어요',
  '사진보다 좋아요',
  '동선이 편해요',
  '재방문 의향',
  '주차 쉬움',
];

// 코스 시간대별 시작 시각 — 타임라인 도착 시각 계산용(BE REALTIME_SLOT_HOUR와 동일 기준)
export const SLOT_START_HOUR = { morning: 10, afternoon: 14, evening: 19 };

// 서울 25개 자치구 — 검색 탭 지역 선택용
export const SEOUL_DISTRICTS = [
  '강남구',
  '강동구',
  '강북구',
  '강서구',
  '관악구',
  '광진구',
  '구로구',
  '금천구',
  '노원구',
  '도봉구',
  '동대문구',
  '동작구',
  '마포구',
  '서대문구',
  '서초구',
  '성동구',
  '성북구',
  '송파구',
  '양천구',
  '영등포구',
  '용산구',
  '은평구',
  '종로구',
  '중구',
  '중랑구',
];

// 검색 탭 카테고리 칩 — '볼거리' 기본, 백엔드 cat1 그룹과 1:1
export const SEARCH_CATEGORIES = ['볼거리', '문화·역사', '자연·공원', '미식', '쇼핑'];

// ── 위치 기반 근처 관광지(MVP 목업) ───────────────────────────────
// 실제 GPS 대신 '경복궁 근처(종로)'를 가상 현재 위치로 둔다.
export const MOCK_LOCATION_LABEL = '서울 종로구 인근 (예시 위치)'; // 괄호 안은 줄바꿈 없이 한 덩어리로

export const companionOptions = [
  { value: '', label: '선택 안 함' },
  { value: 'solo', label: '혼자' },
  { value: 'couple', label: '둘이서' },
  { value: 'family', label: '가족과' },
];

// 동행을 고르면 추천을 '거르는' 게 아니라 그 동행에 맞는 곳을 먼저 보여주는 우선정렬 안내
export const companionHints = {
  solo: '혼자 여행에 맞춰 한적하고 덜 알려진 곳을 먼저 보여드려요',
  couple: '둘이서 여행에 맞춰 포토스팟·자연·뷰가 좋은 곳을 먼저 보여드려요',
  family: '가족 여행에 맞춰 실내·이동이 편한 곳을 먼저 보여드려요',
};

// AI 코스 탭 — 지역·코스 길이·동행·날짜만 고르면 널널한 일정을 만들어준다
export const AI_DURATIONS = [
  { key: '3h', label: '3시간', desc: '가볍게 두 곳', stops: 2 },
  { key: 'half', label: '반나절', desc: '여유롭게 세 곳', stops: 3 },
  { key: 'day', label: '하루', desc: '느긋하게 네 곳', stops: 4 },
];
export const AI_TIMESLOTS = [
  { key: 'morning', label: '오전' },
  { key: 'afternoon', label: '오후' },
  { key: 'evening', label: '저녁' },
];
export const AI_THEMES = ['역사', '자연', '미식', '포토스팟', '쇼핑', '힐링'];
export const AI_PACE = ['여유', '보통'];
export const AI_INDOOR = [
  { key: '상관없음', label: '상관없음' },
  { key: '실내', label: '실내 위주' },
  { key: '실외', label: '실외 위주' },
];
// 이동 방식 — 도보면 도보권 후보로 좁혀 걷기 좋은 동선, 차량이면 넓은 반경 허용
export const AI_TRANSPORT = [
  { key: 'walk', label: '도보', desc: '걸어서 이어지는 동선' },
  { key: 'car', label: '차량', desc: '차로 넓게 둘러보기' },
];

// 요일 × 시간대 히트맵의 행 정의(오전/오후/저녁)
export const HEAT_SLOT_ROWS = [
  { key: 'morning', label: '오전' },
  { key: 'afternoon', label: '오후' },
  { key: 'evening', label: '저녁' },
];

// AlternativeScore(9-2) 항목별 표시 정의 — "추천 근거 수치화"를 그대로 보여준다
export const BREAKDOWN_ROWS = [
  { key: 'theme_similarity', label: '테마 유사도' },
  { key: 'relief', label: '혼잡 완화 효과' },
  { key: 'mobility', label: '이동 편의성' },
  { key: 'hidden', label: '덜 알려진 곳' },
  { key: 'weather', label: '날씨 적합성' },
];

// 하단 탭이 아닌 하위 화면(관광지 상세·코스 결과)에서 강조할 탭 매핑
export const NAV_ACTIVE_KEY = {
  detail: 'region', // 관광지 상세는 '검색'에서 진입
  alternatives: 'course-ai', // 대안 보기는 코스 생성 계열
  course: 'course-ai', // 코스 결과는 'AI 코스' 계열
};
