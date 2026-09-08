// Static server for the built bundle inside the runtime image. Vite is a dev
// dependency, so `vite preview` is not available there. Unknown paths fall
// back to index.html so client-side routes deep-link correctly.
import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';

const dist = new URL('./dist/', import.meta.url).pathname;
const port = Number(process.env.PORT ?? 4173);
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
};

createServer((req, res) => {
  const path = normalize(new URL(req.url ?? '/', 'http://x').pathname);
  let file = join(dist, path);
  if (!file.startsWith(dist) || !existsSync(file) || statSync(file).isDirectory()) {
    file = join(dist, 'index.html');
  }
  res.setHeader('Content-Type', types[extname(file)] ?? 'application/octet-stream');
  createReadStream(file).pipe(res);
}).listen(port, '0.0.0.0');
