import type { Page } from '@playwright/test';

// A trip of the test's own, built from the catalog rows the Docker gate seeds (#253).
//
// These specs used to open /trip/018f4a10-…, the MSW fixture's id. Against the real API that id
// is nobody's trip the test can be: every run starts from a fresh anonymous session, and a trip
// belongs to the session that created it, so the answer is 404 by design (invariant 11,
// BA-070-T1). Nothing can seed a trip for a session that does not exist yet, so the test makes
// one, the way the product does — createTrip with seedItems.
//
// The place ids are the ones scripts/e2e/catalog-seed.sql writes (the same ids
// packages/contracts/fixtures/trips/trip-detail-scheduled.json uses for these names). If that
// seed did not run, createTrip answers 4xx and this throws with the status, rather than the
// screen failing later as "We can't find that trip".

/**
 * The first day holds 경복궁 then 인사동; 경복궁 carries a DATE lock and 인사동 a MUST_VISIT one.
 *
 * The DATE lock is what makes a day change a question rather than a move
 * (`reorder.ts` `moveBlock` returns 'date-lock' for DATE and nothing else), so
 * the focus-restore test needs it to reach a confirm at all. It is sent here
 * because the server does NOT attach it: TripService only turns a candidate's
 * MUST_VISIT into a lock, and these items come from seedItems, not candidates.
 * The msw fixture (trip-detail-scheduled.json) does carry DATE on 경복궁, which
 * is why this test passed locally while the gate never saw a confirm at all.
 */
export const FIRST_ITEM = '경복궁';

const GYEONGBOKGUNG = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01';
const INSADONG = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b03';

/**
 * Opens `/`, waits for the session bootstrap, then creates a trip from inside the page.
 *
 * From inside the page because that is where the session lives: the browser holds the
 * __Host- cookie and sends the Origin the API checks, exactly as the app does. Returns the trip
 * path. Navigating afterwards reloads the app, which mints its own CSRF token again.
 */
export async function createSeededTrip(page: Page): Promise<string> {
  await page.goto('/');
  // Splash redirects to /language (first visit) or /feed (returning) once bootstrap resolves.
  await page.waitForURL(/\/(language|feed)$/, { timeout: 15_000 });

  // Dates relative to today, so the trip never falls into the past as the calendar moves.
  const day = (offset: number) => {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + offset);
    return d.toISOString().slice(0, 10);
  };
  const startDate = day(14);
  const endDate = day(17);

  const result = await page.evaluate(
    async ({ startDate, endDate, first, second }) => {
      const csrf = await fetch('/api/v1/session/csrf', {
        method: 'POST',
        credentials: 'same-origin',
      });
      if (!csrf.ok) return { status: csrf.status, step: 'csrf', body: await csrf.text() };
      const { csrfToken } = (await csrf.json()) as { csrfToken: string };
      const created = await fetch('/api/v1/trips', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': csrfToken,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({
          startDate,
          endDate,
          timezone: 'Asia/Seoul',
          planningLevel: 'MUST_VISIT_ONLY',
          interests: [],
          seedItems: [
            {
              placeId: first,
              date: startDate,
              position: 0,
              // DATE carries its own date: V014's typed check requires it, and
              // the contract makes it required on SetDateConstraintInput.
              // BA-030-T2 sends this same shape through seedItems against a
              // real PostgreSQL, so the server is known to accept and store it.
              constraints: [{ type: 'DATE', locked: true, date: startDate }],
            },
            {
              placeId: second,
              date: startDate,
              position: 1,
              constraints: [{ type: 'MUST_VISIT', locked: true }],
            },
          ],
        }),
      });
      if (created.status !== 201) {
        return { status: created.status, step: 'createTrip', body: await created.text() };
      }
      const { id } = (await created.json()) as { id: string };
      return { status: 201, step: 'createTrip', id };
    },
    { startDate, endDate, first: GYEONGBOKGUNG, second: INSADONG },
  );

  if (result.status !== 201 || !('id' in result) || !result.id) {
    throw new Error(
      `could not create the seeded trip: ${result.step} answered ${result.status} ` +
        `${'body' in result ? result.body : ''}`,
    );
  }
  return `/trip/${result.id}`;
}
