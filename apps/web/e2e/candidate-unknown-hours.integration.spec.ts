import { expect, test } from '@playwright/test';

const PLACE_ID = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01';

test('FE-303-T2 an unknown opening time still lets the owner choose a day by keyboard', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 780 });
  await page.addInitScript(() => localStorage.setItem('nullnull.locale', 'ko-KR'));
  await page.goto('/');
  await page.waitForURL(/\/(language|feed)$/, { timeout: 15_000 });

  const day = (offset: number) => {
    const date = new Date();
    date.setUTCDate(date.getUTCDate() + offset);
    return date.toISOString().slice(0, 10);
  };
  const dates = { startDate: day(14), endDate: day(16) };
  const tripId = await page.evaluate(
    async ({ startDate, endDate, placeId }) => {
      const csrfResponse = await fetch('/api/v1/session/csrf', { method: 'POST' });
      if (!csrfResponse.ok) throw new Error(`csrf status ${csrfResponse.status}`);
      const { csrfToken } = (await csrfResponse.json()) as { csrfToken: string };
      const headers = {
        'Content-Type': 'application/json',
        'X-CSRF-Token': csrfToken,
        'Idempotency-Key': crypto.randomUUID(),
      };
      const created = await fetch('/api/v1/trips', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          startDate,
          endDate,
          timezone: 'Asia/Seoul',
          planningLevel: 'MUST_VISIT_ONLY',
          interests: [],
        }),
      });
      if (created.status !== 201) throw new Error(`create trip status ${created.status}`);
      const { id } = (await created.json()) as { id: string };
      const saved = await fetch(`/api/v1/trips/${id}/candidates`, {
        method: 'POST',
        headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ placeId, source: { type: 'SEARCH' }, mustVisit: true }),
      });
      if (saved.status !== 201) throw new Error(`save candidate status ${saved.status}`);
      return id;
    },
    { ...dates, placeId: PLACE_ID },
  );

  await page.goto(`/trip/${tripId}`);
  await expect(page.getByText('아직 일정이 없어요', { exact: true })).toBeVisible();
  const savedPlaces = page.getByRole('link', {
    name: '담아둔 장소에서 날짜 고르기',
  });
  await expect(savedPlaces).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBe(true);
  await savedPlaces.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`/trip/${tripId}/candidates$`));
  const card = page.getByRole('article').filter({ hasText: '경복궁' });
  await expect(card).toContainText('판단할 근거가 부족해요. 날짜를 직접 골라주세요');
  const trigger = card.getByRole('button', { name: '일정에 추가' });
  await trigger.focus();
  await page.keyboard.press('Enter');

  const sheet = page.getByRole('dialog', { name: '어느 날에 추가할까요?' });
  await expect(sheet).toBeVisible();
  const rows = sheet.getByRole('list', { name: '날짜 선택' }).getByRole('button');
  await expect(rows).toHaveCount(3);
  await expect(rows.first()).toBeEnabled();
  await expect(sheet.getByRole('button', { name: '취소' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(rows.first()).toBeFocused();
  await page.keyboard.press('Enter');

  await expect(card).toContainText('이미 일정에 있어요');
  await expect(
    card.getByRole('button', { name: '경복궁 담아둔 장소에서 제거' }),
  ).toBeFocused();
  await page.goto(`/trip/${tripId}`);
  await expect(page.getByText('경복궁')).toBeVisible();
});
