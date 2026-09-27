// FE-001-T4: inside the app, a shared component that keeps Korean defaults
// speaks the chosen locale even when its caller passes no words.
//
// Each of these components keeps a Korean DEFAULT for a render with no
// I18nProvider at all (a bare unit test - the Storybook stories run inside
// one, .storybook/preview.tsx). Until this clause, that default was also what
// the app got whenever a caller left a label out: the defaults were reachable
// from an English screen, and the only thing standing between them and the
// user was every caller remembering every key. OptimizationRunScreen carried a
// comment warning exactly that about DecisionBar's stale and failed states.
// StateLabel and CrowdLevel closed it first (useOptionalI18n); this is the
// same fallback for the other seven. Two of them - CandidateCard and
// TripItemCard - no screen mounts today; they had no way to speak English at
// all, and the first screen to adopt one would have shipped Korean.
//
// Every case asserts the English word is THERE, not only that Korean is
// absent: "no Hangul" alone is satisfied by a component that renders nothing.
import type { components } from '@nullnull/api-client';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../../i18n/I18nProvider.js';
import { messages } from '../../../../i18n/messages.js';
import {
  CandidateCard,
  DataAttribution,
  DecisionBar,
  type DecisionState,
  MustVisitBadge,
  NavBar,
  type TripAddState,
  TripAddButton,
  TripItemCard,
} from '../index.js';

type TripCandidate = components['schemas']['TripCandidate'];
type TripItem = components['schemas']['TripItem'];

const en = messages['en-US'];
const ko = messages['ko-KR'];
const HANGUL = /[가-힣]/;

function english({ children }: { children: ReactNode }) {
  localStorage.setItem('nullnull.locale', 'en-US');
  return <I18nProvider>{children}</I18nProvider>;
}

/** Visible text, and every aria-label and title, in one string. */
function everythingShown(container: HTMLElement): string {
  const attributes = [...container.querySelectorAll('[aria-label], [title]')].flatMap(
    (element) => [
      element.getAttribute('aria-label') ?? '',
      element.getAttribute('title') ?? '',
    ],
  );
  return [container.textContent ?? '', ...attributes].join(' | ');
}

// A place name in Latin letters, so "no Hangul" judges the card's own words
// and not the server's name for the place.
const place = { id: '018f3f8e-9b67-7a21-8d31-31d315b93911', name: 'Gyeongbokgung' };

const candidate = {
  id: '018f3f8e-2222-7a21-8d31-31d315b93911',
  tripId: '018f3f8e-3333-7a21-8d31-31d315b93911',
  place,
  status: 'ACTIVE',
  sources: [],
  createdAt: '2026-10-02T00:00:00Z',
} as unknown as TripCandidate;

// Every lock and no start time, so each string the card owns is on screen.
const item = {
  id: '018f3f8e-1111-7a21-8d31-31d315b93911',
  place,
  date: '2026-10-04',
  position: 0,
  startTime: null,
  constraints: [
    { type: 'MUST_VISIT' },
    { type: 'DATE' },
    { type: 'TIME' },
    { type: 'RESERVATION' },
  ],
} as unknown as TripItem;

afterEach(() => {
  localStorage.removeItem('nullnull.locale');
});

