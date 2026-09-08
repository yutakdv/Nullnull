import type { components } from '@nullnull/api-client';
import { render, screen } from '@testing-library/react';
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

  it('shows the state label and the credit as separate pieces', () => {
    render(<FeedPostCard card={card} />);
    expect(screen.getByText('실시간 관측')).toBeInTheDocument();
    expect(screen.getByText('출처: ⓒ한국관광공사')).toBeInTheDocument();
  });
});

describe('TripItemCard', () => {
  const item = {
    id: '018f3f8e-1111-7a21-8d31-31d315b93911',
    place,
    date: '2026-10-04',
    position: 0,
    startTime: '09:30',
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

describe('TabBar', () => {
  it('exposes the four P0 tabs and marks the active one', () => {
    render(<TabBar active="trip" />);
    expect(screen.getAllByRole('button')).toHaveLength(4);
    expect(screen.getByRole('button', { name: /내 여행/ })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('does not render the P1 search tab', () => {
    render(<TabBar active="home" />);
    expect(screen.queryByRole('button', { name: /검색/ })).not.toBeInTheDocument();
  });
});
