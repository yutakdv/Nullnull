import { useEffect, useMemo, useState } from 'react';
import { Bookmark, Clock3, Compass, Leaf, Map as MapIcon, Shuffle, Sparkles } from 'lucide-react';
import HeroScene from '../components/HeroScene';
import SpotSearch from '../components/SpotSearch';
import { Card, SectionHeader, Tag, EmptyState } from '../components/common';
import { PopularCourseCard, SpotCard, VisitedSpotCard } from '../components/cards';
import { HERO_GIF, MOCK_LOCATION_LABEL } from '../constants';
import { imageUrl } from '../utils/image';
import { withMockDistance } from '../utils/mappers';

// 히어로 확장/축소는 스크롤마다 상태가 바뀌므로, 홈 화면 전체가 아니라
// 히어로 패널만 다시 그리도록 별도 컴포넌트로 격리한다.
function HomeHero({ apiReady, onOpenSpot }) {
  const [heroCollapse, setHeroCollapse] = useState(0);

  useEffect(() => {
    const updateHeroCollapse = () => {
      const collapsedHeight = Math.max(460, Math.min(window.innerHeight * 0.67, 560));
      const expandedHeight = Math.max(collapsedHeight, window.innerHeight - 48);
      const progress = Math.min(window.scrollY / 180, 1);
      setHeroCollapse(Math.round((expandedHeight - collapsedHeight) * progress));
    };
    updateHeroCollapse();
    window.addEventListener('scroll', updateHeroCollapse, { passive: true });
    window.addEventListener('resize', updateHeroCollapse);
    return () => {
      window.removeEventListener('scroll', updateHeroCollapse);
      window.removeEventListener('resize', updateHeroCollapse);
    };
  }, []);

  return (
    <div className="hero-panel" style={{ '--hero-collapse': `${heroCollapse}px` }}>
      <HeroScene />
      <img className="hero-media" src={HERO_GIF} alt="" aria-hidden="true" />
      <div className="hero-overlay" />
      <div className="hero-content">
        <Tag icon={Sparkles}>
          {apiReady ? '실측 혼잡 데이터 기반 추천' : '데이터를 불러오는 중'}
        </Tag>
        <p className="hero-tagline">붐비는 곳 말고, 널널한 여행 — Null crowd, Full trip.</p>
        <h1>오늘은 어디로 떠나볼까요?</h1>
        <div className="hero-pillars">
          <span>
            <Clock3 size={14} />
            시간 분산
          </span>
          <span>
            <Compass size={14} />
            공간 분산
          </span>
          <span>
            <Shuffle size={14} />
            추천 분산
          </span>
        </div>
        <SpotSearch onPick={onOpenSpot} disabled={!apiReady} />
      </div>
    </div>
  );
}

function HomeStats({ impact, homeSpotTotal, featuredSpot }) {
  return (
    <div className="stats-grid">
      <StatCard
        label={`이번 주 덜 붐비게 다녀온 비율${impact?.includes_seed ? ' · 예시 포함' : ''}`}
        value={impact ? `${impact.avoid_rate_avg_pct}%` : '-'}
        icon={Leaf}
        tone="green"
      />
      <StatCard
        label={`이번 주 새로 발견한 덜 알려진 곳${impact?.includes_seed ? ' · 예시 포함' : ''}`}
        value={impact ? `${impact.hidden_pick_count.toLocaleString()}곳` : '-'}
        icon={Sparkles}
        tone="blue"
      />
      <StatCard
        label="골라 담을 수 있는 서울 관광지"
        value={homeSpotTotal ? homeSpotTotal.toLocaleString() : '-'}
        icon={Compass}
        tone="green"
      />
      <StatCard
        label={featuredSpot ? `${featuredSpot.name} 지금 얼마나 붐벼요` : '지금 얼마나 붐벼요'}
        value={featuredSpot ? `${Math.round(featuredSpot.risk)}%` : '-'}
        icon={MapIcon}
        tone="blue"
      />
    </div>
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

// 홈 '내 코스' 캐러셀 카드 — localStorage 보관함(courseMemo) 항목 표시
function MyCourseCard({ course, onClick }) {
  return (
    <button className="course-card discovery-card my-course-card" onClick={onClick}>
      <span className="card-media">
        <img src={imageUrl(course.image_url, course.title)} alt={course.title} />
        <Bookmark className="card-bookmark" size={24} aria-hidden="true" />
      </span>
      <div className="course-card-body">
        <h3>{course.title}</h3>
        <div className="card-location">
          <span>{course.location ?? '서울'}</span>
          <small>저장한 코스</small>
        </div>
        <p className="card-tags">#{course.tag ?? '널널여행'} #나만의코스</p>
        <div className="mini-metrics">
          <span>혼잡 회피 {Math.round(course.relief_pct ?? 0)}%</span>
          <span>{course.duration_text ?? '추천 코스'}</span>
        </div>
      </div>
    </button>
  );
}

export default function HomeScreen({
  selectedSpotId,
  homeSpots,
  homeSpotTotal,
  homeCourses,
  visitedSpots,
  myCourses,
  impact,
  apiReady,
  onOpenSpot,
  onOpenCourse,
  savedIds,
  onToggleSaveSpot,
  savedCourseIds,
  onToggleSaveCourse,
}) {
  const featuredSpot = homeSpots.find((spot) => spot.spot_id === selectedSpotId) ?? homeSpots[0];
  const nearbySpots = useMemo(() => withMockDistance(homeSpots), [homeSpots]);

  return (
    <section className="screen home-screen">
      <HomeHero apiReady={apiReady} onOpenSpot={onOpenSpot} />

      {/* 위치 기반 근처 추천 — MVP: 실제 GPS 대신 예시 위치 기준 목업 거리 */}
      <SectionHeader title="내 주변 널널 관광지" action={MOCK_LOCATION_LABEL} />
      <div className="course-carousel">
        {nearbySpots.length ? (
          nearbySpots.map((spot) => (
            <SpotCard
              key={spot.spot_id}
              spot={spot}
              selected={spot.spot_id === selectedSpotId}
              saved={savedIds.includes(spot.spot_id)}
              onToggleSave={() => onToggleSaveSpot(spot)}
              onClick={() => onOpenSpot(spot.spot_id)}
            />
          ))
        ) : (
          <EmptyState />
        )}
      </div>

      {homeCourses.length > 0 && (
        <>
          <SectionHeader title="인기 널널 코스" action="여행자들이 공유한 코스" />
          <div className="course-carousel">
            {homeCourses.map((course) => (
              <PopularCourseCard
                key={course.course_id}
                course={course}
                saved={savedCourseIds.includes(course.course_id)}
                onToggleSave={() => onToggleSaveCourse(course)}
                onClick={() => onOpenCourse(course.course_id)}
              />
            ))}
          </div>
        </>
      )}

      {myCourses.length > 0 && (
        <>
          <SectionHeader
            title="내 코스"
            action={`평균 혼잡 회피 ${Math.round(
              myCourses.reduce((sum, c) => sum + (c.relief_pct ?? 0), 0) / myCourses.length,
            )}% · ${myCourses.length}개`}
          />
          <div className="course-carousel">
            {myCourses.map((course) => (
              <MyCourseCard
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

      <footer className="home-footer">
        <HomeStats impact={impact} homeSpotTotal={homeSpotTotal} featuredSpot={featuredSpot} />
      </footer>
    </section>
  );
}
