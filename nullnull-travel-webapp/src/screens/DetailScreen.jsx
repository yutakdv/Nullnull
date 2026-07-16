// 관광지 상세 — 소개·널널도 헤드라인·시간 분산 제안·요일/캘린더 히트맵
import { useState } from 'react';
import { ArrowRight, ChevronRight, Clock3, Heart, Map as MapIcon, Sparkles } from 'lucide-react';
import SmartImage from '../components/SmartImage';
import {
  Button,
  Card,
  IconButton,
  ProofBar,
  SectionHeader,
  StarRating,
  EmptyState,
} from '../components/common';
import { CalendarHeat, CrowdBadge, TimeCard, WeekdayHeat } from '../components/crowd';
import { mapTimeSlotCards } from '../utils/mappers';

// 관광지 소개 — 이미지 바로 아래, TourAPI overview(관광지별 상이)를 보여준다.
// overview가 없는 장소는 카테고리·주소로 만든 기본 소개 문장으로 대신한다.
function SpotIntro({ spot }) {
  const [expanded, setExpanded] = useState(false);
  if (!spot?.name) return null;
  const fallback =
    `${spot.name}은(는) ${spot.addr ?? spot.region ?? '서울'}에 있는 ` +
    `${spot.category_name ?? '관광'} 명소예요. ` +
    (spot.tags?.length ? `#${spot.tags.slice(0, 3).join(' #')} 테마로 둘러보기 좋아요.` : '');
  const text = spot.overview?.trim() || fallback;
  const long = text.length > 150;

  return (
    <Card className="spot-intro-card">
      <SectionHeader title="관광지 소개" compact />
      <p className={`spot-intro-text ${long && !expanded ? 'is-clamped' : ''}`}>{text}</p>
      {long && (
        <button className="spot-intro-more" onClick={() => setExpanded((v) => !v)}>
          {expanded ? '접기' : '더 보기'}
          <ChevronRight size={15} className={expanded ? 'is-open' : ''} />
        </button>
      )}
      {spot.highlight && (
        <p className="spot-intro-highlight">
          <Sparkles size={14} />
          {spot.highlight}
        </p>
      )}
    </Card>
  );
}

function ReviewProofCard({ proof, reviewStats }) {
  const avgRating = reviewStats.avg_rating ?? 0;
  const reviewCount = reviewStats.count ?? 0;

  return (
    <Card className="review-proof-card">
      <div className="review-proof-head">
        <span className="review-score">{Number(avgRating).toFixed(1)}</span>
        <div>
          <StarRating rating={Math.round(avgRating)} readonly compact />
          <p>방문 후기 {reviewCount.toLocaleString()}개 기반</p>
        </div>
      </div>
      <div className="proof-bars">
        <ProofBar label="한산함 예측 정확도" value={proof.prediction_accuracy_pct ?? 0} />
        <ProofBar label="동선 편안함" value={proof.route_comfort_pct ?? 0} />
        <ProofBar label="테마 만족도" value={proof.theme_satisfaction_pct ?? 0} />
      </div>
    </Card>
  );
}

