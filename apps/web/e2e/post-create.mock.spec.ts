import { createServer, type Server } from 'node:http';
import { expect, test } from '@playwright/test';

let uploadServer: Server;
let uploadUrl = '';
const storageRequests: string[] = [];
test.beforeAll(async () => {
  uploadServer = createServer((request, response) => {
    response.setHeader('Access-Control-Allow-Origin', 'http://127.0.0.1:5173');
    response.setHeader('Access-Control-Allow-Methods', 'PUT');
    response.setHeader('Access-Control-Allow-Headers', 'content-type');
    if (request.method === 'OPTIONS') {
      response.writeHead(204).end();
      return;
    }
    storageRequests.push(request.method ?? '');
    if (request.headers['x-csrf-token'] || request.headers.cookie) {
      response.writeHead(403).end();
      return;
    }
    request.resume();
    request.on('end', () => response.writeHead(200).end());
  });
  await new Promise<void>((resolve) => uploadServer.listen(0, '127.0.0.1', resolve));
  const address = uploadServer.address();
  if (!address || typeof address === 'string') throw new Error('Missing upload server');
  uploadUrl = `http://127.0.0.1:${address.port}/upload`;
});
test.afterAll(async () => {
  await new Promise<void>((resolve, reject) =>
    uploadServer.close((error) => (error ? reject(error) : resolve())),
  );
});

