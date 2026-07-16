// 코스 조건 입력 폼 — 날짜·기준 장소·동행·코스 스타일(테마 유지/자유여행)
import {
  ArrowRight,
  CalendarDays,
  Compass,
  Heart,
  Loader2,
  MapPin,
  Navigation,
  Shuffle,
  X,
} from 'lucide-react';
import { Button, Card, FilterControl } from './common';
import { companionOptions, slotThemeOptions, themes } from '../constants';
import { todayInSeoul } from '../utils/datetime';

const MIN_SLOTS = 2;
const MAX_SLOTS = 4;

export default function CourseFinder({
  selectedTheme,
  visitDate,
  maxVisitDate,
  selectedSpotId,
  homeSpots,
  courseMode,
  freeSlots,
  companion,
  courseCreating,
  homeLoading,
  onFind,
  onCreateFreeCourse,
  onCourseModeChange,
  onFreeSlotsChange,
  onCompanionChange,
  onVisitDateChange,
  onSpotChange,
  onThemeChange,
}) {
  const isFree = courseMode === 'free';
  const busy = homeLoading || courseCreating;
  const updateSlot = (index, value) => {
    onFreeSlotsChange(freeSlots.map((slot, i) => (i === index ? value : slot)));
  };
  const removeSlot = (index) => {
    if (freeSlots.length > MIN_SLOTS) onFreeSlotsChange(freeSlots.filter((_, i) => i !== index));
  };
  const addSlot = () => {
    if (freeSlots.length < MAX_SLOTS) onFreeSlotsChange([...freeSlots, slotThemeOptions[0]]);
  };

  return (
    <Card className="search-card course-finder">
      <div className="finder-heading">
        <span className="eyebrow">테마별 추천 코스</span>
        <h1>오늘의 여유로운 코스를 찾아볼까요?</h1>
      </div>
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
        <FilterControl icon={Navigation} label="기준 장소">
          <select
            value={selectedSpotId ?? ''}
            onChange={(event) => onSpotChange(Number(event.target.value))}
            disabled={homeLoading || !homeSpots.length}
          >
            {homeSpots.map((spot) => (
              <option key={spot.spot_id} value={spot.spot_id}>
                {spot.name}
              </option>
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
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
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
            테마 유지 코스<small>같은 테마의 한적한 대안</small>
          </span>
        </button>
        <button
          className={`mode-chip ${isFree ? 'is-active' : ''}`}
          onClick={() => onCourseModeChange('free')}
          disabled={busy}
        >
          <Shuffle size={17} />
          <span>
            자유여행 코스<small>카테고리 섞어 일정 만들기</small>
          </span>
        </button>
      </div>
      {isFree ? (
        <div className="slot-builder">
          {freeSlots.map((slot, index) => (
            <div className="slot-item" key={`${slot}-${index}`}>
              <span className="slot-no">{index + 1}</span>
              <select
                value={slot}
                onChange={(event) => updateSlot(index, event.target.value)}
                disabled={busy}
                aria-label={`${index + 1}번째 카테고리`}
              >
                {slotThemeOptions.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
              {freeSlots.length > MIN_SLOTS && (
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
          {freeSlots.length < MAX_SLOTS && (
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
        {busy ? (
          <>
            <Loader2 size={19} className="spin" />
            {courseCreating ? '자유여행 코스를 만드는 중' : '조건에 맞는 장소를 찾는 중'}
          </>
        ) : (
          <>
            {isFree ? '자유여행 코스 만들기' : '이 조건으로 추천 코스 찾기'}
            <ArrowRight size={19} />
          </>
        )}
      </Button>
    </Card>
  );
}
