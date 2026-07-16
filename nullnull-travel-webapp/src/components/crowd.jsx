// 혼잡도(널널도) 표시 계열 컴포넌트 — 배지·범례·시간대 카드·히트맵·캘린더
import { Clock3 } from 'lucide-react';
import { crowdLevels, HEAT_SLOT_ROWS } from '../constants';

export function CrowdBadge({ level, size = 'normal' }) {
  const item = crowdLevels[level - 1];
  return (
    <span className={`crowd-badge ${item.className} ${size === 'large' ? 'is-large' : ''}`}>
      <span>{item.value}</span>
      {item.label}
    </span>
  );
}

export function CrowdLegend() {
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

export function TimeCard({ label, value, note, active = false, onClick }) {
  return (
    <button className={`card time-card ${active ? 'is-active' : ''}`} onClick={onClick}>
      <Clock3 size={19} />
      <small>{label}</small>
      <strong>{value}</strong>
      <span>{note}</span>
    </button>
  );
}

// 요일 × 시간대(오전/오후/저녁) 혼잡도를 색으로 한눈에 보는 히트맵(그래프 대체)
export function WeekdayHeat({ data }) {
  return (
    <div className="weekday-heat-wrap">
      <div className="weekday-heat" role="table" aria-label="요일별·시간대별 혼잡도">
        <div className="weekday-heat-row weekday-heat-head" role="row">
          <span className="wh-slot" aria-hidden="true" />
          {data.map((row) => (
            <span key={row.day} className="wh-day" role="columnheader">
              {row.day}
            </span>
          ))}
        </div>
        {HEAT_SLOT_ROWS.map((slot) => (
          <div className="weekday-heat-row" role="row" key={slot.key}>
            <span className="wh-slot" role="rowheader">
              {slot.label}
            </span>
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
        <i className="heat-1" />
        <i className="heat-2" />
        <i className="heat-3" />
        <i className="heat-4" />
        <i className="heat-5" />
        <span>혼잡</span>
      </div>
    </div>
  );
}

export function CalendarHeat({ days, selectedDate, onPick }) {
  // 첫 주 시작 요일에 맞춰 빈 칸을 채워 실제 달력 형태로 그린다(월요일 시작)
  const firstWeekday = new Date(`${days[0].date}T00:00:00`).getDay(); // 0=일
  const leadingBlanks = (firstWeekday + 6) % 7;
  return (
    <div className="calendar-heat" role="grid" aria-label="30일 널널도 캘린더">
      {['월', '화', '수', '목', '금', '토', '일'].map((day) => (
        <span className="heat-head" key={day}>
          {day}
        </span>
      ))}
      {Array.from({ length: leadingBlanks }, (_, i) => (
        <span className="heat-cell is-blank" key={`blank-${i}`} />
      ))}
      {days.map((day) => (
        <button
          key={day.date}
          className={[
            'heat-cell',
            `heat-${day.level}`,
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