const postId = '018f5b00-0000-7000-8000-000000000001';
for (const locale of ['ko-KR', 'en-US'] as const) {
  test(`FE-P1-104-T2 FE-P1-104-T3 FE-P1-104-T5 FE-P1-104-T9 photo upload, keyboard publish, and retry in ${locale}`, async ({
    page,
  }) => {
    const ko = locale === 'ko-KR';
    storageRequests.length = 0;
    await page.setViewportSize({ width: ko ? 360 : 180, height: 800 });
    await page.addInitScript(
      (locale) => localStorage.setItem('nullnull.locale', locale),
      locale,
    );
    await page.addInitScript(
      ({ locale, postId, uploadUrl }) => {
        localStorage.setItem('nullnull.locale', locale);
        const original = window.fetch.bind(window);
        let failed = false;
        const attempts: string[] = [];
        window.fetch = async (...args) => {
          const request = new Request(...args);
          const path = new URL(request.url).pathname;
          if (path === '/api/v1/places/search' && request.method === 'POST') {
            return Response.json({
              items: [
                {
                  id: '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01',
                  name: '경복궁',
                  sourceAttribution: null,
                },
              ],
              page: { nextCursor: null, hasMore: false },
            });
          }
          if (path === '/api/v1/posts/images/uploads') {
            const body = (await request.json()) as {
              checksumSha256: string;
              contentLength: number;
              contentType: string;
            };
            if (
              !/^[0-9a-f]{64}$/.test(body.checksumSha256) ||
              body.contentLength < 1 ||
              body.contentType !== 'image/png'
            )
              throw new Error('Invalid reservation');
            return Response.json(
              {
                uploadId: '018f5b00-0000-7000-8000-000000000099',
                url: uploadUrl,
                method: 'PUT',
                headers: { 'Content-Type': 'image/png' },
                expiresAt: '2099-01-01T00:00:00Z',
              },
              { status: 201 },
            );
          }
          if (path === '/api/v1/posts' && request.method === 'POST') {
            const fingerprint = `${request.headers.get('Idempotency-Key')}:${await request.text()}`;
            attempts.push(fingerprint);
            if (!failed) {
              failed = true;
              throw new TypeError('Response lost');
            }
            if (attempts[0] !== fingerprint) throw new Error('Retry changed payload');
            return Response.json({ postId }, { status: 201 });
          }
          return original(...args);
        };
      },
      { locale, postId, uploadUrl },
    );
    await page.goto('/feed');
    const entry = page.getByRole('link', { name: ko ? '게시물 작성' : 'Create post' });
    const searchButton = page.getByRole('button', { name: ko ? '검색' : 'Search' });
    const entryBox = await entry.boundingBox();
    const searchBox = await searchButton.boundingBox();
    expect(entryBox?.width).toBeGreaterThanOrEqual(44);
    expect(entryBox?.height).toBeGreaterThanOrEqual(44);
    expect(searchBox?.width).toBeGreaterThanOrEqual(44);
    expect(searchBox?.height).toBeGreaterThanOrEqual(44);
    expect(searchBox!.x - (entryBox!.x + entryBox!.width)).toBeGreaterThanOrEqual(8);
    await expect(
      page.getByText(ko ? '피드 검색 기능을 준비 중이에요' : 'Search is coming soon'),
    ).toHaveCount(0);
    await entry.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/posts\/new$/);
    const mainBottom =
      (await page.locator('main').boundingBox())!.y +
      (await page.locator('main').boundingBox())!.height;
    const steps = page.locator('ol > li');
    await expect(steps).toHaveCount(3);
    expect(await steps.first().evaluate((item) => getComputedStyle(item).fontSize)).toBe(
      '17px',
    );
    await expect(steps.first()).toHaveAttribute('aria-current', 'step');
    await expect(
      page.locator('section[aria-labelledby="author-heading"] > form > h2'),
    ).toHaveCount(0);
    await expect(
      page.locator('section[aria-labelledby="author-heading"] legend'),
    ).toHaveCount(0);
    const photoNext = page.getByRole('button', { name: ko ? '글 작성' : 'Write post' });
    const photoNextBox = await photoNext.boundingBox();
    expect(photoNextBox!.y + photoNextBox!.height).toBeGreaterThan(mainBottom - 80);
    const backStyle = await page
      .getByRole('button', {
        name: ko ? '피드로 돌아가기' : 'Back to feed',
      })
      .evaluate((button) => ({
        background: getComputedStyle(button).backgroundColor,
        color: getComputedStyle(button).color,
      }));
    expect(backStyle.background).toBe('rgba(0, 0, 0, 0)');
    expect(backStyle.color).toBe(
      await page
        .locator('header [class*="title"]')
        .evaluate((title) => getComputedStyle(title).color),
    );
    const image = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZc8AAAAASUVORK5CYII=',
      'base64',
    );
    await page
      .locator('input[type=file]')
      .setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: image });
    await expect(
      page.getByText(
        ko
          ? '업로드 완료 · 게시하면 공개돼요.'
          : 'Upload complete. Your photo becomes public when you publish.',
      ),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: ko ? '업로드' : 'Upload', exact: true }),
    ).toHaveCount(0);
    await page.getByRole('button', { name: ko ? '글 작성' : 'Write post' }).click();
    await expect(steps.nth(1)).toBeFocused();
    await page.getByLabel(ko ? '제목' : 'Title', { exact: true }).fill('Quiet afternoon');
    await page
      .getByLabel(ko ? '캡션' : 'Caption', { exact: true })
      .fill('A walk in Seoul.');
    await page.getByLabel(ko ? '장소 검색' : 'Search places').fill('경복궁');
    const titleField = page.getByLabel(ko ? '제목' : 'Title', { exact: true });
    const placeField = page.getByLabel(ko ? '장소 검색' : 'Search places');
    const fieldStyles = await Promise.all(
      [titleField, placeField.locator('..')].map((field) =>
        field.evaluate((element) => ({
          background: getComputedStyle(element).backgroundColor,
          border: getComputedStyle(element).borderColor,
          radius: getComputedStyle(element).borderRadius,
        })),
      ),
    );
    expect(fieldStyles[1]).toEqual(fieldStyles[0]);
    await expect(placeField.locator('..').locator('svg')).toBeVisible();
    await page.getByRole('checkbox', { name: '경복궁' }).check();
    const back = page.getByRole('button', {
      name: ko ? '피드로 돌아가기' : 'Back to feed',
    });
    await back.click();
    const discard = page.getByRole('dialog');
    await expect(discard).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(discard).not.toBeVisible();
    await expect(back).toBeFocused();
    await expect(page.getByLabel(ko ? '제목' : 'Title', { exact: true })).toHaveValue(
      'Quiet afternoon',
    );
    await page.evaluate(() => window.history.back());
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page).toHaveURL(/\/posts\/new$/);
    await expect(page.getByLabel(ko ? '제목' : 'Title', { exact: true })).toHaveValue(
      'Quiet afternoon',
    );
    const review = page.getByRole('button', {
      name: ko ? '게시 전 확인' : 'Review post',
    });
    const reviewBounds = await review.boundingBox();
    expect(reviewBounds!.y + reviewBounds!.height).toBeGreaterThan(740);
    await review.click();
    await expect(steps.nth(2)).toBeFocused();
    await expect(page.getByRole('heading', { name: 'Quiet afternoon' })).toBeVisible();
    await expect(page.getByText('A walk in Seoul.')).toBeVisible();
    for (const label of [
      ko ? '사진 변경하기' : 'Change photo',
      ko ? '글 작성' : 'Write post',
    ]) {
      const action = page.getByRole('button', { name: label });
      expect(
        await action.evaluate((element) => getComputedStyle(element).borderWidth),
      ).toBe('0px');
    }
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    const publish = page.getByRole('button', {
      name: ko ? '업로드' : 'Upload',
      exact: true,
    });
    await publish.focus();
    const publishBox = await publish.boundingBox();
    expect(publishBox!.y + publishBox!.height).toBeGreaterThan(mainBottom - 80);
    await page.keyboard.press('Enter');
    const retry = page.getByRole('button', {
      name: ko ? '같은 게시물 다시 시도' : 'Retry this post',
    });
    await expect(retry).toBeEnabled();
    const noticeSizes = await page.locator('footer').evaluate((footer) => {
      const alert = footer.querySelector('[role="alert"]');
      const note = footer.querySelector('p:not([role="alert"])');
      return [alert, note].map((item) => getComputedStyle(item!).fontSize);
    });
    expect(noticeSizes[0]).toBe(noticeSizes[1]);
    await expect(
      page.getByRole('button', { name: ko ? '글 작성' : 'Write post' }),
    ).toBeDisabled();
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/posts/${postId}$`));
    expect(storageRequests).toEqual(['PUT']);
  });
}
