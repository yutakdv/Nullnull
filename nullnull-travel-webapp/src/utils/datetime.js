// 서울(Asia/Seoul) 기준 날짜·시간대 계산 유틸
export function todayInSeoul() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' });
}

// 현재 서울 시각(0~23) — 실시간 시간 기준 시간대 판정용(BE current_time_slot과 동일 경계)
export function seoulHour() {
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    hour12: false,
  }).formatToParts(new Date());
  const hour = Number(parts.find((part) => part.type === 'hour')?.value);
  return hour === 24 ? 0 : hour; // 자정을 '24'로 주는 환경 방어
}

export function currentSlotInSeoul() {
  const hour = seoulHour();
  if (hour < 12) return 'morning';
  if (hour < 17) return 'afternoon';
  return 'evening';
}

// 널널도 헤드라인 기본 시간대 — 당일이면 '지금'(실시간), 그 외 날짜는 '오후'
export function defaultSlotFor(date) {
  return date === todayInSeoul() ? currentSlotInSeoul() : 'afternoon';
}

export function dateAfter(date, days) {
  const target = new Date(`${date}T00:00:00Z`);
  target.setUTCDate(target.getUTCDate() + days);
  return target.toISOString().slice(0, 10);
}
