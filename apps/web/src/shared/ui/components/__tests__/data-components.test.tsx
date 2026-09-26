import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../../i18n/I18nProvider.js';
import {
  CrowdLevel,
  DataAttribution,
  DecisionBar,
  MetricDelta,
  type MetricDeltaProps,
  StateLabel,
  type SourceState,
} from '../index.js';

const SEOUL_FULL =
  '출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)';

describe('StateLabel', () => {
  it('gives all six data states their own words', () => {
    const states: SourceState[] = [
      'LIVE',
      'FORECAST',
      'QUALITATIVE',
      'STALE',
      'UNAVAILABLE',
      'REPLAY',
    ];
    const texts = states.map((state) => {
      const { container, unmount } = render(<StateLabel state={state} />);
      const text = container.textContent ?? '';
      unmount();
      return text;
    });
    expect(new Set(texts).size).toBe(states.length);
  });

  it('never presents replay as live', () => {
    render(<StateLabel state="REPLAY" />);
    const text = screen.getByText(/과거 관측 재생/).textContent ?? '';
    expect(text).toContain('실시간 아님');
  });

  // A-068 (owner, 2026-09-25): a live reading the provider has flagged with an
  // incident is not called live.
  it('FE-403-T1 does not call a reading live while its provider reports an incident', () => {
    const { container } = render(
      <StateLabel qualityFlags={['PROVIDER_INCIDENT']} state="LIVE" />,
    );
    expect(container).toHaveTextContent('제공처 장애');
    expect(container).not.toHaveTextContent('실시간 관측');
    expect(container.firstElementChild).toHaveAttribute(
      'data-quality',
      'PROVIDER_INCIDENT',
    );
  });

  it('FE-403-T1 keeps a non-live state its own words under an incident', () => {
    // Replay, stale and the rest already say they are not live.
    const { container } = render(
      <StateLabel qualityFlags={['PROVIDER_INCIDENT']} state="REPLAY" />,
    );
    expect(container).toHaveTextContent('과거 관측 재생 · 실시간 아님');
  });

  it('FE-403-T1 reads other quality flags as nothing to relabel', () => {
    const { container } = render(
      <StateLabel qualityFlags={['SCHEMA_DRIFT']} state="LIVE" />,
    );
    expect(container).toHaveTextContent('실시간 관측');
  });
});

describe('FE-201-T2 FE-202-T2 DataAttribution (FCR-011 trace)', () => {
  const base = {
    attribution: SEOUL_FULL,
    attributionShort: '출처: 서울특별시',
    officialUrl: null,
    licenseUrl: null,
    observedAt: null,
  };

  it('shows the server attribution verbatim', () => {
    render(<DataAttribution provenance={base} />);
    expect(screen.getByText(SEOUL_FULL)).toBeInTheDocument();
  });

  it('uses the short credit only when the server supplies one', () => {
    const { rerender } = render(<DataAttribution provenance={base} compact />);
    expect(screen.getByText('출처: 서울특별시')).toBeInTheDocument();

    // Without a short form it must fall back to the full string, never
    // truncate on its own.
    rerender(
      <DataAttribution provenance={{ ...base, attributionShort: null }} compact />,
    );
    expect(screen.getByText(SEOUL_FULL)).toBeInTheDocument();
  });
});

