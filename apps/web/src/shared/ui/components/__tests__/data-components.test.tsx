import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import {
  CrowdLevel,
  DataAttribution,
  DecisionBar,
  MetricDelta,
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
});

describe('DataAttribution', () => {
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
  it('takes its wording from the caller when given', () => {
    // How the app localizes it: the Korean defaults stay for Storybook, and
    // the screen passes the selected locale's words in.
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
        levelLabel="Level 4 of 4"
        stateLabels={{ LIVE: 'Observed live' }}
      />,
    );
    expect(screen.getByText('Observed live')).toBeInTheDocument();
    expect(screen.queryByText('실시간 관측')).toBeNull();
    expect(screen.getByRole('img', { name: 'Level 4 of 4' })).toBeInTheDocument();
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
    // The words come from the data state, not from `label`. The contract calls
    // label "diagnostic, not display copy: it is not localized", and says to
    // build user-facing wording from `state` and `provenance.metricDefinition`
    // — so rendering it was showing the user an internal metric name in the
    // source's language whatever locale they chose.
    expect(screen.queryByText('4 · 혼잡')).toBeNull();
    expect(screen.getByText('실시간 관측')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '4단계 중 4번째' })).toBeInTheDocument();
  });
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
