import { expect, test } from '@playwright/test';

// BA-010 exercises the real API directly. The shell's keyboard
// checks remain in shell.spec.ts; the session UI belongs to the next FE slice.
// Bearers stay in memory; traces would persist request cookies and response tokens.
test.use({
  baseURL: process.env.API_INTERNAL_BASE_URL ?? 'http://127.0.0.1:8080',
  trace: 'off',
  screenshot: 'off',
  video: 'off',
});

test('BA-010 bootstrap, refresh and independent tabs use the same owner', async ({
  request,
}) => {
  const origin = 'http://localhost:5173';
  const created = await request.post('/api/v1/demo/sessions', {
    headers: { Origin: origin },
  });
  expect(created.status()).toBe(201);
  const first = (await created.json()) as {
    owner: { id: string };
    csrfToken: string;
  };
  // Compose uses HTTP between containers. Send the returned Secure cookie
  // explicitly for this API transport test; browser cookie acceptance is not claimed.
  const cookie = created.headers()['set-cookie']?.split(';')[0];
  expect(Boolean(cookie)).toBe(true);
  const headers = { Origin: origin, Cookie: cookie! };
  const resumed = await request.post('/api/v1/demo/sessions', { headers });
  expect(resumed.status()).toBe(200);
  const second = (await resumed.json()) as { owner: { id: string } };
  expect(second.owner.id === first.owner.id).toBe(true);
  for (let tab = 0; tab < 2; tab += 1) {
    const token = await request.post('/api/v1/session/csrf', { headers });
    expect(token.status()).toBe(200);
    const body = (await token.json()) as { csrfToken: string };
    expect(body.csrfToken !== first.csrfToken).toBe(true);
  }
  const readiness = await request.get('/api/v1/demo/readiness', { headers });
  expect(readiness.status()).toBe(200);
  const crossOrigin = await request.post('/api/v1/session/csrf', {
    headers: { Cookie: cookie!, Origin: 'https://foreign.example' },
  });
  expect(crossOrigin.status()).toBe(403);
  const expired = await request.get('/api/v1/demo/readiness', {
    headers: { Cookie: '__Host-nullnull_session=invalid' },
  });
  expect(expired.status()).toBe(401);
});

test('BA-011 preferences persist and reject unsupported locale', async ({ request }) => {
  const origin = 'http://localhost:5173';
  const bootstrap = await request.post('/api/v1/demo/sessions', {
    headers: { Origin: origin },
  });
  expect(bootstrap.status()).toBe(201);
  const owner = (await bootstrap.json()) as { csrfToken: string };
  const cookie = bootstrap.headers()['set-cookie']?.split(';')[0];
  expect(Boolean(cookie)).toBe(true);
  const headers = {
    Origin: origin,
    Cookie: cookie!,
    'X-CSRF-Token': owner.csrfToken,
    'Content-Type': 'application/merge-patch+json',
  };
  for (const locale of ['en-US', 'ko-KR']) {
    const patched = await request.patch('/api/v1/me', {
      headers,
      data: { locale, onboardingCompleted: true },
    });
    expect(patched.status()).toBe(200);
    const read = await request.get('/api/v1/me', { headers });
    expect(read.status()).toBe(200);
    expect((await read.json()) as object).toMatchObject({
      locale,
      onboardingCompleted: true,
    });
  }
  const unsupported = await request.patch('/api/v1/me', {
    headers,
    data: { locale: 'ja-JP' },
  });
  expect(unsupported.status()).toBe(422);
  expect((await unsupported.json()) as object).toMatchObject({
    code: 'VALIDATION_FAILED',
    fieldErrors: [{ field: 'locale', code: 'UNSUPPORTED_LOCALE' }],
  });
});

test('BA-012 deletion replays its receipt and status token cannot authorize the session', async ({
  request,
}) => {
  const origin = 'http://localhost:5173';
  const bootstrap = await request.post('/api/v1/demo/sessions', {
    headers: { Origin: origin },
  });
  const session = (await bootstrap.json()) as { csrfToken: string };
  const cookie = bootstrap.headers()['set-cookie']?.split(';')[0];
  expect(Boolean(cookie)).toBe(true);
  const key = `delete-${crypto.randomUUID()}`;
  const accepted = await request.delete('/api/v1/session', {
    headers: {
      Origin: origin,
      Cookie: cookie!,
      'X-CSRF-Token': session.csrfToken,
      'Idempotency-Key': key,
    },
  });
  expect(accepted.status()).toBe(202);
  const firstText = await accepted.text();
  const receipt = JSON.parse(firstText) as {
    requestId: string;
    statusToken: string;
    statusUrl: string;
  };
  const replay = await request.delete('/api/v1/session', {
    headers: { Origin: origin, Cookie: cookie!, 'Idempotency-Key': key },
  });
  expect(replay.status()).toBe(202);
  expect(await replay.text()).toBe(firstText);
  const deletion = await request.get(receipt.statusUrl, {
    headers: { 'X-Deletion-Status-Token': receipt.statusToken },
  });
  expect(deletion.status()).toBe(200);
  const revoked = await request.get('/api/v1/me', {
    headers: { Cookie: cookie! },
  });
  expect(revoked.status()).toBe(401);
  const wrongKey = await request.delete('/api/v1/session', {
    headers: {
      Origin: origin,
      Cookie: cookie!,
      'Idempotency-Key': `delete-${crypto.randomUUID()}`,
    },
  });
  expect(wrongKey.status()).toBe(401);
});
