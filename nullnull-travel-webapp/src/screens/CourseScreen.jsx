// 코스 결과 화면 — 타임라인·동선 지도·요약 지표·여행하기·공유·피드백·후기
import { useState } from 'react';
import {
  ArrowRight,
  Check,
  ImagePlus,
  Leaf,
  Loader2,
  MessageSquareText,
  Navigation,
  RefreshCcw,
  Share2,
  Shuffle,
  ThumbsDown,
  ThumbsUp,
  UsersRound,
} from 'lucide-react';
import PointsMap from '../components/PointsMap';
import {
  Button,
  Card,
  SectionHeader,
  StarRating,
  SummaryMetric,
  Tag,
  EmptyState,
} from '../components/common';
import { CrowdBadge } from '../components/crowd';
import { TimelineItem } from '../components/cards';
import { reviewTags, SLOT_START_HOUR } from '../constants';
import { imageUrl } from '../utils/image';

const FEEDBACK_OPTIONS = [
  { label: '생각보다 한산했어요', icon: ThumbsUp, perceived: -1 },
  { label: '예상과 비슷했어요', icon: Check, perceived: 0 },
  { label: '생각보다 붐볐어요', icon: ThumbsDown, perceived: 1 },
];

// "45분" 같은 문구에서 분(minute) 숫자만 뽑는다
const minutesOf = (text) => Number(text?.match(/(\d+)\s*분/)?.[1] ?? 0);

// 코스 시간대 기준 도착 시각 — 체류·이동 시간을 누적해 계산
function withArrivalTimes(timelineItems, timeSlot) {
  let clock = (SLOT_START_HOUR[timeSlot] ?? SLOT_START_HOUR.afternoon) * 60;
  return timelineItems.map((item) => {
    const arrival = `${Math.floor(clock / 60)}:${String(clock % 60).padStart(2, '0')}`;
    clock += minutesOf(item.meta) + minutesOf(item.move);
    return { ...item, arrival };
  });
}

function ReviewComposer({
  rating,
  setRating,
  selectedTags,
  toggleTag,
  reviewText,
  setReviewText,
  onSubmit,
}) {
  return (
    <Card className="review-composer">
      <SectionHeader title="방문 후기 작성" compact />
      <div className="rating-panel">
        <span className="eyebrow">여행 만족도</span>
        <StarRating rating={rating} onChange={setRating} />
        <strong>{rating}.0</strong>
      </div>

      <div className="review-tags">
        {reviewTags.map((tag) => (
          <button
            key={tag}
            className={selectedTags.includes(tag) ? 'is-selected' : ''}
            onClick={() => toggleTag(tag)}
          >
            {tag}
          </button>
        ))}
      </div>

      <label className="review-textarea">
        <MessageSquareText size={18} />
        <textarea
          value={reviewText}
          onChange={(event) => setReviewText(event.target.value)}
          placeholder="이 코스가 얼마나 여유로웠는지 알려주세요."
        />
      </label>

      <div className="review-actions">
        <button className="photo-button">
          <ImagePlus size={18} />
          사진 추가
        </button>
        <Button onClick={onSubmit}>
          후기 등록
          <ArrowRight size={18} />
        </Button>
      </div>
    </Card>
  );
}

function RecentReviews({ reviews }) {
  return (
    <Card className="recent-reviews">
      <SectionHeader title="최근 방문 후기" compact />
      {reviews.length ? (
        <div className="review-list">
          {reviews.map((review) => {
            const name = review.name ?? review.nickname;
            const date = review.date ?? review.date_text;
            return (
              <article className="review-item" key={`${name}-${date}-${review.text}`}>
                <div className="review-item-head">
                  <div className="avatar">{name.slice(0, 1)}</div>
                  <div>
                    <strong>{name}</strong>
                    <span>{date}</span>
                  </div>
                  <StarRating rating={review.rating} readonly compact />
                </div>
                <p>{review.text}</p>
                <div className="review-tag-list">
                  {review.tags.map((tag) => (
                    <span key={tag}>{tag}</span>
                  ))}
                </div>
              </article>
            );
          })}
        </div>
      ) : (
        <EmptyState />
      )}
    </Card>
  );
}

