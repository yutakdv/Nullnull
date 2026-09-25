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
import { render, screen, within } from '@testing-library/react';
import { load } from 'js-yaml';
import { afterEach, describe, expect, it } from 'vitest';
import { RouterProvider, createMemoryRouter } from 'react-router';
import { I18nProvider } from '../../../i18n/I18nProvider.js';
import { messages } from '../../../i18n/messages.js';
import * as contracts from '@nullnull/contracts';
import { DataGuideScreen } from '../DataGuideScreen.js';
import { GUIDE_SOURCES } from '../guide-sources.js';

const copy = messages['en-US'];

const spec = load(
  readFileSync(resolve(process.cwd(), '../../docs/api/openapi.yaml'), 'utf8'),
) as { components: { schemas: { SourceState: { enum: string[] } } } };
const contractStates = spec.components.schemas.SourceState.enum;

// The credits this static screen draws, checked against what the server
// actually sends for each source: the approved examples in packages/contracts
// (kept truthful to the server since #390) and, for the English text source
// that no example carries yet, its registry migration (V050). The screen calls
// no API, so without this a URL or a credit could drift from the server's
// source registry and nothing would notice.
function exampleProvenance(source: string) {
  const found = new Map<
    string,
    {
      attribution: string;
      officialUrl: string;
      licenseUrl: string;
      sourceDisplayName: string;
    }
  >();
  const walk = (node: unknown): void => {
    if (Array.isArray(node)) node.forEach(walk);
    else if (node && typeof node === 'object') {
      const record = node as Record<string, unknown>;
      if (record.source === source && typeof record.officialUrl === 'string') {
        found.set(record.officialUrl, record as never);
      }
      Object.values(record).forEach(walk);
    }
  };
  walk(Object.values(contracts));
  return [...found.values()];
}
const migrations = resolve(process.cwd(), '../api/src/main/resources/db/migration');

function renderGuide() {
  // Wrapped in a router: the screen's NavBar navigates to a named destination
  // rather than calling history.go(-1), so it needs the router context.
  const router = createMemoryRouter(
    [{ path: '/about-data', element: <DataGuideScreen /> }],
    { initialEntries: ['/about-data'] },
  );
  return render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
}

// The locale is stored, so a test that sets it must put it back or every
// later test in this file inherits it.
afterEach(() => {
  localStorage.clear();
});

describe('the screen speaks one language at a time', () => {
  it('renders the state labels in the selected locale, not always Korean', async () => {
    // The defect this guards: StateLabel keeps Korean defaults so Storybook
    // can mount it without a provider, and this screen did not pass the
    // localized set — so an English reader saw "실시간 관측" welded to the
    // English sentence explaining it.
    localStorage.setItem('nullnull.locale', 'en-US');
    renderGuide();
    await screen.findByRole('heading', { level: 1 });
    const copyEn = messages['en-US'];
    expect(screen.getByText(copyEn['state.LIVE'])).toBeInTheDocument();
    expect(screen.getByText(copyEn['state.UNAVAILABLE'])).toBeInTheDocument();
    expect(screen.queryByText(messages['ko-KR']['state.LIVE'])).toBeNull();
  });

  it('still renders the pinned Korean wording in Korean', async () => {
    // Figma pins these strings ("문구 임의 변경 금지"), so ko-KR must match the
    // component's own defaults exactly.
    localStorage.setItem('nullnull.locale', 'ko-KR');
    renderGuide();
    await screen.findByRole('heading', { level: 1 });
    expect(screen.getByText(messages['ko-KR']['state.LIVE'])).toBeInTheDocument();
  });
});

describe('FE-404-T1 data guide source states (FCR-007 trace)', () => {
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
    // Read from the message table rather than repeated literals: the wording
    // is pinned by Figma, and a second hand-typed copy is a second thing to
    // keep in sync — which is exactly what this screen's own comment forbids.
    for (const state of contractStates) {
      const key = `state.${state}` as keyof typeof copy;
      expect(copy[key], `no label for SourceState ${state}`).toBeDefined();
      expect(screen.getByText(copy[key])).toBeInTheDocument();
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

  // A-074 (owner, 2026-09-25): the 24-hour undo entry left the trip screen
  // (93de5679) and is retired, so this guide may not promise it.
  it('FE-404-T5 promises no undo the app does not offer', () => {
    renderGuide();
    expect(screen.getByText(copy['dataGuide.rule4.body'])).not.toHaveTextContent(
      /revert|undo/i,
    );
    expect(messages['ko-KR']['dataGuide.rule4.body']).not.toContain('되돌');
  });
});

describe('required attribution and structure', () => {
  it('FE-404-T4 credits each source on its own line, linked to its official page and licence', () => {
    renderGuide();
    for (const credit of GUIDE_SOURCES) {
      const links = screen
        .getAllByRole('link', { name: credit.attribution })
        .filter((link) => link.getAttribute('href') === credit.officialUrl);
      // One line per source: its own words, linked to its own page.
      expect(links, `${credit.source} credit`).toHaveLength(1);
      const line = links[0]?.closest('p');
      expect(line).not.toBeNull();
      expect(
        within(line as HTMLElement).getByRole('link', { name: copy['license.terms'] }),
      ).toHaveAttribute('href', credit.licenseUrl);
    }
    // Never two providers merged into one credit.
    expect(screen.queryByText(/ⓒ한국관광공사\s*·\s*서울/)).toBeNull();
  });

  it('FE-404-T4 names the dataset beside each of the KTO credits that read alike', () => {
    renderGuide();
    const kto = GUIDE_SOURCES.filter(
      (credit) => credit.attribution === '출처: ⓒ한국관광공사',
    );
    expect(kto.length).toBeGreaterThan(1);
    for (const credit of kto) {
      expect(screen.getByText(credit.sourceDisplayName)).toBeInTheDocument();
    }
  });

  it('FE-404-T4 credits exactly what the server sends for each source', () => {
    for (const credit of GUIDE_SOURCES) {
      if (credit.source === 'KTO_ENG_SERVICE') {
        const sql = readFileSync(
          resolve(migrations, 'V050__kto_eng_service_text_source.sql'),
          'utf8',
        );
        for (const value of [
          credit.sourceDisplayName,
          credit.officialUrl,
          credit.licenseUrl,
          credit.attribution,
        ]) {
          expect(sql, `${credit.source} ${value}`).toContain(`'${value}'`);
        }
        continue;
      }
      const examples = exampleProvenance(credit.source).filter(
        (example) => example.officialUrl === credit.officialUrl,
      );
      expect(
        examples,
        `${credit.source} has an approved example with this URL`,
      ).not.toHaveLength(0);
      for (const example of examples) {
        expect(example.attribution).toBe(credit.attribution);
        expect(example.licenseUrl).toBe(credit.licenseUrl);
        expect(example.sourceDisplayName).toBe(credit.sourceDisplayName);
      }
    }
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
    await screen.findAllByRole('link', { name: copy['license.terms'] });
    expect(seen).toEqual([]);
    server.events.removeAllListeners();
  });
});
