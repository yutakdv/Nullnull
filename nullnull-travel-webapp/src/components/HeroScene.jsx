// 홈 히어로 — '널널함'을 그린 살아있는 숲 풍경(3D 레이어드 씬).
// 원경 능선→중경 숲→근경 나무 순의 깊이 레이어에, 바람에 흔들리는 나무·
// 흐르는 안개·떨어지는 잎·숲길을 산책하는 사람을 CSS 애니메이션으로 움직인다.
const NEAR_TREES = [
  { x: 40, s: 1.15, d: 0 },
  { x: 150, s: 0.85, d: 1.2 },
  { x: 265, s: 1.3, d: 0.5 },
  { x: 420, s: 0.9, d: 1.8 },
  { x: 560, s: 1.2, d: 0.9 },
  { x: 700, s: 1.0, d: 0.2 },
  { x: 830, s: 1.35, d: 1.5 },
  { x: 950, s: 0.8, d: 0.7 },
];

export default function HeroScene() {
  return (
    <div className="hero-scene" aria-hidden="true">
      <div className="scene-sky" />
      <div className="scene-sun" />
      <div className="scene-cloud cloud-a" />
      <div className="scene-cloud cloud-b" />
      <svg
        className="scene-layer scene-far"
        viewBox="0 0 1000 240"
        preserveAspectRatio="xMidYMax slice"
      >
        <path
          d="M0 240V150c60-40 130-70 210-64 90 7 150-42 240-40 100 3 160 50 260 46 110-4 180-55 290-40v188z"
          fill="currentColor"
        />
      </svg>
      <svg
        className="scene-layer scene-mid"
        viewBox="0 0 1000 220"
        preserveAspectRatio="xMidYMax slice"
      >
        <path
          d="M0 220V120c80-25 140-52 220-48 90 5 150-30 250-26 100 5 170 36 270 30 90-6 170-30 260-18v162z"
          fill="currentColor"
        />
        {[90, 250, 430, 620, 810, 940].map((x, i) => (
          <g key={x} transform={`translate(${x} 130)`}>
            <g className="tree-sway mid-tree" style={{ '--sway-delay': `${i * 0.7}s` }}>
              <path
                d="M0-58C-15-40-22-20-22-4c0 14 10 24 22 24s22-10 22-24c0-16-7-36-22-54z"
                fill="currentColor"
                opacity="0.85"
              />
              <rect
                x="-2.4"
                y="16"
                width="4.8"
                height="20"
                rx="2"
                fill="currentColor"
                opacity="0.65"
              />
            </g>
          </g>
        ))}
      </svg>
      <div className="scene-mist mist-a" />
      <div className="scene-mist mist-b" />
      <svg
        className="scene-layer scene-near"
        viewBox="0 0 1000 200"
        preserveAspectRatio="xMidYMax slice"
      >
        {/* 숲길 — 산책자가 걷는 길 */}
        <path
          d="M0 200V168c150-14 320-22 500-22s350 8 500 22v32z"
          fill="rgba(236, 244, 235, 0.2)"
        />
        {NEAR_TREES.map(({ x, s, d }) => (
          <g key={x} transform={`translate(${x} 172) scale(${s})`}>
            <g className="tree-sway near-tree" style={{ '--sway-delay': `${d}s` }}>
              <path
                d="M0-96C-22-70-34-40-34-14c0 22 15 36 34 36s34-14 34-36c0-26-12-56-34-82z"
                fill="currentColor"
              />
              <rect
                x="-3.5"
                y="20"
                width="7"
                height="26"
                rx="3"
                fill="currentColor"
                opacity="0.8"
              />
            </g>
          </g>
        ))}
      </svg>
      {/* 숲길을 산책하는 사람 — 왼쪽에서 오른쪽으로 여유롭게 */}
      <svg className="scene-person" viewBox="0 0 40 80">
        <g className="person-body">
          <circle cx="20" cy="12" r="7.5" fill="currentColor" />
          <rect x="14.5" y="21" width="11" height="26" rx="5.5" fill="currentColor" />
          <g className="person-leg leg-l">
            <rect x="15" y="45" width="5" height="24" rx="2.5" fill="currentColor" />
          </g>
          <g className="person-leg leg-r">
            <rect x="20" y="45" width="5" height="24" rx="2.5" fill="currentColor" />
          </g>
          <g className="person-arm">
            <rect x="12" y="23" width="4.5" height="19" rx="2.25" fill="currentColor" />
          </g>
        </g>
      </svg>
      {[0, 1, 2, 3, 4].map((i) => (
        <span key={i} className={`scene-leaf leaf-${i}`} />
      ))}
    </div>
  );
}