export default function CourseScreen({
  courseView,
  courseAlternatives,
  showToast,
  onSwap,
  onReroll,
  rerolling,
  onSubmitFeedback,
  onSubmitReview,
  onShareCourse,
  sharing = false,
  onStartTravel,
  activeCourseId = null,
}) {
  const [feedback, setFeedback] = useState('');
  const [rating, setRating] = useState(4);
  const [selectedTags, setSelectedTags] = useState(['한산했어요']);
  const [reviewText, setReviewText] = useState('');
  const timelineItems = courseView?.timeline ?? [];
  const summary = courseView?.summary;
  const reviewItems = courseView?.reviews?.recent ?? [];
  const isFree = courseView?.mode === 'free';
  const swapSlots = (courseAlternatives?.items ?? []).filter((slot) => slot.alternatives.length);
  const timedItems = withArrivalTimes(timelineItems, courseView?.time_slot);

  const toggleTag = (tag) => {
    setSelectedTags((current) =>
      current.includes(tag) ? current.filter((item) => item !== tag) : [...current, tag],
    );
  };

  const handleFeedback = async (label, perceived) => {
    setFeedback(label);
    try {
      await onSubmitFeedback(perceived);
      showToast('피드백이 반영됐어요');
    } catch (error) {
      console.warn(error);
      showToast('피드백 저장 중 문제가 생겼어요');
    }
  };

  const handleReviewSubmit = async () => {
    try {
      await onSubmitReview({ rating, tags: selectedTags, text: reviewText });
      setReviewText('');
      showToast('후기가 저장됐어요');
    } catch (error) {
      console.warn(error);
      showToast('후기 저장 중 문제가 생겼어요');
    }
  };

  return (
    <section className="screen course-screen">
      <Card className="course-summary-hero">
        <div>
          <Tag icon={isFree ? Shuffle : Leaf}>{isFree ? '자유여행 코스' : '추천 코스'}</Tag>
          {courseView?.companion_label && <Tag icon={UsersRound}>{courseView.companion_label}</Tag>}
          <h1>{courseView?.title ?? '생성한 코스를 불러오고 있어요.'}</h1>
          <p>
            {courseView?.description ?? '대안 관광지를 선택하면 실제 데이터로 코스를 구성합니다.'}
          </p>
        </div>
        <CrowdBadge level={courseView?.level ?? 1} size="large" />
      </Card>

      <div className="timeline">
        {timedItems.length ? (
          timedItems.map((item, index) => (
            <TimelineItem
              key={item.place}
              item={item}
              index={index}
              isLast={index === timedItems.length - 1}
            />
          ))
        ) : (
          <EmptyState />
        )}
      </div>

      {(courseView?.map_points?.length ?? 0) > 1 && (
        <Card className="course-map-card">
          <SectionHeader title="코스 동선" compact />
          <PointsMap
            points={courseView.map_points.map((p) => ({
              lat: p.lat,
              lng: p.lng,
              pin: p.order_no === 0 ? '출발' : String(p.order_no),
              className: p.order_no === 0 ? 'is-origin' : '',
              tooltip: p.name,
            }))}
          />
        </Card>
      )}

      <Card className="summary-card">
        <SummaryMetric label="예상 혼잡 감소" value={summary ? `${summary.relief_pct}%` : '-'} />
        <SummaryMetric
          label={isFree ? '카테고리 일치 정도' : '테마 유지 정도'}
          value={summary ? `${summary.theme_keep_pct}%` : '-'}
        />
        <SummaryMetric label="총 이동시간" value={summary ? `${summary.total_move_min}분` : '-'} />
        <SummaryMetric
          label="총 이동거리"
          value={summary?.total_distance_km ? `${summary.total_distance_km}km` : '-'}
        />
      </Card>

      {/* 여행하기 — 이 코스를 사용하겠다는 선택. 여행 중인 코스로 지정 + 마이페이지 저장 */}
      {courseView?.course_id &&
        (courseView.course_id === activeCourseId ? (
          <div className="share-done travel-active">
            <Navigation size={17} />
            지금 이 코스로 여행 중이에요 — 마이페이지에서 확인할 수 있어요
          </div>
        ) : (
          <Button full onClick={() => onStartTravel?.(courseView)}>
            <Navigation size={18} />이 코스로 여행하기
          </Button>
        ))}

      {courseView?.course_id && (
        <button
          className="button button-full share-course-button"
          onClick={onReroll}
          disabled={rerolling}
        >
          {rerolling ? (
            <>
              <Loader2 size={18} className="spin" />
              다른 조합을 찾는 중
            </>
          ) : (
            <>
              <Shuffle size={18} />
              다른 코스 추천
            </>
          )}
        </button>
      )}

      {/* F9 코스 공유 — 공개하면 홈 '인기 널널 코스'에 노출된다 */}
      {courseView?.course_id &&
        (courseView.is_shared ? (
          <div className="share-done">
            <Check size={17} />
            공개된 코스예요 — 홈 인기 널널 코스에서 다른 여행자에게 보여요
          </div>
        ) : (
          <button
            className="button button-full share-course-button"
            onClick={onShareCourse}
            disabled={sharing}
          >
            {sharing ? (
              <>
                <Loader2 size={18} className="spin" />
                코스를 공개하는 중
              </>
            ) : (
              <>
                <Share2 size={18} />이 코스를 다른 여행자에게 공유하기
              </>
            )}
          </button>
        ))}

      {swapSlots.length > 0 && (
        <Card className="swap-card">
          <SectionHeader title="이 코스의 대안" compact />
          <p className="swap-hint">
            마음에 안 드는 장소는 탭 한 번으로 바꿔보세요. 원래 코스도 그대로 남아요.
          </p>
          {swapSlots.map((slot) => (
            <div className="swap-slot" key={slot.order_no}>
              <div className="swap-current">
                <span className="swap-order">{slot.order_no}</span>
                <strong>{slot.name}</strong>
                {slot.slot_theme && <Tag>{slot.slot_theme}</Tag>}
                <RefreshCcw size={15} />
              </div>
              <div className="swap-options">
                {slot.alternatives.map((alt) => (
                  <button
                    key={alt.spot_id}
                    className="swap-option"
                    onClick={() => onSwap(slot.order_no, alt.spot_id)}
                    title={alt.reason}
                  >
                    <img src={imageUrl(alt.image_url, alt.name)} alt={alt.name} />
                    <span className="swap-body">
                      <span className="swap-name">
                        {alt.name}
                        <CrowdBadge level={alt.level} />
                      </span>
                      <span className="swap-metrics">
                        혼잡 -{alt.decrease_pct}% · 이동 {alt.travel_time_min}분
                        {alt.hidden_gem ? ' · 숨은 명소' : ''}
                      </span>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </Card>
      )}

      <Card className="feedback-card">
        <SectionHeader title="방문 후 피드백" compact />
        <div className="feedback-grid">
          {FEEDBACK_OPTIONS.map(({ label, icon: Icon, perceived }) => (
            <button
              key={label}
              className={`feedback-button ${feedback === label ? 'is-selected' : ''}`}
              onClick={() => handleFeedback(label, perceived)}
            >
              <Icon size={19} />
              {label}
            </button>
          ))}
        </div>
      </Card>

      <ReviewComposer
        rating={rating}
        setRating={setRating}
        selectedTags={selectedTags}
        toggleTag={toggleTag}
        reviewText={reviewText}
        setReviewText={setReviewText}
        onSubmit={handleReviewSubmit}
      />

      <RecentReviews reviews={reviewItems} />
    </section>
  );
}
