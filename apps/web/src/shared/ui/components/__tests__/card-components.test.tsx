import type { components } from '@nullnull/api-client';
import { feedFixtures, placeFixtures } from '@nullnull/contracts';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CandidateCard, FeedPostCard, TabBar, TripItemCard } from '../index.js';

type FeedCard = components['schemas']['FeedCard'];
type TripItem = components['schemas']['TripItem'];
type TripCandidate = components['schemas']['TripCandidate'];

// Fixtures carry only the fields these components read. The casts narrow to
// that subset rather than inventing a shape: the contract types stay the
// source of truth for what the components accept.
const place = {
  id: '018f3f8e-9b67-7a21-8d31-31d315b93911',
  name: '경복궁',
} as FeedCard['primaryPlace'];

const provenance = {
  attribution: '출처: ⓒ한국관광공사',
  attributionShort: '출처: ⓒ한국관광공사',
  officialUrl: null,
  licenseUrl: null,
  observedAt: null,
} as components['schemas']['DataProvenance'];

const seoulProvenance = {
  ...provenance,
  source: 'SEOUL_CITYDATA',
  attribution:
    '출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)',
  attributionShort: '출처: 서울특별시',
  observedAt: '2026-10-04T05:35:00Z',
} as components['schemas']['DataProvenance'];

const TAB_LABELS = {
  home: '홈',
  trip: '내 여행',
  live: '라이브',
  profile: '내 정보',
};

describe('FeedPostCard', () => {
  const card = {
    post: {
      id: '018f3f8e-0000-7a21-8d31-31d315b93911',
      title: '수문장 교대의식은 10시부터',
      coverUrl: 'https://example.test/cover.jpg',
      publishedAt: '2026-10-02T00:00:00Z',
    },
    primaryPlace: place,
    crowd: {
      state: 'LIVE',
      label: '4 · 혼잡',
      ordinalLevel: '4',
      provenance,
    } as components['schemas']['CrowdMetric'],
    savedPost: false,
    candidateState: 'NOT_SAVED',
  } as FeedCard;

  it('keeps post bookmarking and candidate saving separate', () => {
    render(<FeedPostCard card={card} />);
    // The add control speaks about 담기, not scheduling.
    const name =
      screen.getByRole('button', { name: /담기/ }).getAttribute('aria-label') ?? '';
    expect(name).not.toContain('일정');
  });

  it('offers trip creation when no trip is selected', () => {
    render(<FeedPostCard card={{ ...card, candidateState: 'NO_TRIP_SELECTED' }} />);
    expect(
      screen.getByRole('button', { name: '여행을 만들고 담기' }),
    ).toBeInTheDocument();
  });

  it('FE-201-T2 FE-202-T2 shows the state label and credit separately (FCR-011 trace)', () => {
    render(<FeedPostCard card={card} />);
    expect(screen.getByText('실시간 관측')).toBeInTheDocument();
    expect(screen.getByText('출처: ⓒ한국관광공사')).toBeInTheDocument();
  });

  it('FE-201-T2 FE-202-T2 keeps Seoul and KTO credits separate (FCR-011 trace)', () => {
    const sourcedPlace = feedFixtures.page.items.find(
      (item) => item.primaryPlace.sourceAttribution !== null,
    )?.primaryPlace;
    const crowd = card.crowd;
    expect(sourcedPlace?.sourceAttribution).toBeDefined();
    expect(crowd).toBeDefined();
    if (!sourcedPlace?.sourceAttribution || !crowd) return;

    render(
      <FeedPostCard
        card={{
          ...card,
          primaryPlace: { ...place, sourceAttribution: sourcedPlace.sourceAttribution },
          crowd: { ...crowd, provenance: seoulProvenance },
        }}
      />,
    );

    const article = screen.getByRole('article');
    expect(within(article).getByText('출처: ⓒ한국관광공사')).toBeInTheDocument();
    expect(within(article).getByText('출처: 서울특별시')).toBeInTheDocument();
    expect(within(article).getByText('실시간 관측')).toBeInTheDocument();
  });
});

