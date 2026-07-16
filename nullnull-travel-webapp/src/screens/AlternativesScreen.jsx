// 대안 추천 화면 — 원래 가려던 곳 대비 널널한 대안 목록 + 경로 지도
import { ArrowDown, ArrowRight, Navigation, UsersRound } from 'lucide-react';
import PointsMap from '../components/PointsMap';
import CourseFinder from '../components/CourseFinder';
import { Button, Card, EmptyState } from '../components/common';
import { CrowdLegend } from '../components/crowd';
import { AlternativeCard } from '../components/cards';
import { companionHints } from '../constants';
import { imageUrl } from '../utils/image';
import { mapAlternative } from '../utils/mappers';

export default function AlternativesScreen({
  setModal,
  onCreateCourse,
  alternativeView,
  companion,
  ...finderProps
}) {
  const origin = alternativeView?.origin;
  const recommendationList = alternativeView?.alternatives?.map(mapAlternative) ?? [];
  const routeSummary = alternativeView?.route_summary;

  return (
    <section className="screen alternatives-screen">
      <CourseFinder companion={companion} {...finderProps} />
      <div className="alternative-layout">
        <div className="recommendation-column">
          <Card className="original-card">
            <div className="mini-photo">
              <img
                src={imageUrl(origin?.image_url, origin?.name)}
                alt={origin?.name ?? '원래 관광지'}
              />
            </div>
            <div>
              <span className="eyebrow">원래 가려던 곳</span>
              <h2>{origin?.name ?? '선택한 관광지'}</h2>
              <p>
                {origin
                  ? `예상 혼잡도 ${Math.round(origin.risk)}%`
                  : '추천 정보를 불러오고 있어요.'}
              </p>
            </div>
            <ArrowDown className="down-arrow" size={20} />
          </Card>

          {companion && companionHints[companion] && (
            <div className="companion-hint">
              <UsersRound size={16} />
              {companionHints[companion]}
            </div>
          )}

          <CrowdLegend />

          <div className="recommendation-list">
            {recommendationList.length ? (
              recommendationList.map((item) => (
                <AlternativeCard
                  key={item.title}
                  item={item}
                  onReason={() => setModal(item)}
                  onSelect={onCreateCourse}
                />
              ))
            ) : (
              <EmptyState />
            )}
          </div>

          {recommendationList.length > 0 && (
            <div className="alt-cta">
              <p>위 {recommendationList.length}곳을 이동 동선에 맞춰 하나의 코스로 묶어드려요.</p>
              <Button full onClick={onCreateCourse}>
                이 대안들로 코스 만들기
                <ArrowRight size={19} />
              </Button>
            </div>
          )}
        </div>

        <Card className="map-card">
          <div className="map-header">
            <div>
              <span className="eyebrow">경로 지도</span>
              <h2>{origin ? `${origin.name} 주변 여유 루트` : '여유 루트'}</h2>
            </div>
            <Navigation size={21} />
          </div>
          <PointsMap
            points={
              origin?.lat
                ? [
                    {
                      lat: origin.lat,
                      lng: origin.lng,
                      pin: '출발',
                      className: 'is-origin',
                      tooltip: origin.name,
                    },
                    ...(alternativeView?.alternatives ?? []).map((alt, index) => ({
                      lat: alt.lat,
                      lng: alt.lng,
                      pin: String(index + 1),
                      className: `is-level-${alt.level}`,
                      tooltip: `${alt.name} · ${alt.label}`,
                    })),
                  ]
                : []
            }
          />
          <div className="map-summary">
            <span>총 {routeSummary?.total_distance_km ?? '-'}km</span>
            <span>차량 {routeSummary?.total_drive_min ?? '-'}분</span>
            <span>도보 {routeSummary?.total_walk_km ?? '-'}km</span>
          </div>
        </Card>
      </div>
    </section>
  );
}