export default function DetailScreen({
  isSaved,
  onToggleSave,
  onFindAlternatives,
  spot,
  congestionView,
  congestionChart,
  calendar,
  activeSlot,
  onTimeShift,
}) {
  // 요일별(이번 주) 히트맵과 한 달 캘린더는 같은 정보의 기간 차이라 탭으로 합쳤다
  const [heatRange, setHeatRange] = useState('week');
  const chartData = congestionChart ?? [];
  const timeCards = mapTimeSlotCards(congestionView);
  const proof = spot?.proof ?? {};
  const reviewStats = spot?.review_stats ?? {};
  const suggestions = congestionView?.time_shift_suggestions ?? [];
  const hasCalendar = (calendar?.days?.length ?? 0) > 0;

  return (
    <section className="screen detail-screen">
      <div className="detail-hero">
        <SmartImage src={spot?.image_url} name={spot?.name} alt={spot?.name ?? '추천 관광지'} />
        <div className="detail-actions">
          <IconButton label="지도 열기" className="glass">
            <MapIcon size={19} />
          </IconButton>
          <IconButton
            label={isSaved ? '저장 해제' : '저장하기'}
            className={`glass ${isSaved ? 'saved' : ''}`}
            onClick={onToggleSave}
          >
            <Heart size={19} fill={isSaved ? 'currentColor' : 'none'} />
          </IconButton>
        </div>
        <div className="place-title">
          <span>{spot?.addr ?? spot?.region ?? '서울'}</span>
          <h2>{spot?.name ?? '관광지 정보를 불러오는 중'}</h2>
        </div>
      </div>

      <SpotIntro spot={spot} />

      <Card className="null-score-card">
        <div>
          <span className="eyebrow">
            {congestionView ? `널널도 · ${congestionView.based_on}` : '널널도'}
            {congestionView?.adjusted && <em className="adjusted-chip">방문자 피드백 반영</em>}
            {congestionView?.tourist_pressure && (
              <em className="adjusted-chip">
                {congestionView.tourist_pressure}
                {congestionView.tourist_share_pct != null &&
                  ` · 관광객 ${congestionView.tourist_share_pct}%`}
              </em>
            )}
          </span>
          <strong>{congestionView?.label ?? '정보 준비 중'}</strong>
          {/* 서울시 실시간 혼잡 메시지가 있으면 tip 대신 표시(실측 문구 우선) */}
          <p>
            {congestionView?.congest_msg ?? congestionView?.tip ?? '혼잡도 정보를 불러오고 있어요.'}
          </p>
          {congestionView?.live_ppltn_min != null && congestionView?.live_ppltn_max != null && (
            <p>
              실시간 체류 인원 약 {congestionView.live_ppltn_min.toLocaleString()}~
              {congestionView.live_ppltn_max.toLocaleString()}명
            </p>
          )}
        </div>
        <CrowdBadge level={congestionView?.level ?? 1} size="large" />
      </Card>

      {suggestions.length > 0 && (
        <div className="suggestion-row">
          {suggestions.map((item) => (
            <button
              key={`${item.kind}-${item.date}-${item.time_slot}`}
              className="suggestion-chip"
              onClick={() => onTimeShift(item)}
            >
              <span className="suggestion-icon">
                <Clock3 size={17} />
              </span>
              <span className="suggestion-text">
                {item.text}
                <small>탭하면 이 시간으로 바꿔서 봐요</small>
              </span>
              <span className="suggestion-drop">붐빔 {item.decrease_pct}%↓</span>
              <ChevronRight size={18} className="suggestion-arrow" />
            </button>
          ))}
        </div>
      )}

      <ReviewProofCard proof={proof} reviewStats={reviewStats} />

      {/* 요일별 혼잡도 — 제목 아래 오전/오후/저녁 현재 혼잡도, 그 아래 요일 히트맵.
          한 달 캘린더는 같은 정보의 기간 확장이라 별도 카드 대신 탭으로 통합했다. */}
      <Card className="congestion-card">
        <SectionHeader title="요일별 혼잡도" compact />
        <div className="compare-grid">
          {timeCards.map((item) => (
            <TimeCard
              key={item.label}
              label={item.label}
              value={item.value}
              note={item.note}
              active={item.slot === activeSlot}
              onClick={() => onTimeShift({ kind: 'slot', time_slot: item.slot })}
            />
          ))}
        </div>

        {hasCalendar && (
          <div className="heat-range-tabs" role="tablist" aria-label="혼잡도 기간">
            <button
              role="tab"
              aria-selected={heatRange === 'week'}
              className={heatRange === 'week' ? 'is-active' : ''}
              onClick={() => setHeatRange('week')}
            >
              이번 주
            </button>
            <button
              role="tab"
              aria-selected={heatRange === 'month'}
              className={heatRange === 'month' ? 'is-active' : ''}
              onClick={() => setHeatRange('month')}
            >
              한 달
            </button>
          </div>
        )}

        {heatRange === 'week' || !hasCalendar ? (
          chartData.length ? (
            <WeekdayHeat data={chartData} />
          ) : (
            <EmptyState />
          )
        ) : (
          <>
            <p className="calendar-note">향후 30일 예측 기준 · 날짜를 탭하면 그 날로 이동해요</p>
            <CalendarHeat
              days={calendar.days}
              selectedDate={congestionView?.date}
              onPick={(day) =>
                onTimeShift({
                  kind: 'date',
                  date: day.date,
                  time_slot: activeSlot,
                })
              }
            />
          </>
        )}
      </Card>

      <Button full onClick={onFindAlternatives}>
        더 널널한 코스 보기
        <ArrowRight size={19} />
      </Button>
    </section>
  );
}
