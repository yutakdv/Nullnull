import type { components } from '@nullnull/api-client';

export function validatePostImage(file: Pick<File, 'type' | 'size'>) {
  if (!['image/jpeg', 'image/png'].includes(file.type)) return 'format';
  if (file.size < 1 || file.size > 4_194_304) return 'size';
  return null;
}

export async function imageChecksum(file: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('');
}

// XHR supplies actual upload progress. The API client is deliberately not used
// for storage: session cookies and CSRF belong only to our API origin.
export function uploadPostImage(
  file: Blob,
  ticket: components['schemas']['UploadTicket'],
  signal: AbortSignal,
  progress: (percent: number) => void,
  createXhr = () => new XMLHttpRequest(),
): Promise<void> {
  if (Date.parse(ticket.expiresAt) <= Date.now())
    return Promise.reject(new Error('Upload expired'));
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const xhr = createXhr();
    const abort = () => xhr.abort();
    const finish = (error?: Error) => {
      signal.removeEventListener('abort', abort);
      if (error) reject(error);
      else resolve();
    };
    xhr.open(ticket.method, ticket.url);
    xhr.withCredentials = false;
    xhr.timeout = 120_000;
    for (const [name, value] of Object.entries(ticket.headers)) {
      // Content-Length is a forbidden browser header; the browser sets it
      // from the same Blob size used to reserve the ticket.
      if (name.toLowerCase() !== 'content-length') xhr.setRequestHeader(name, value);
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable)
        progress(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = () =>
      finish(
        xhr.status >= 200 && xhr.status < 300 ? undefined : new Error('Upload failed'),
      );
    xhr.onerror = () => finish(new Error('Upload failed'));
    xhr.ontimeout = () => finish(new Error('Upload timed out'));
    xhr.onabort = () => finish(new DOMException('Upload cancelled', 'AbortError'));
    signal.addEventListener('abort', abort, { once: true });
    xhr.send(file);
  });
}
