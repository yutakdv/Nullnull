const PLACEHOLDER_GRADIENTS = [
  ['#cfe9dd', '#a9d8ec'],
  ['#d8ecd2', '#bfe0d3'],
  ['#dfe7d2', '#cbe6dd'],
  ['#d1e3ef', '#c8e7db'],
];

function hashSeed(seed = '') {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  return hash;
}

// 사진이 없는 장소를 위한 브랜드 그라디언트 자리표시(외부 요청 없는 data-URI SVG).
// generic 사진을 잘못 붙이는 대신 '사진 준비 중'으로 읽히는 핀 모티브 타일을 만든다.
export function placeholderImage(seed = '') {
  const [from, to] = PLACEHOLDER_GRADIENTS[hashSeed(seed) % PLACEHOLDER_GRADIENTS.length];
  const svg =
    `<svg xmlns='http://www.w3.org/2000/svg' width='400' height='300'>` +
    `<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>` +
    `<stop offset='0' stop-color='${from}'/><stop offset='1' stop-color='${to}'/>` +
    `</linearGradient></defs>` +
    `<rect width='400' height='300' fill='url(#g)'/>` +
    `<path d='M200 98c-23 0-42 19-42 42 0 30 42 68 42 68s42-38 42-68c0-23-19-42-42-42z'` +
    ` fill='rgba(255,255,255,0.68)'/>` +
    `<circle cx='200' cy='140' r='15' fill='rgba(61,133,103,0.5)'/>` +
    `</svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

export function imageUrl(path, seed = '') {
  if (!path) return placeholderImage(seed);
  if (path.startsWith('http') || path.startsWith('/')) return path;
  return `/${path}`;
}
