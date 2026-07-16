// 기본은 same-origin(/api/...) 호출:
//  - 로컬 dev: vite.config.js proxy → 127.0.0.1:8000
//  - docker-compose: nginx → backend:8000
//  - Vercel: vercel.json rewrites → 공개된 백엔드 URL
// 다른 주소로 직접 호출하려면 VITE_API_BASE_URL로 재정의(CORS 허용 필요).
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export async function apiFetch(path, options) {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', ...(options?.headers ?? {}) },
    ...options,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail ?? `API 요청 실패 (${response.status})`);
  }
  return response.json();
}