describe('FE-001-T4 a shared component speaks the locale with no labels passed', () => {
  it.each<[DecisionState, string[]]>([
    ['preview', [en['decision.apply'], en['decision.keep']]],
    ['applying', [en['decision.applying'], en['decision.keep']]],
    ['applied', [en['decision.applied']]],
    ['stale', [en['decision.staleMessage'], en['decision.staleAction']]],
    ['failed', [en['decision.failedMessage'], en['decision.failedAction']]],
  ])('FE-001-T4 DecisionBar words its %s state in English', (state, words) => {
    // stale and failed are the states that are hard to reach from a screen,
    // which is where an untranslated default survives longest.
    const { container } = render(<DecisionBar state={state} />, { wrapper: english });
    expect(
      screen.getByRole('group', { name: en['decision.groupLabel'] }),
    ).toBeInTheDocument();
    for (const word of words) expect(container).toHaveTextContent(word);
    expect(everythingShown(container)).not.toMatch(HANGUL);
  });

  it.each<TripAddState>(['idle', 'saved', 'no-trip', 'duplicate', 'loading', 'error'])(
    'FE-001-T4 TripAddButton names its %s state in English',
    (state) => {
      render(<TripAddButton state={state} />, { wrapper: english });
      expect(screen.getByRole('button')).toHaveAccessibleName(en[`tripAdd.${state}`]);
    },
  );

  it('FE-001-T4 NavBar names its back control in English', () => {
    render(<NavBar onBack={() => undefined} />, { wrapper: english });
    expect(screen.getByRole('button')).toHaveAccessibleName(en['nav.back']);
  });

  it('FE-001-T4 MustVisitBadge says must-visit in English', () => {
    const { container } = render(<MustVisitBadge />, { wrapper: english });
    expect(container).toHaveTextContent(en['mustVisit.badge']);
    expect(container.textContent).not.toMatch(HANGUL);
  });

  it('FE-001-T4 DataAttribution names its licence link in English', () => {
    // The credit itself is the server's approved wording and stays as sent
    // (CMP-ATT-003) - here it is plain ASCII so only the link is judged.
    render(
      <DataAttribution
        provenance={{
          attribution: 'Source: Example',
          licenseUrl: 'https://example.test/licence',
        }}
        showLicense
      />,
      { wrapper: english },
    );
    expect(screen.getByRole('link', { name: en['license.terms'] })).toHaveAttribute(
      'href',
      'https://example.test/licence',
    );
  });

  it('FE-001-T4 CandidateCard words a saved and a scheduled candidate in English', () => {
    const { container, rerender } = render(<CandidateCard candidate={candidate} />, {
      wrapper: english,
    });
    expect(container).toHaveTextContent(en['candidateCard.unscheduled']);
    expect(
      screen.getByRole('button', { name: en['candidateCard.schedule'] }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: en['candidateCard.remove'] }),
    ).toBeInTheDocument();
    expect(everythingShown(container)).not.toMatch(HANGUL);

    rerender(<CandidateCard candidate={{ ...candidate, status: 'SCHEDULED' }} />);
    expect(container).toHaveTextContent(en['candidateCard.scheduled']);
    expect(everythingShown(container)).not.toMatch(HANGUL);
  });

  it.each(['changed', 'conflict', 'optimized'] as const)(
    'FE-001-T4 TripItemCard words a %s row in English',
    (state) => {
      const { container } = render(<TripItemCard item={item} state={state} />, {
        wrapper: english,
      });
      expect(container).toHaveTextContent(en[`tripItemCard.badge.${state}`]);
      expect(container).toHaveTextContent(en['trip.timeUnset']);
      expect(container).toHaveTextContent(en['mustVisit.badge']);
      expect(
        screen.getByRole('button', { name: en['trip.lock.DATE'] }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: en['trip.lock.TIME'] }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: en['trip.lock.RESERVATION'] }),
      ).toHaveAttribute('title', en['trip.lock.reservationNote']);
      expect(
        screen.getByRole('button', {
          name: en['trip.item.actions'].replace('{name}', place.name),
        }),
      ).toBeInTheDocument();
      expect(everythingShown(container)).not.toMatch(HANGUL);
    },
  );
});

// The fallback sits BEHIND the caller: screens pass words of their own that
// are not the component's (NavBar's back control is "Leave" on the run screen,
// not "Back"), and a fallback placed in front would silently replace them.
describe('a caller that passes words still gets its own words', () => {
  it('keeps each component’s override ahead of the locale', () => {
    const { container } = render(
      <>
        <DecisionBar labels={{ keep: 'KEEP-OWN' }} state="preview" />
        <TripAddButton labels={{ idle: 'ADD-OWN' }} state="idle" />
        <NavBar backLabel="BACK-OWN" onBack={() => undefined} />
        <MustVisitBadge label="MUST-OWN" />
        <DataAttribution
          provenance={{ attribution: 'Source', licenseUrl: 'https://example.test/l' }}
          showLicense
          termsLabel="TERMS-OWN"
        />
        <CandidateCard candidate={candidate} labels={{ schedule: 'SCHEDULE-OWN' }} />
        <TripItemCard
          item={item}
          labels={{ changed: 'CHANGED-OWN', menu: 'MENU-OWN' }}
          state="changed"
        />
      </>,
      { wrapper: english },
    );
    expect(screen.getByRole('button', { name: 'KEEP-OWN' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'ADD-OWN' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'BACK-OWN' })).toBeInTheDocument();
    expect(container).toHaveTextContent('MUST-OWN');
    expect(screen.getByRole('link', { name: 'TERMS-OWN' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'SCHEDULE-OWN' })).toBeInTheDocument();
    expect(container).toHaveTextContent('CHANGED-OWN');
    expect(screen.getByRole('button', { name: 'MENU-OWN' })).toBeInTheDocument();
  });
});

// With no provider at all - a bare unit test - the Korean defaults are still
// what renders. This is the reason they exist; the cases above only move them
// out of the app's reach.
describe('with no I18nProvider the Korean defaults still render', () => {
  it('draws the Figma Korean wording for a bare render', () => {
    const { container } = render(
      <>
        <DecisionBar state="preview" />
        <TripAddButton state="idle" />
        <NavBar onBack={() => undefined} />
        <MustVisitBadge />
        <DataAttribution
          provenance={{ attribution: 'Source', licenseUrl: 'https://example.test/l' }}
          showLicense
        />
        <CandidateCard candidate={candidate} />
        <TripItemCard item={item} state="optimized" />
      </>,
    );
    expect(screen.getByRole('button', { name: ko['decision.keep'] })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ko['tripAdd.idle'] })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ko['nav.back'] })).toBeInTheDocument();
    expect(container).toHaveTextContent(ko['mustVisit.badge']);
    expect(screen.getByRole('link', { name: ko['license.terms'] })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: ko['candidateCard.schedule'] }),
    ).toBeInTheDocument();
    expect(container).toHaveTextContent(ko['tripItemCard.badge.optimized']);
  });
});
