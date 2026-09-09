// Offline shell only (FE-004).
//
// This caches the app shell — the documents and static assets needed to render
// a screen — and NOTHING from the API. That boundary is the whole design:
//
//   docs/api responses carry freshness, confidence and sourceState, and
//   CLAUDE.md forbids showing stale or replayed data as live. A service worker
//   that served a cached /api/v1 response would do exactly that, silently, with
//   the screen having no way to know the body was old. So every request to the
//   API goes to the network, and fails honestly when there is none.
//
// Navigations fall back to the cached shell when offline, which is what keeps
// the app from showing the browser's error page. The screen then renders its own
// offline state from a failed query, which is the state the app can explain.

const VERSION = 'v1';
const SHELL_CACHE = `nullnull-shell-${VERSION}`;

// Only the entry document is precached. Hashed assets are added as they are
// requested, so a build never has to list them.
const SHELL_URLS = ['/', '/manifest.webmanifest', '/icon.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      // Individual misses must not fail the whole install.
      .then((cache) => Promise.allSettled(SHELL_URLS.map((url) => cache.add(url))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((key) => key !== SHELL_CACHE).map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

/** The API is same-origin, so path is what separates data from shell. */
function isApiRequest(url) {
  return url.pathname.startsWith('/api/');
}

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Never touch anything but same-origin GETs. Mutations must reach the server
  // or fail; replaying one from a worker would break idempotency guarantees.
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;

  // API traffic is never cached and never served from cache. See the note above.
  if (isApiRequest(url)) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          void caches.open(SHELL_CACHE).then((cache) => cache.put('/', copy));
          return response;
        })
        // Offline: the cached shell boots and the screens render their own
        // offline state rather than the browser's error page.
        .catch(() => caches.match('/').then((cached) => cached ?? Response.error())),
    );
    return;
  }

  // Static assets: cache-first, since Vite fingerprints their filenames.
  event.respondWith(
    caches.match(request).then(
      (cached) =>
        cached ??
        fetch(request).then((response) => {
          if (response.ok && response.type === 'basic') {
            const copy = response.clone();
            void caches.open(SHELL_CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        }),
    ),
  );
});
