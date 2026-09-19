// @vitest-environment happy-dom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { crowdFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import type { SupportedLocale } from '../../../i18n/locales.js';
import {
  CrowdForecastCardReading,
  CrowdForecastQueryState,
} from '../CrowdForecastReading.js';

function renderSeries(
  series: Parameters<typeof CrowdForecastCardReading>[0]['series'],
  locale: SupportedLocale = 'en-US',
) {
  localStorage.setItem('nullnull.locale', locale);
  return render(
    <I18nProvider>
      <CrowdForecastCardReading series={series} />
    </I18nProvider>,
  );
}

describe('#105 FCR-029/FCR-036 card crowd copy', () => {
  it('keeps the maximum point, date, state and provenance together', () => {
    renderSeries(crowdFixtures.seriesForecast);
    expect(screen.getByText('Relative concentration 72.5')).toBeInTheDocument();
    expect(screen.getByText('Forecast for 10/6')).toBeInTheDocument();
    expect(screen.getByText('Official crowd forecast')).toBeInTheDocument();
    expect(screen.getByText('출처: ⓒ한국관광공사')).toBeInTheDocument();
  });

  it.each([
    ['ko-KR', 'NO_COVERAGE', messages['ko-KR']['crowd.unavailable.NO_COVERAGE']],
    ['en-US', 'NO_COVERAGE', messages['en-US']['crowd.unavailable.NO_COVERAGE']],
    [
      'ko-KR',
      'PLACE_UNAVAILABLE',
      messages['ko-KR']['crowd.unavailable.PLACE_UNAVAILABLE'],
    ],
    [
      'en-US',
      'PLACE_UNAVAILABLE',
      messages['en-US']['crowd.unavailable.PLACE_UNAVAILABLE'],
    ],
    ['ko-KR', 'FUTURE_REASON', messages['ko-KR']['crowd.unavailable.unknown']],
    ['en-US', 'FUTURE_REASON', messages['en-US']['crowd.unavailable.unknown']],
  ] as const)(
    'maps %s %s to localized copy without exposing the token',
    (locale, unavailableReason, expected) => {
      renderSeries({ ...crowdFixtures.seriesUnavailable, unavailableReason }, locale);
      expect(screen.getByText(expected)).toBeInTheDocument();
      expect(screen.queryByText(unavailableReason)).toBeNull();
    },
  );

  it('labels fetched time as fetched when the provider supplied no observed time', () => {
    renderSeries(crowdFixtures.seriesStale);
    expect(screen.getByText(/Fetched /)).toBeInTheDocument();
    expect(screen.queryByText(/Observed /)).toBeNull();
    expect(screen.getByText('Update delayed')).toBeInTheDocument();
  });

  it('labels a provider reference time as observed rather than fetched', () => {
    const point = crowdFixtures.seriesForecast.points[0];
    if (!point) throw new Error('forecast fixture changed');
    renderSeries({
      ...crowdFixtures.seriesForecast,
      points: [
        {
          ...point,
          provenance: { ...point.provenance, observedAt: '2026-10-02T01:35:00Z' },
        },
      ],
    });
    expect(screen.getByText(/Observed /)).toBeInTheDocument();
    expect(screen.queryByText(/Fetched /)).toBeNull();
  });

  it('does not announce loading for a disabled query state', () => {
    render(
      <I18nProvider>
        <CrowdForecastQueryState failed={false} loading={false} series={undefined} />
      </I18nProvider>,
    );
    expect(screen.queryByText('Loading crowd forecast')).toBeNull();
  });
});
