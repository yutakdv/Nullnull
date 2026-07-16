// 이미지 폴백: API 이미지가 없으면 위키백과에서 장소 이름으로 검색
import { readJson, writeJson, STORAGE_KEYS } from './storage';

const wikiImageCache = readJson(STORAGE_KEYS.wikiImages, {});
const wikiImagePending = new Map(); // 같은 이름 동시 요청 합치기

export async function searchWikiImage(name) {
  if (name in wikiImageCache) return wikiImageCache[name];
  if (wikiImagePending.has(name)) return wikiImagePending.get(name);
  const task = (async () => {
    try {
      const params = new URLSearchParams({
        action: 'query',
        format: 'json',
        origin: '*',
        prop: 'pageimages',
        piprop: 'thumbnail',
        pithumbsize: '800',
        generator: 'search',
        gsrsearch: name,
        gsrlimit: '1',
        gsrnamespace: '0',
      });
      const response = await fetch(`https://ko.wikipedia.org/w/api.php?${params}`);
      const data = await response.json();
      const pages = data?.query?.pages ?? {};
      const url = Object.values(pages)[0]?.thumbnail?.source ?? null;
      wikiImageCache[name] = url;
      writeJson(STORAGE_KEYS.wikiImages, wikiImageCache);
      return url;
    } catch {
      return null; // 실패는 캐시하지 않음 — 다음에 재시도
    } finally {
      wikiImagePending.delete(name);
    }
  })();
  wikiImagePending.set(name, task);
  return task;
}
