import { expect, test, type Locator, type Page } from '@playwright/test';
import { messages } from '../src/i18n/messages.js';

const copy = messages['en-US'];
const tripId = '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01';

async function tabTo(page: Page, target: Locator) {
  for (let press = 0; press < 80; press += 1) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  throw new Error('Recovery action was not reachable by keyboard');
}

test('A-04 missing routes leave every date disabled and expose a keyboard recovery path', async ({
  page,
}) => {
  await page.addInitScript(() => {
    localStorage.setItem('nullnull.locale', 'en-US');
    localStorage.setItem('a04-item-writes', '0');
    const nativeFetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const input = args[0];
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const path = new URL(url, location.origin).pathname;
      const method = args[1]?.method ?? (input instanceof Request ? input.method : 'GET');
      if (method === 'POST' && /\/trips\/[^/]+\/items$/.test(path)) {
        localStorage.setItem(
          'a04-item-writes',
          String(Number(localStorage.getItem('a04-item-writes') ?? '0') + 1),
        );
      }
      const response = await nativeFetch(...args);
      if (!/\/candidates\/[^/]+\/matches$/.test(path) || !response.ok) return response;
      const body = (await response.clone().json()) as {
        candidateId: string;
        slots: Array<{
          date: string;
          eligible: boolean;
          suggestedTime: string | null;
          reasonCode: string | null;
        }>;
      };
      return new Response(
        JSON.stringify({
          ...body,
          state: 'UNKNOWN',
          slots: body.slots.map((slot) => ({
            ...slot,
            eligible: false,
            suggestedTime: null,
            reasonCode: 'ROUTE_EVIDENCE_MISSING',
          })),
        }),
        { status: response.status, headers: response.headers },
      );
    };
  });

  await page.goto(`/trip/${tripId}/candidates`);
  const card = page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: '연희동 카페거리' }) });
  const open = card.getByRole('button', { name: copy['candidates.add'], exact: true });
  await expect(open).toBeVisible();
  await tabTo(page, open);
  await page.keyboard.press('Enter');

  const sheet = card.getByRole('dialog', { name: copy['candidates.sheet.title'] });
  await expect(sheet).toBeVisible();
  const dates = sheet.getByRole('list', { name: copy['candidates.pickDate'] });
  const rows = dates.getByRole('button');
  expect(await rows.count()).toBeGreaterThan(0);
  for (const row of await rows.all()) {
    await expect(row).toBeDisabled();
    await expect(row).toContainText(
      copy['candidates.sheet.blocked.ROUTE_EVIDENCE_MISSING'],
    );
  }
  await expect(sheet.getByText(copy['candidates.sheet.allBlocked'])).toBeVisible();
  const manual = sheet.getByRole('link', { name: copy['trip.addPlace'] });
  await tabTo(page, manual);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`/trip/${tripId}/add-place$`));
  expect(await page.evaluate(() => localStorage.getItem('a04-item-writes'))).toBe('0');
});

test('A-04 a disabled replacement source explains the empty sheet and keeps the trip', async ({
  page,
}) => {
  await page.addInitScript(() => {
    localStorage.setItem('nullnull.locale', 'en-US');
    localStorage.setItem('a04-replace-writes', '0');
    const nativeFetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const input = args[0];
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      const path = new URL(url, location.origin).pathname;
      const method = args[1]?.method ?? (input instanceof Request ? input.method : 'GET');
      if (method === 'POST' && /\/trips\/[^/]+\/items\/[^/]+\/replace$/.test(path)) {
        localStorage.setItem(
          'a04-replace-writes',
          String(Number(localStorage.getItem('a04-replace-writes') ?? '0') + 1),
        );
      }
      const response = await nativeFetch(...args);
      if (!/\/places\/[^/]+\/related$/.test(path) || !response.ok) return response;
      const body = (await response.clone().json()) as { sourcePlaceId: string };
      return new Response(
        JSON.stringify({
          sourcePlaceId: body.sourcePlaceId,
          state: 'UNKNOWN',
          reason: 'SOURCE_DISABLED',
          items: [],
        }),
        { status: response.status, headers: response.headers },
      );
    };
  });

  await page.goto(`/trip/${tripId}`);
  const card = page.getByRole('article').filter({
    has: page.getByRole('heading', { name: '경복궁' }),
  });
  const menu = card.getByRole('button', {
    name: copy['trip.item.actions'].replace('{name}', '경복궁'),
  });
  await expect(menu).toBeVisible();
  await tabTo(page, menu);
  await page.keyboard.press('Enter');
  const replace = card.getByRole('button', {
    name: copy['replace.open'].replace('{name}', '경복궁'),
  });
  await tabTo(page, replace);
  await page.keyboard.press('Enter');

  const sheet = page.getByRole('dialog', { name: copy['replace.title'] });
  await expect(sheet).toBeVisible();
  await expect(sheet.getByText(copy['replace.state.SOURCE_DISABLED'])).toBeVisible();
  await expect(sheet.getByText(copy['replace.emptyRecovery'])).toBeVisible();
  await expect(sheet.getByRole('button', { name: copy['replace.confirm'] })).toHaveCount(
    0,
  );
  const manual = sheet.getByRole('link', { name: copy['trip.addPlace'] });
  await tabTo(page, manual);
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`/trip/${tripId}/add-place$`));
  expect(await page.evaluate(() => localStorage.getItem('a04-replace-writes'))).toBe('0');
});
