import seoulDistricts from '../assets/seoul-districts.geo.json';

const VIEW_W = 1000,
  VIEW_H = 640,
  PAD = 0.18;

function makeProjection(points) {
  const lats = points.map((p) => p.lat),
    lngs = points.map((p) => p.lng);
  const cLat = (Math.min(...lats) + Math.max(...lats)) / 2;
  const cLng = (Math.min(...lngs) + Math.max(...lngs)) / 2;
  const spanLat = Math.max(Math.max(...lats) - Math.min(...lats), 0.02) * (1 + PAD * 2);
  const spanLng = Math.max(Math.max(...lngs) - Math.min(...lngs), 0.02) * (1 + PAD * 2);
  const minLat = cLat - spanLat / 2,
    maxLat = cLat + spanLat / 2;
  const minLng = cLng - spanLng / 2,
    maxLng = cLng + spanLng / 2;
  return (lng, lat) => [
    ((lng - minLng) / (maxLng - minLng)) * VIEW_W,
    ((maxLat - lat) / (maxLat - minLat)) * VIEW_H, // y 뒤집기(위도↑=위쪽)
  ];
}

function districtPath(feature, project) {
  return (feature.geometry.coordinates || [])
    .map((ring) => {
      const pts = ring.map(([lng, lat]) => project(lng, lat));
      const bbox = pts.reduce(
        (b, [x, y]) => ({
          minX: Math.min(b.minX, x),
          maxX: Math.max(b.maxX, x),
          minY: Math.min(b.minY, y),
          maxY: Math.max(b.maxY, y),
        }),
        { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity },
      );
      if (bbox.maxX < 0 || bbox.minX > VIEW_W || bbox.maxY < 0 || bbox.minY > VIEW_H) return ''; // viewBox 밖(교차 없음) 스킵
      return 'M' + pts.map(([x, y]) => `${x.toFixed(1)} ${y.toFixed(1)}`).join('L') + 'Z';
    })
    .join(' ');
}

export default function PointsMap({ points }) {
  if (!points?.length)
    return (
      <div className="route-map">
        <div className="svg-map-skeleton" />
      </div>
    );
  const project = makeProjection(points);
  const projected = points.map((p) => {
    const [x, y] = project(p.lng, p.lat);
    return { ...p, x, y };
  });
  const districts = seoulDistricts.features
    .map((f) => ({ name: f.properties?.name, d: districtPath(f, project) }))
    .filter((d) => d.d);
  const routePts = projected.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');
  return (
    <div className="route-map">
      <svg
        className="svg-map"
        viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
        preserveAspectRatio="xMidYMid slice"
      >
        {districts.map((d) => (
          <path key={d.name} d={d.d} className="svg-district" />
        ))}
        {projected.length > 1 && <polyline points={routePts} className="svg-route" />}
        {projected.map((p, i) => (
          <g
            key={i}
            transform={`translate(${p.x.toFixed(1)} ${p.y.toFixed(1)})`}
            className={`svg-pin ${p.className || ''}`}
          >
            {p.tooltip && <title>{p.tooltip}</title>}
            <circle r="16" />
            <text className="svg-pin-label" dy="5" textAnchor="middle">
              {p.pin}
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}
