import { expect, test } from '@playwright/test';
import { createSeededTrip, FIRST_ITEM, SECOND_ITEM } from './seeded-trip.js';

test('A-01 keyboard cancel preserves the server order and Save commits it once', async ({
  page,
}) => {
  const writes: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/items\/reorder$/.test(request.url())) {
      writes.push(request.url());
    }
  });
  const path = await createSeededTrip(page);
  const firstDay = () => page.locator('section[aria-labelledby^="day-"]').first();
  const firstStop = () => firstDay().getByRole('heading', { level: 3 }).first();

  async function stageMove() {
    await page.getByRole('button', { name: 'Edit itinerary' }).click();
    await expect(page).toHaveURL(/\/edit$/);
    await expect(page.getByRole('button', { name: 'Save changes' })).toBeEnabled();
    const menu = page.getByRole('button', { name: `${FIRST_ITEM} item actions` });
    await menu.focus();
    await page.keyboard.press('Enter');
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    const down = page.getByRole('button', { name: `Move ${FIRST_ITEM} down` });
    await down.focus();
    await page.keyboard.press('Enter');
    await expect(firstStop()).toHaveText(SECOND_ITEM);
  }

  await page.goto(path);
  await expect(firstStop()).toHaveText(FIRST_ITEM);
  const canonicalPath = (
    await page.getByRole('link', { name: 'Saved places' }).getAttribute('href')
  )?.replace(/\/candidates$/, '');
  expect(canonicalPath).toMatch(/^\/trip\/[0-9a-f-]+$/i);
  await stageMove();
  const cancel = page.getByRole('button', { name: 'Cancel', exact: true });
  await cancel.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Discard your changes?' });
  await expect(dialog).toBeVisible();
  const discard = dialog.getByRole('button', { name: 'Leave' });
  await discard.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`${canonicalPath}$`));
  await page.reload();
  await expect(firstStop()).toHaveText(FIRST_ITEM);
  expect(writes).toHaveLength(0);

  await stageMove();
  const save = page.getByRole('button', { name: 'Save changes' });
  await save.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`${canonicalPath}$`));
  await expect(firstStop()).toHaveText(SECOND_ITEM);
  expect(writes).toHaveLength(1);
  if (process.env.PLAYWRIGHT_BASE_URL || process.env.WEB_BASE_URL) {
    await page.reload();
    await expect(firstStop()).toHaveText(SECOND_ITEM);
  }
});

test('A-05 a user can set and clear an exact visit time by keyboard', async ({
  page,
}) => {
  const patches: string[] = [];
  page.on('request', (request) => {
    if (
      request.method() === 'PATCH' &&
      /\/trips\/[^/]+\/items\/[^/]+$/.test(request.url())
    ) {
      patches.push(request.postData() ?? '');
    }
  });
  const path = await createSeededTrip(page);
  await page.goto(path);

  async function editTime(value: string) {
    const card = page.getByRole('article').filter({
      has: page.getByRole('heading', { name: FIRST_ITEM }),
    });
    const menu = card.getByRole('button', { name: `${FIRST_ITEM} item actions` });
    if ((await menu.getAttribute('aria-expanded')) !== 'true') {
      await menu.focus();
      await page.keyboard.press('Enter');
    }
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    const open = card.getByRole('button', { name: 'Edit start time' });
    await open.focus();
    await page.keyboard.press('Enter');
    const input = card.getByLabel('Start time (leave blank if unknown)');
    await expect(input).toBeFocused();
    await input.fill(value);
    const save = card.getByRole('button', { name: 'Save time' });
    await save.focus();
    await page.keyboard.press('Enter');
    await expect(input).toHaveCount(0);
    return card;
  }

  let card = await editTime('15:45');
  await expect(card.getByText('3:45 PM')).toBeVisible();
  if (process.env.PLAYWRIGHT_BASE_URL || process.env.WEB_BASE_URL) {
    await page.reload();
    card = page
      .getByRole('article')
      .filter({ has: page.getByRole('heading', { name: FIRST_ITEM }) });
    await expect(card.getByText('3:45 PM')).toBeVisible();
  }
  await editTime('');
  if (process.env.PLAYWRIGHT_BASE_URL || process.env.WEB_BASE_URL) await page.reload();
  await expect(card.getByText('Stop 1')).toBeVisible();
  expect(patches.map((body) => JSON.parse(body))).toEqual([
    { startTime: '15:45:00' },
    { startTime: null },
  ]);
});
