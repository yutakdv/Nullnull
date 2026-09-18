// Static server for the built bundle inside the runtime image. Vite is a dev
// dependency, so `vite preview` is not available there. Unknown paths fall
// back to index.html so client-side routes deep-link correctly.
//
// It also proxies /api, for the same reason vite.config.ts does in dev: the
// client builds its base URL from location.origin (shared/api/session.ts), so
// the session cookie and the CSRF token are same-origin. That proxy was dev
// ONLY, and this file is what serves the runtime image — so in the integration
// stack every /api/v1 request fell through to the index.html fallback below
// and came back as 200 text/html. openapi-fetch cannot parse that, so `data`
// was undefined and the client threw "Request failed with status 200": a
// request that succeeded, an app that could not read a single trip. The four
// keyboard-flow E2E tests that drive the trip screen through the browser have
// never passed since they were added, and this is why.
import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import { extname, join, normalize } from 'node:path';

const dist = new URL('./dist/', import.meta.url).pathname;
const port = Number(process.env.PORT ?? 4173);
const apiTarget = process.env.API_INTERNAL_BASE_URL;
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
};

/**
 * Forwards one /api request to the API and pipes its answer back untouched.
 *
 * Status and headers are copied rather than rebuilt. An API error has to reach
 * the client AS that error — a proxy that turns a 409 into something else
 * recreates exactly the defect this proxy exists to fix, where the wrong
 * content type made a failure look like a success.
 */
function proxy(req, res, target) {
  const upstream = new URL(req.url ?? '/', target);
  const forwarded = httpRequest(
    {
      protocol: upstream.protocol,
      hostname: upstream.hostname,
      port: upstream.port,
      path: upstream.pathname + upstream.search,
      method: req.method,
      // Headers pass through UNCHANGED, Host included. vite.config.ts sets
      // changeOrigin: false for the dev proxy and says why — the session
      // cookie and the CSRF token depend on the request still looking
      // same-origin — so this one matches it rather than inventing a second
      // policy. An earlier version rewrote Host to the upstream's, justified
      // by "some frameworks build redirect URLs from it"; nothing in apps/api
      // reads Host, getServerName or a forwarded header, so that comment
      // claimed a reason this server does not have.
      headers: req.headers,
    },
    (answer) => {
      res.writeHead(answer.statusCode ?? 502, answer.headers);
      answer.pipe(res);
    },
  );
  forwarded.on('error', () => {
    // A JSON body, because the caller is the generated client and an HTML
    // error page is the thing that started all this.
    res.writeHead(502, { 'Content-Type': 'application/problem+json' });
    res.end(JSON.stringify({ title: 'The API could not be reached', status: 502 }));
  });
  // Bodies matter: the app sends POST/PUT/DELETE with a CSRF header, so a
  // proxy that only forwarded the method would pass a read-only test and
  // break every mutation.
  req.pipe(forwarded);
}

createServer((req, res) => {
  const path = normalize(new URL(req.url ?? '/', 'http://x').pathname);

  if (path === '/api' || path.startsWith('/api/')) {
    if (apiTarget === undefined) {
      // Deliberately loud. Serving index.html here is what hid this for days:
      // the request succeeded, so nothing in the stack reported a problem and
      // only a screen with no data showed it.
      res.writeHead(502, { 'Content-Type': 'application/problem+json' });
      res.end(
        JSON.stringify({
          title: 'API_INTERNAL_BASE_URL is not set, so /api cannot be served',
          status: 502,
        }),
      );
      return;
    }
    proxy(req, res, apiTarget);
    return;
  }

  let file = join(dist, path);
  if (!file.startsWith(dist) || !existsSync(file) || statSync(file).isDirectory()) {
    file = join(dist, 'index.html');
  }
  res.setHeader('Content-Type', types[extname(file)] ?? 'application/octet-stream');
  createReadStream(file).pipe(res);
}).listen(port, '0.0.0.0');
