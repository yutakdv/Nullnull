// FE-404 acceptance (FR-DAT-01).
//
// FE-404-T1: the six data states are distinguished, and no missing value is
//            filled in with a zero or an average.
// FE-404-T2: the screen renders without an API, so it has no loading/error path
//            of its own; the states below are its content.
// FE-404-T3: headings and list structure are reachable.
//
// The state list is read from the OpenAPI SourceState enum rather than written
// out here: a state added to the contract must fail this test, not slip past a
// hand-kept copy.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { render, screen } from '@testing-library/react';
import { load } from 'js-yaml';
import { describe, expect, it } from 'vitest';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import { DataGuideScreen } from '../DataGuideScreen.js';

const copy = messages['en-US'];

const spec = load(
  readFileSync(resolve(process.cwd(), '../../docs/api/openapi.yaml'), 'utf8'),
) as { components: { schemas: { SourceState: { enum: string[] } } } };
const contractStates = spec.components.schemas.SourceState.enum;

function renderGuide() {
  return render(
    <I18nProvider>
      <DataGuideScreen />
    </I18nProvider>,
  );
}

describe('the data guide explains every state the contract can return', () => {
  it('covers each SourceState in the OpenAPI enum', () => {
    renderGuide();
    // Six in the contract today; the assertion is on the contract, not the six.
    expect(contractStates.length).toBeGreaterThan(0);
    for (const state of contractStates) {
      const key = `dataGuide.state.${state}` as keyof typeof copy;
      expect(copy[key], `no copy for SourceState ${state}`).toBeDefined();
      expect(screen.getByText(copy[key])).toBeInTheDocument();
    }
  });

  it('names each state, so colour is never the only signal', () => {
    renderGuide();
    // StateLabel owns the wording; these are the labels it renders.
    for (const label of [
      '실시간 관측',
      '공식 혼잡 예측',
      '공식 혼잡 예측 범위 밖',
      '업데이트 지연',
      '현재 데이터 없음',
      '과거 관측 재생 · 실시간 아님',
    ]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
  });

  it('says replay is not live, rather than dressing it as live', () => {
    renderGuide();
    expect(screen.getByText(copy['dataGuide.state.REPLAY'])).toHaveTextContent(
      /Not live/i,
    );
  });

  it('states that no number is invented when there is no basis', () => {
    renderGuide();
    expect(screen.getByText(copy['dataGuide.state.UNAVAILABLE'])).toHaveTextContent(
      /do not invent/i,
    );
  });
});

describe('the guide states the product rules it is there to explain', () => {
  it('separates saved candidates from the itinerary', () => {
    renderGuide();
    expect(screen.getByText(copy['dataGuide.rule1.title'])).toBeInTheDocument();
  });

  it('says the four locks are independent', () => {
    renderGuide();
    expect(screen.getByText(copy['dataGuide.rule2.body'])).toHaveTextContent(/separate/i);
  });

  it('says nothing changes before the user approves', () => {
    renderGuide();
    expect(screen.getByText(copy['dataGuide.rule4.body'])).toHaveTextContent(
      /preview only/i,
    );
  });
});

describe('required attribution and structure', () => {
  it('shows the approved source line', () => {
    renderGuide();
    // Invariant 12: the approved Korean wording, in both locales.
    expect(screen.getByText(/ⓒ한국관광공사/)).toBeInTheDocument();
  });

  it('gives each section a heading its list is labelled by', () => {
    renderGuide();
    const lists = screen.getAllByRole('list');
    expect(lists).toHaveLength(2);
    for (const list of lists) {
      const labelledBy = list.getAttribute('aria-labelledby');
      expect(labelledBy).toBeTruthy();
      expect(document.getElementById(labelledBy ?? '')).toBeInTheDocument();
    }
  });

  it('sends no request: this screen has no API', async () => {
    const { server } = await import('../../../shared/testing/msw/server.js');
    const seen: string[] = [];
    server.events.on('request:start', ({ request }) => seen.push(request.url));
    renderGuide();
    await screen.findByText(copy['dataGuide.attribution']);
    expect(seen).toEqual([]);
    server.events.removeAllListeners();
  });
});
