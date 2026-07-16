import { useEffect, useState } from 'react';
import { searchWikiImage } from '../services/wikiImage';
import { placeholderImage } from '../utils/image';

// API 이미지 → (없거나 로드 실패 시) 위키백과 검색 → 플레이스홀더 순서로 표시
export default function SmartImage({ src, name, alt, className }) {
  const [failed, setFailed] = useState(false);
  const [wikiSrc, setWikiSrc] = useState(undefined);
  const needsFallback = !src || failed;

  useEffect(() => {
    if (!needsFallback || !name) return undefined;
    let alive = true;
    searchWikiImage(name).then((url) => {
      if (alive) setWikiSrc(url);
    });
    return () => {
      alive = false;
    };
  }, [needsFallback, name]);

  const resolved = !needsFallback
    ? src.startsWith('http') || src.startsWith('/')
      ? src
      : `/${src}`
    : (wikiSrc ?? placeholderImage(name));
  return (
    <img
      src={resolved}
      alt={alt ?? name ?? ''}
      className={className}
      loading="lazy"
      onError={() => {
        if (!needsFallback) setFailed(true);
        else if (wikiSrc) setWikiSrc(null); // 위키 이미지도 깨지면 플레이스홀더로
      }}
    />
  );
}
