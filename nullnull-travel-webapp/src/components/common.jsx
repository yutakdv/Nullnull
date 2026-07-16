// 화면 전반에서 쓰는 범용 UI 프리미티브
import { Bookmark, Check, Map as MapIcon, Star } from 'lucide-react';

export function Button({ children, full = false, onClick, disabled = false }) {
  return (
    <button className={`button ${full ? 'button-full' : ''}`} onClick={onClick} disabled={disabled}>
      {children}
    </button>
  );
}

export function Card({ children, className = '' }) {
  return <div className={`card ${className}`}>{children}</div>;
}

export function Tag({ children, icon: Icon }) {
  return (
    <span className="tag">
      {Icon && <Icon size={15} />}
      {children}
    </span>
  );
}

export function IconButton({ children, label, onClick, className = '' }) {
  return (
    <button
      className={`icon-button ${className}`}
      onClick={onClick}
      aria-label={label}
      title={label}
    >
      {children}
    </button>
  );
}

export function SectionHeader({ title, action, compact = false }) {
  return (
    <div className={`section-header ${compact ? 'compact' : ''}`}>
      <h2>{title}</h2>
      {action && <button>{action}</button>}
    </div>
  );
}

export function EmptyState() {
  return (
    <div className="empty-state">
      <MapIcon size={28} />
      <p>조건에 맞는 코스를 준비하고 있어요.</p>
    </div>
  );
}

export function Toast({ message }) {
  return (
    <div className="toast">
      <Check size={18} />
      {message}
    </div>
  );
}

export function Metric({ label, value }) {
  return (
    <span>
      <small>{label}</small>
      <strong>{value}</strong>
    </span>
  );
}

export function SummaryMetric({ label, value }) {
  return (
    <div>
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

export function ProofBar({ label, value }) {
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

export function FilterControl({ icon: Icon, label, children }) {
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

export function StarRating({ rating, onChange, readonly = false, compact = false }) {
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

// 카드 우상단 북마크 토글 — 카드 열기(onClick)와 분리해 북마크만 토글한다.
// (키보드 접근을 위해 role=button + Enter/Space 처리)
export function BookmarkToggle({
  saved,
  onToggle,
  labelBase,
  className = 'card-bookmark',
  size = 20,
}) {
  const handleBookmark = (event) => {
    event.stopPropagation();
    onToggle?.();
  };
  return (
    <span
      className={`${className} ${saved ? 'is-saved' : ''}`}
      role="button"
      tabIndex={0}
      aria-label={saved ? `${labelBase}에서 빼기` : `${labelBase}에 담기`}
      aria-pressed={saved}
      onClick={handleBookmark}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          handleBookmark(event);
        }
      }}
    >
      <Bookmark size={size} fill={saved ? 'currentColor' : 'none'} />
    </span>
  );
}
