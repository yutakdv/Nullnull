// 앱 프레임 공통 레이아웃 — 상단 바·하단 탭·연결 실패 배너
import {
  Home,
  Leaf,
  Loader2,
  LocateFixed,
  RefreshCcw,
  Route,
  Search,
  Share2,
  UserRound,
} from 'lucide-react';
import { IconButton } from './common';

export function Header({ title, screen, setScreen, onShare }) {
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

const NAV_ITEMS = [
  { key: 'home', label: '홈', icon: Home },
  { key: 'region', label: '검색', icon: Search },
  { key: 'course-ai', label: 'AI 코스', icon: Route },
  { key: 'mypage', label: '마이페이지', icon: UserRound },
];

export function BottomNavigation({ active, onChange, hidden = false }) {
  return (
    <nav className={`bottom-nav${hidden ? ' is-hidden' : ''}`} aria-hidden={hidden}>
      {NAV_ITEMS.map(({ key, label, icon: Icon }) => (
        <button
          key={key}
          className={active === key ? 'is-active' : ''}
          onClick={() => onChange(key)}
        >
          <Icon size={20} />
          <span>{label}</span>
        </button>
      ))}
    </nav>
  );
}

export function ConnectionBanner({ onRetry, loading }) {
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