describe('CrowdLevel', () => {
  // A-060 (owner, 2026-09-20): Seoul's four stages sit on cells 1-4 of the
  // product's five and the fifth stays empty. The server says so in
  // ordinalScale; the bar follows it rather than a source name.
  const SEOUL_SCALE = { size: 5, publishedCells: ['1', '2', '3', '4'] };

  it('FE-403-T4 FE-403-T5 draws a Seoul reading on five cells and leaves the fifth unpublished', () => {
    render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: 'diagnostic',
          ordinalLevel: '3',
          ordinalScale: SEOUL_SCALE,
          value: null,
          unit: null,
          provenance: { source: 'SEOUL_CITYDATA' } as never,
        }}
      />,
    );
    const bar = screen.getByRole('img', { name: '4단계 중 3번째' });
    expect(bar.children).toHaveLength(5);
    expect(bar.querySelectorAll('[data-filled]')).toHaveLength(3);
    // Only the fifth is a cell Seoul never fills, and it is drawn, not dropped.
    const unpublished = bar.querySelectorAll('[data-unpublished]');
    expect(unpublished).toHaveLength(1);
    expect(unpublished[0]).toBe(bar.lastElementChild);
    expect(screen.getByText('3 · 약간 붐빔')).toBeInTheDocument();
  });

  it('FE-403-T5 names a reading by the stages its source publishes, not the cells the bar has', () => {
    // The contract: do not describe a reading as "Nth of five" unless that
    // source publishes five. A source filling three of five cells is "2 of 3".
    render(
      <CrowdLevel
        crowd={{
          state: 'FORECAST',
          label: 'diagnostic',
          ordinalLevel: '2',
          ordinalScale: { size: 5, publishedCells: ['1', '2', '3'] },
          value: null,
          unit: null,
          provenance: { source: 'SOME_THREE_STEP_SOURCE' } as never,
        }}
      />,
    );
    const bar = screen.getByRole('img', { name: '3단계 중 2번째' });
    expect(bar.children).toHaveLength(5);
    expect(bar.querySelectorAll('[data-unpublished]')).toHaveLength(2);
  });

  it('FE-403-T4 FE-403-T5 draws a Seoul top reading short of the last cell', () => {
    render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: 'diagnostic',
          ordinalLevel: '4',
          ordinalScale: SEOUL_SCALE,
          value: null,
          unit: null,
          provenance: { source: 'SEOUL_CITYDATA' } as never,
        }}
      />,
    );
    const bar = screen.getByRole('img', { name: '4단계 중 4번째' });
    expect(bar.querySelectorAll('[data-filled]')).toHaveLength(4);
    expect(bar.lastElementChild).not.toHaveAttribute('data-filled');
    expect(bar.lastElementChild).toHaveAttribute('data-unpublished');
  });

  it('FE-403-T4 FE-403-T5 keeps a Seoul reading on five cells when the scale is missing', () => {
    // A server without the descriptor still may not give Seoul a fifth stage.
    render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: 'diagnostic',
          ordinalLevel: '2',
          value: null,
          unit: null,
          provenance: { source: 'SEOUL_CITYDATA' } as never,
        }}
      />,
    );
    const bar = screen.getByRole('img', { name: '4단계 중 2번째' });
    expect(bar.children).toHaveLength(5);
    expect(bar.querySelectorAll('[data-unpublished]')).toHaveLength(1);
  });

  it('FE-403-T4 does not render a fifth Seoul stage the scale does not publish', () => {
    render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: 'diagnostic',
          ordinalLevel: '5',
          ordinalScale: SEOUL_SCALE,
          value: null,
          unit: null,
          provenance: { source: 'SEOUL_CITYDATA' } as never,
        }}
      />,
    );
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('takes its wording from the caller when given', () => {
    // A caller's words win over both the locale and the Korean defaults, which
    // are only for a render with no provider at all.
    render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: '4 · 혼잡',
          ordinalLevel: '4',
          value: null,
          unit: null,
          provenance: {} as never,
        }}
        levelLabel="Level 4 of 5"
        levelLabels={{ 4: 'Crowded' }}
        stateLabels={{ LIVE: 'Observed live' }}
      />,
    );
    expect(screen.getByText('Observed live')).toBeInTheDocument();
    expect(screen.queryByText('실시간 관측')).toBeNull();
    expect(screen.getByRole('img', { name: 'Level 4 of 5' })).toBeInTheDocument();
    expect(screen.getByText('4 · Crowded')).toBeInTheDocument();
  });

  it('says data is missing instead of drawing an empty bar', () => {
    render(<CrowdLevel crowd={null} unavailableReason="관측 권역 밖이에요" />);
    expect(screen.getByText('현재 데이터 없음')).toBeInTheDocument();
    expect(screen.getByText('관측 권역 밖이에요')).toBeInTheDocument();
  });

  it('states the level in words, not only as bars', () => {
    render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: '4 · 혼잡',
          ordinalLevel: '4',
          value: null,
          unit: null,
          provenance: {} as never,
        }}
      />,
    );
    // The words come from the FE-owned five-stage vocabulary, not `label`.
    // The contract calls label diagnostic and it may be in the source locale.
    expect(screen.getByText('4 · 혼잡')).toBeInTheDocument();
    expect(screen.getByText('실시간 관측')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '5단계 중 4번째' })).toBeInTheDocument();
  });

  it('renders the fifth contract level instead of dropping it', () => {
    const { container } = render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: '5 · 매우 혼잡',
          ordinalLevel: '5',
          value: null,
          unit: null,
          provenance: {} as never,
        }}
      />,
    );

    expect(screen.getByRole('img', { name: '5단계 중 5번째' })).toBeInTheDocument();
    expect(screen.getByText('5 · 매우 혼잡')).toBeInTheDocument();
    expect(container.querySelectorAll('[data-filled]')).toHaveLength(5);
  });

  it.each(['05', '5.0', '5e0', ' 5 '])(
    'rejects malformed level token %j instead of coercing it to five',
    (ordinalLevel) => {
      render(
        <CrowdLevel
          crowd={{
            state: 'LIVE',
            label: 'invalid level',
            ordinalLevel,
            value: null,
            unit: null,
            provenance: {} as never,
          }}
        />,
      );

      expect(screen.queryByRole('img')).toBeNull();
    },
  );
});