describe('TripItemCard', () => {
  const item = {
    id: '018f3f8e-1111-7a21-8d31-31d315b93911',
    place,
    date: '2026-10-04',
    position: 0,
    startTime: '09:30:00',
    constraints: [{ type: 'DATE' }, { type: 'MUST_VISIT' }],
  } as TripItem;

  it('renders each present lock separately and leaves others unlocked', () => {
    render(<TripItemCard item={item} />);
    expect(screen.getByRole('button', { name: '날짜 고정' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: '시간 고정' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(screen.getByText('꼭 가요')).toBeInTheDocument();
  });

  it('asks about one constraint at a time', async () => {
    const seen: string[] = [];
    render(<TripItemCard item={item} onToggleConstraint={(t) => seen.push(t)} />);
    screen.getByRole('button', { name: '날짜 고정' }).click();
    expect(seen).toEqual(['DATE']);
  });

  it('states a changed row in words', () => {
    render(<TripItemCard item={item} state="optimized" />);
    expect(screen.getByText('최적화 반영')).toBeInTheDocument();
  });
});

describe('CandidateCard', () => {
  const candidate = {
    id: '018f3f8e-2222-7a21-8d31-31d315b93911',
    tripId: '018f3f8e-3333-7a21-8d31-31d315b93911',
    place,
    status: 'ACTIVE',
    sources: [],
    createdAt: '2026-10-02T00:00:00Z',
  } as TripCandidate;

  it('says a candidate has no date or time yet', () => {
    render(<CandidateCard candidate={candidate} />);
    expect(screen.getByText('날짜·시간 없이 담아둔 장소예요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '일정에 넣기' })).toBeInTheDocument();
  });

  it('stops offering scheduling once it is scheduled', () => {
    render(<CandidateCard candidate={{ ...candidate, status: 'SCHEDULED' }} />);
    expect(screen.queryByRole('button', { name: '일정에 넣기' })).not.toBeInTheDocument();
  });
});

describe('FE-603-T5 the shared place cards credit their place', () => {
  // Neither card is mounted by a screen today (TripScreen and CandidatesScreen
  // draw their own rows). They are credited anyway so the first screen that
  // adopts one does not ship an uncredited place. The place is the search
  // fixture's credited one.
  const sourced = placeFixtures.searchPage.items[0];
  const credit = sourced?.sourceAttribution;
  if (!sourced || !credit) throw new Error('the search fixture lost its credited place');

  it('credits the place on a trip item card', () => {
    render(
      <TripItemCard
        item={
          {
            id: '018f3f8e-1111-7a21-8d31-31d315b93911',
            place: sourced,
            date: '2026-10-04',
            position: 0,
            startTime: '09:30:00',
            constraints: [],
          } as TripItem
        }
      />,
    );
    expect(
      within(screen.getByRole('article')).getByRole('link', {
        name: credit.attribution,
      }),
    ).toHaveAttribute('href', credit.officialUrl ?? '');
  });

  it('credits the place on a candidate card', () => {
    render(
      <CandidateCard
        candidate={
          {
            id: '018f3f8e-2222-7a21-8d31-31d315b93911',
            tripId: '018f3f8e-3333-7a21-8d31-31d315b93911',
            place: sourced,
            status: 'ACTIVE',
            sources: [],
            createdAt: '2026-10-02T00:00:00Z',
          } as TripCandidate
        }
      />,
    );
    expect(
      within(screen.getByRole('article')).getByRole('link', {
        name: credit.attribution,
      }),
    ).toHaveAttribute('href', credit.officialUrl ?? '');
  });
});

describe('TabBar', () => {
  it('exposes the four P0 tabs and marks the active one', () => {
    render(<TabBar active="trip" labels={TAB_LABELS} navLabel="주요 메뉴" />);
    expect(screen.getAllByRole('button')).toHaveLength(4);
    expect(screen.getByRole('button', { name: /내 여행/ })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('does not render the P1 search tab', () => {
    render(<TabBar active="home" labels={TAB_LABELS} navLabel="주요 메뉴" />);
    expect(screen.queryByRole('button', { name: /검색/ })).not.toBeInTheDocument();
  });
});
