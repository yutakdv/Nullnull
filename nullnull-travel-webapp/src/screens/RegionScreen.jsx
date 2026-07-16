// 검색 탭 — 키워드 검색 + 서울 25개 자치구 + 카테고리 필터로 tourAPI 관광지 카탈로그 탐색
import { ChevronRight, Loader2, MapPin } from 'lucide-react';
import SpotSearch from '../components/SpotSearch';
import { EmptyState } from '../components/common';
import { RegionSpotCard } from '../components/cards';
import { SEARCH_CATEGORIES, SEOUL_DISTRICTS } from '../constants';

export default function RegionScreen({
  selectedDistrict,
  selectedCategory,
  spots,
  total,
  hasMore,
  loading,
  onSelectDistrict,
  onSelectCategory,
  onLoadMore,
  onOpenSpot,
  apiReady,
}) {
  const firstLoad = loading && spots.length === 0;
  return (
    <section className="screen region-screen">
      <div className="region-hero">
        <span className="eyebrow">서울 관광지 검색</span>
        <h1>어디로 떠나볼까요?</h1>
        <p className="region-note">이름으로 찾거나, 지역·카테고리를 골라 둘러보세요.</p>
        <SpotSearch onPick={onOpenSpot} disabled={!apiReady} />
        <label className="district-select">
          <MapPin size={17} />
          <select
            value={selectedDistrict}
            onChange={(event) => onSelectDistrict(event.target.value)}
            aria-label="지역(구) 선택"
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
        <div className="category-chips">
          {SEARCH_CATEGORIES.map((c) => (
            <button
              key={c}
              type="button"
              className={`category-chip ${selectedCategory === c ? 'is-active' : ''}`}
              onClick={() => onSelectCategory(c)}
            >
              {c}
            </button>
          ))}
        </div>
      </div>

      <div className="section-header">
        <h2>
          {selectedDistrict || '서울'} · {selectedCategory}
        </h2>
        {!firstLoad && total > 0 && <button type="button">{total.toLocaleString()}곳</button>}
      </div>

      {firstLoad ? (
        <div className="region-loading">
          <Loader2 size={22} className="spin" />
          관광지를 불러오는 중
        </div>
      ) : spots.length ? (
        <>
          <div className="region-results">
            {spots.map((spot) => (
              <RegionSpotCard
                key={spot.spot_id}
                spot={spot}
                onClick={() => onOpenSpot(spot.spot_id)}
              />
            ))}
          </div>
          {hasMore && (
            <button className="region-more" onClick={onLoadMore} disabled={loading}>
              {loading ? (
                <>
                  <Loader2 size={17} className="spin" />
                  불러오는 중
                </>
              ) : (
                '더 보기'
              )}
            </button>
          )}
        </>
      ) : (
        <EmptyState />
      )}
    </section>
  );
}
