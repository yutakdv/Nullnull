// AI 코스 탭 — 지역·코스 길이·동행·날짜만 고르면 널널한 일정을 만들어준다
import { useState } from 'react';
import {
  Bookmark,
  CalendarDays,
  ChevronRight,
  Clock3,
  Heart,
  Home,
  Leaf,
  Loader2,
  MapPin,
  Navigation,
  ShieldCheck,
  Sparkles,
  UsersRound,
} from 'lucide-react';
import SmartImage from '../components/SmartImage';
import { Button, Card } from '../components/common';
import { AiCourseCard } from '../components/cards';
import {
  AI_DURATIONS,
  AI_INDOOR,
  AI_PACE,
  AI_THEMES,
  AI_TIMESLOTS,
  AI_TRANSPORT,
  companionOptions,
  SEOUL_DISTRICTS,
} from '../constants';
import { todayInSeoul } from '../utils/datetime';

export default function AiCourseScreen({
  visitDate,
  maxVisitDate,
  companion,
  onCompanionChange,
  creating,
  apiReady,
  onCreate,
  results,
  myCourses,
  onOpenCourse,
  savedCourseIds = [],
  onToggleSaveCourse,
}) {
  const [district, setDistrict] = useState('종로구');
  const [duration, setDuration] = useState('half');
  const [date, setDate] = useState(visitDate);
  const [timeSlot, setTimeSlot] = useState('afternoon');
  const [themes, setThemes] = useState([]); // 관심 테마(다중, 빈 배열=전체)
  const [pace, setPace] = useState('여유');
  const [indoor, setIndoor] = useState('상관없음');
  const [transport, setTransport] = useState('walk'); // 이동 방식(도보|차량)
  const selected = AI_DURATIONS.find((d) => d.key === duration);

  const toggleTheme = (t) =>
    setThemes((prev) => (prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]));

  const submit = () =>
    onCreate({
      district,
      stops: selected?.stops ?? 3,
      companion,
      date,
      timeSlot,
      themes,
      pace,
      indoor,
      transport,
    });

  return (
    <section className="screen ai-course-screen">
      <div className="region-hero ai-hero">
        <span className="eyebrow">
          <Sparkles size={14} /> AI 코스 추천
        </span>
        <h1>조건만 고르면, 코스는 AI가.</h1>
        <p className="region-note">
          혼잡·날씨 데이터로 후보를 추리고, AI가 동선까지 고려해 여러 코스를 제안해요.
        </p>
      </div>

      <Card className="ai-form-card">
        <div className="ai-field">
          <span className="ai-field-label">
            <Clock3 size={16} />
            코스 길이
          </span>
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
          <span className="ai-field-label">
            <Navigation size={16} />
            이동 방식
          </span>
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
            <span className="ai-field-label">
              <MapPin size={16} />
              둘러볼 지역
            </span>
            <label className="district-select">
              <select
                value={district}
                onChange={(event) => setDistrict(event.target.value)}
                disabled={creating}
                aria-label="둘러볼 지역"
              >
                <option value="">서울 전체</option>
                {SEOUL_DISTRICTS.map((gu) => (
                  <option key={gu} value={gu}>
                    {gu}
                  </option>
                ))}
              </select>
              <ChevronRight size={16} className="district-caret" />
            </label>
          </div>
          <div className="ai-field">
            <span className="ai-field-label">
              <UsersRound size={16} />
              동행
            </span>
            <label className="district-select">
              <select
                value={companion}
                onChange={(event) => onCompanionChange(event.target.value)}
                disabled={creating}
                aria-label="동행 유형"
              >
                {companionOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              <ChevronRight size={16} className="district-caret" />
            </label>
          </div>
          <div className="ai-field">
            <span className="ai-field-label">
              <CalendarDays size={16} />
              날짜
            </span>
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
          <span className="ai-field-label">
            <Clock3 size={16} />
            시작 시간대
          </span>
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
          <span className="ai-field-label">
            <Heart size={16} />
            관심 테마 <small>(여러 개 선택 가능)</small>
          </span>
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
            <span className="ai-field-label">
              <Leaf size={16} />
              여행 페이스
            </span>
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
            <span className="ai-field-label">
              <Home size={16} />
              실내외
            </span>
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
          {creating ? (
            <>
              <Loader2 size={19} className="spin" />
              AI가 널널한 동선을 계산하는 중
            </>
          ) : (
            <>
              <Sparkles size={18} />
              {district || '서울'} {selected?.label} 코스 추천받기
            </>
          )}
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
              {results.source === 'llm' ? (
                <>
                  <Sparkles size={13} />
                  AI 추천
                </>
              ) : (
                <>
                  <ShieldCheck size={13} />
                  널널 알고리즘
                </>
              )}
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
              <button
                key={course.course_id}
                className="region-spot-card ai-recent"
                onClick={() => onOpenCourse(course.course_id)}
              >
                <span className="region-spot-main">
                  <SmartImage src={course.image_url} name={course.title} alt={course.title} />
                  <span className="region-spot-body">
                    <span className="region-spot-top">
                      <strong>{course.title}</strong>
                    </span>
                    <span className="region-spot-addr">
                      혼잡 회피 {Math.round(course.relief_pct ?? 0)}%
                    </span>
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
