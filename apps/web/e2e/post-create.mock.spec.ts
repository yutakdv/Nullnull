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
  test(`#312 photo upload, keyboard publish, and retry in ${locale}`, async ({
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
    await entry.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/posts\/new$/);
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
    await page.getByLabel(ko ? '제목' : 'Title', { exact: true }).fill('Quiet afternoon');
    await page
      .getByLabel(ko ? '캡션' : 'Caption', { exact: true })
      .fill('A walk in Seoul.');
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
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    const publish = page.getByRole('button', {
      name: ko ? '게시' : 'Publish',
      exact: true,
    });
    await publish.focus();
    await page.keyboard.press('Enter');
    const retry = page.getByRole('button', {
      name: ko ? '같은 게시물 다시 시도' : 'Retry this post',
    });
    await expect(retry).toBeEnabled();
    await expect(page.getByLabel(ko ? '제목' : 'Title', { exact: true })).toBeDisabled();
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/posts/${postId}$`));
    expect(storageRequests).toEqual(['PUT']);
  });
}
