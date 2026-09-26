// FE-001-T4: inside the app, a shared component that keeps Korean defaults
// speaks the chosen locale even when its caller passes no words.
//
// Each of these components keeps a Korean DEFAULT so a Storybook story can
// mount it with no I18nProvider. Until this clause, that default was also what
// the app got whenever a caller left a label out: the defaults were reachable
// from an English screen, and the only thing standing between them and the
// user was every caller remembering every key. OptimizationRunScreen carried a
// comment warning exactly that about DecisionBar's stale and failed states.
// StateLabel and CrowdLevel closed it first (useOptionalI18n); this is the
// same fallback for the other five.
//
// Every case asserts the English word is THERE, not only that Korean is
// absent: "no Hangul" alone is satisfied by a component that renders nothing.
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../../i18n/I18nProvider.js';
import { messages } from '../../../../i18n/messages.js';
import {
  DataAttribution,
  DecisionBar,
  type DecisionState,
  MustVisitBadge,
  NavBar,
  type TripAddState,
  TripAddButton,
} from '../index.js';

const en = messages['en-US'];
const ko = messages['ko-KR'];
const HANGUL = /[가-힣]/;

function english({ children }: { children: ReactNode }) {
  localStorage.setItem('nullnull.locale', 'en-US');
  return <I18nProvider>{children}</I18nProvider>;
}

/** Visible text and every accessible name set by aria-label, in one string. */
function everythingShown(container: HTMLElement): string {
  const labels = [...container.querySelectorAll('[aria-label]')].map(
    (element) => element.getAttribute('aria-label') ?? '',
  );
  return [container.textContent ?? '', ...labels].join(' | ');
}

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
      </>,
      { wrapper: english },
    );
    expect(screen.getByRole('button', { name: 'KEEP-OWN' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'ADD-OWN' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'BACK-OWN' })).toBeInTheDocument();
    expect(container).toHaveTextContent('MUST-OWN');
    expect(screen.getByRole('link', { name: 'TERMS-OWN' })).toBeInTheDocument();
  });
});

// With no provider at all - a Storybook story, a bare unit test - the Korean
// defaults are still what renders. This is the reason they exist; the cases
// above only move them out of the app's reach.
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
      </>,
    );
    expect(screen.getByRole('button', { name: ko['decision.keep'] })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ko['tripAdd.idle'] })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: ko['nav.back'] })).toBeInTheDocument();
    expect(container).toHaveTextContent(ko['mustVisit.badge']);
    expect(screen.getByRole('link', { name: ko['license.terms'] })).toBeInTheDocument();
  });
});
