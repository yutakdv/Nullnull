// @vitest-environment happy-dom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { crowdFixtures } from '@nullnull/contracts';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import {
  CrowdForecastCardReading,
  CrowdForecastQueryState,
} from '../CrowdForecastReading.js';

function renderSeries(series: Parameters<typeof CrowdForecastCardReading>[0]['series']) {
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

  it('maps an unknown unavailable token to generic copy without exposing it', () => {
    renderSeries({
      ...crowdFixtures.seriesUnavailable,
      unavailableReason: 'FUTURE_REASON',
    });
    expect(screen.getByText('Crowd forecast is unavailable')).toBeInTheDocument();
    expect(screen.queryByText('FUTURE_REASON')).toBeNull();
  });

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