describe('MetricDelta', () => {
  it('hides the number when the server says the pair is not comparable', () => {
    render(
      <MetricDelta
        label="이동시간 변화"
        eligible={false}
        value="18분"
        reason="경로 provider 미정 · 확인 불가"
      />,
    );
    expect(screen.queryByText(/18분/)).not.toBeInTheDocument();
    expect(screen.getByText('경로 provider 미정 · 확인 불가')).toBeInTheDocument();
  });

  it('shows the value only when eligible', () => {
    render(
      <MetricDelta
        label="혼잡 단계"
        eligible
        value="4 · 혼잡 → 1 · 매우 여유"
        direction="improved"
      />,
    );
    expect(screen.getByText('4 · 혼잡 → 1 · 매우 여유')).toBeInTheDocument();
  });

  it('cannot be told a pair is not comparable without being told why', () => {
    // A type-level check, run by `tsc` (this file is in the typecheck
    // program): with `eligible: false` the reason is required. It used to be
    // optional behind a Korean fallback no caller reached, which any caller
    // that forgot it would have put on an English screen. If the reason
    // becomes optional again this directive is unused and `tsc` fails.
    // @ts-expect-error -- a refused comparison must carry its reason
    const refused: MetricDeltaProps = { label: 'Crowd', eligible: false };
    expect(refused.eligible).toBe(false);
  });
});

describe('DecisionBar', () => {
  it('always offers a way to decline in preview', () => {
    render(<DecisionBar state="preview" />);
    expect(screen.getByRole('button', { name: '현재 일정 유지' })).toBeEnabled();
  });

  it('states that a failure left the schedule untouched', () => {
    render(<DecisionBar state="failed" />);
    expect(screen.getByText(/일정은 바뀌지 않았어요/)).toBeInTheDocument();
  });

  it('requires a recompute for a stale run rather than applying it', () => {
    render(<DecisionBar state="stale" />);
    expect(
      screen.getByRole('button', { name: '최신 일정으로 다시 계산' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '이 변경 적용' }),
    ).not.toBeInTheDocument();
  });
});

// Inside the app the words are the chosen locale's, whether or not the caller
// passed its own. A caller that forgot one key - the feed's state list had no
// PROVIDER_INCIDENT - used to fall through to the Korean defaults below,
// which only a render with no I18nProvider (a bare unit test; the Storybook
// stories run inside one) should see.
describe('inside the app, crowd words come from the locale', () => {
  afterEach(() => {
    localStorage.removeItem('nullnull.locale');
  });
  const english = ({ children }: { children: ReactNode }) => {
    localStorage.setItem('nullnull.locale', 'en-US');
    return <I18nProvider>{children}</I18nProvider>;
  };

  it('FE-403-T1 says a provider incident in English with no labels passed', () => {
    render(<StateLabel qualityFlags={['PROVIDER_INCIDENT']} state="LIVE" />, {
      wrapper: english,
    });
    expect(screen.getByText('Provider incident')).toBeInTheDocument();
    expect(screen.queryByText('제공처 장애')).not.toBeInTheDocument();
  });

  it('FE-403-T5 names and words a Seoul reading in English with no labels passed', () => {
    render(
      <CrowdLevel
        crowd={{
          state: 'LIVE',
          label: 'diagnostic',
          ordinalLevel: '3',
          ordinalScale: { size: 5, publishedCells: ['1', '2', '3', '4'] },
          value: null,
          unit: null,
          provenance: { source: 'SEOUL_CITYDATA', qualityFlags: [] } as never,
        }}
      />,
      { wrapper: english },
    );
    expect(
      screen.getByRole('img', { name: 'Seoul crowd level 3 of 4' }),
    ).toBeInTheDocument();
    expect(screen.getByText('3 · Slightly crowded')).toBeInTheDocument();
    expect(screen.getByText('Observed live')).toBeInTheDocument();
  });

  it('FE-403-T5 names and words a five-step reading in English with no labels passed', () => {
    render(
      <CrowdLevel
        crowd={{
          state: 'FORECAST',
          label: 'diagnostic',
          ordinalLevel: '4',
          value: null,
          unit: null,
          provenance: { source: 'KTO_CONCENTRATION_FORECAST', qualityFlags: [] } as never,
        }}
      />,
      { wrapper: english },
    );
    expect(screen.getByRole('img', { name: 'Level 4 of 5' })).toBeInTheDocument();
    expect(screen.getByText('4 · Crowded')).toBeInTheDocument();
  });
});
