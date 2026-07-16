// 목록/캐러셀에 쓰는 카드 계열 컴포넌트
import {
  ChevronRight,
  Clock3,
  History,
  MapPin,
  Navigation,
  Route,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import SmartImage from './SmartImage';
import { BookmarkToggle, Card, Metric } from './common';
import { CrowdBadge } from './crowd';
import { imageUrl } from '../utils/image';

// 홈 캐러셀의 세로형 관광지 카드
export function SpotCard({ spot, selected = false, saved = false, onToggleSave, onClick }) {
  const tag = spot.tags?.[0] ?? spot.category_name ?? '서울 관광지';

  return (
    <button
      className={`course-card discovery-card ${selected ? 'is-selected' : ''}`}
      onClick={onClick}
    >
      <span className="card-media">
        <SmartImage src={spot.image_url} name={spot.name} alt={spot.name} />
        {spot.distance_km != null && (
          <span className="card-distance">
            <Navigation size={12} />
            {spot.distance_km}km · 도보 {spot.walk_min}분
          </span>
        )}
        <BookmarkToggle saved={saved} onToggle={onToggleSave} labelBase="저장한 관광지" />
      </span>
      <div className="course-card-body">
        <h3>{spot.name}</h3>
        <div className="card-location">
          <span>{spot.addr ?? spot.region ?? '서울'}</span>
          <small>
            {spot.label} {Math.round(spot.risk)}%
          </small>
        </div>
        <p className="card-tags">#{tag} #서울나들이</p>
        <div className="mini-metrics">
          <span>추천 시간</span>
          <span>{spot.best_time_slot_label ?? '확인 중'}</span>
        </div>
      </div>
    </button>
  );
}

export function PopularCourseCard({ course, saved = false, onToggleSave, onClick }) {
  return (
    <button className="course-card discovery-card" onClick={onClick}>
      <span className="card-media">
        <SmartImage src={course.image_url} name={course.title} alt={course.title} />
        <BookmarkToggle saved={saved} onToggle={onToggleSave} labelBase="저장한 코스" />
      </span>
      <div className="course-card-body">
        <h3>{course.title}</h3>
        <div className="card-location">
          <span>{course.location ?? '서울'}</span>
          <small>혼잡 회피 {course.rate_pct}%</small>
        </div>
        <p className="card-tags">#{course.tag ?? '널널여행'} #서울여행</p>
        <div className="mini-metrics">
          <span>여유로운 추천</span>
          <span>{course.duration_text}</span>
        </div>
      </div>
    </button>
  );
}

// 최근 방문한 장소 — 관광지/코스 블럭(discovery-card)과 동일한 구조·이미지 비율
export function VisitedSpotCard({ spot, onClick }) {
  const note = spot.last_rating
    ? `★ ${spot.last_rating}.0 후기 남김`
    : (spot.last_perceived_label ?? '피드백 남김');

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
          <span>
            {spot.label} {Math.round(spot.risk)}%
          </span>
        </div>
      </div>
    </button>
  );
}

// 지역/저장 목록에 쓰는 가로형(리스트) 관광지 카드
export function RegionSpotCard({ spot, onClick, onRemove }) {
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
                <em>
                  <Clock3 size={13} />
                  추천 {spot.best_time_slot_label}
                </em>
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

// AI 추천 결과 카드 — 제목·컨셉·혼잡회피·동선 미리보기 + 마이페이지 저장 북마크
export function AiCourseCard({ course, saved = false, onToggleSave, onClick }) {
  const stops = (course.timeline ?? []).map((t) => t.place);
  return (
    <button className="ai-course-card" onClick={onClick}>
      <div className="ai-course-top">
        <strong>{course.title}</strong>
        <span className="ai-course-actions">
          <BookmarkToggle
            saved={saved}
            onToggle={onToggleSave}
            labelBase="저장한 코스"
            className="ai-course-bookmark"
            size={18}
          />
          <ChevronRight size={20} />
        </span>
      </div>
      {course.description && <p className="ai-course-desc">{course.description}</p>}
      <div className="ai-course-route">
        <Route size={15} />
        <span>{stops.join(' → ')}</span>
      </div>
      <div className="ai-course-meta">
        <em className="relief">
          <ShieldCheck size={13} />
          혼잡 회피 {Math.round(course.summary?.relief_pct ?? 0)}%
        </em>
        <em>
          <Clock3 size={13} />
          이동 {course.summary?.total_move_min ?? 0}분
        </em>
        <em>
          <MapPin size={13} />
          {stops.length}곳
        </em>
      </div>
    </button>
  );
}

export function AlternativeCard({ item, onReason, onSelect }) {
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
                <span
                  className="chip chip-rotation"
                  title="한 곳에 추천이 몰리지 않게 여러 장소를 번갈아 보여드려요"
                >
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

export function TimelineItem({ item, index, isLast }) {
  return (
    <div className={`timeline-item ${isLast ? 'is-last' : ''}`}>
      <div className="timeline-marker">{index + 1}</div>
      <Card>
        <div className="timeline-body">
          {item.image_url && (
            <img
              className="timeline-thumb"
              src={imageUrl(item.image_url, item.place)}
              alt={item.place}
            />
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
