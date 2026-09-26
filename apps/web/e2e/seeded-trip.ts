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
 * The first day holds 경복궁 then 인사동; 경복궁 carries a DATE lock and 인사동 a MUST_VISIT one
 * (SECOND_ITEM below).
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

/**
 * 인사동, whose only lock is MUST_VISIT - which does not stop a day change, so moving it goes
 * straight from the move sheet to the reorder with no confirm in between. That is the one path
 * where MoveDaySheet's own focus restore is the only thing keeping focus off <body>: moving
 * FIRST_ITEM in the gate hands the restore to the DATE-lock confirm instead.
 */
export const SECOND_ITEM = '인사동';

const GYEONGBOKGUNG = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b01';
const INSADONG = '018f4b20-1a44-7e11-9c02-5d7e3f1a2b03';

/**
 * Opens `/` and waits for the session bootstrap, the way a person arrives.
 *
 * Not because nothing else bootstraps. Since #240 (ecad7070) the shell also starts a session
 * when a deep link's CSRF reissue answers 401 with `missingCredential: SESSION_COOKIE`
 * (AppShell.tsx `noCookieSent`; live-session.integration.spec.ts measures it on /live). What
 * a deep link does not give is a moment to wait for: the splash redirects to /language or
 * /feed only on the bootstrap's success branch, so the wait below ends once the session
 * exists, while a deep link's URL does not move when it lands. The helpers below need that
 * moment - a trip is its creator's (invariant 11), so the session has to exist before
 * createTrip, and the page opened afterwards has to carry that same session.
 */
export async function startSession(page: Page): Promise<void> {
  await page.goto('/');
  // Splash redirects to /language (first visit) or /feed (returning) once bootstrap resolves.
  await page.waitForURL(/\/(language|feed)$/, { timeout: 15_000 });
}

/**
 * Opens `/`, waits for the session bootstrap, then creates a trip from inside the page.
 *
 * From inside the page because that is where the session lives: the browser holds the
 * __Host- cookie and sends the Origin the API checks, exactly as the app does. Returns the trip
 * path. Navigating afterwards reloads the app, which mints its own CSRF token again.
 */
export async function createSeededTrip(page: Page): Promise<string> {
  await startSession(page);

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

/**
 * Creates trips through the real product API, chooses the first one as the
 * representative trip, and returns the detail path the UI opened.
 *
 * The deterministic MSW server already has trips, while the composed API
 * starts each browser session empty. Calling the same UI journey in both
 * modes keeps feed tests independent of either fixture's ids and titles.
 */
export async function createRepresentativeTrip(
  page: Page,
  tripCount = 1,
): Promise<string> {
  for (let index = 0; index < tripCount; index += 1) {
    await createSeededTrip(page);
  }

  await page.goto('/trips/select');
  const firstTrip = page.getByRole('list').getByRole('button').first();
  await firstTrip.click();
  await page.waitForURL(/\/trip\/[0-9a-f-]+$/i, { timeout: 15_000 });
  return new URL(page.url()).pathname;
}
