import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import districts from './assets/seoul-districts.geo.json';

// 혼잡 level(1~5)별 핀 색 — 널널(그린) → 붐빔(레드)
const LEVEL_COLOR = {
  1: 0x6fd08c, 2: 0x9fd07a, 3: 0xe0c65a, 4: 0xe0954c, 5: 0xdf5c5c,
};
const BRAND = 0x3d8567;
const BRAND_SOFT = 0x6faf8f;

function webglSupported() {
  try {
    const canvas = document.createElement('canvas');
    return !!(window.WebGLRenderingContext &&
      (canvas.getContext('webgl') || canvas.getContext('experimental-webgl')));
  } catch {
    return false;
  }
}

// 모든 폴리곤 경위도의 바운딩 박스 → [-span/2, span/2] 평면 좌표 투영기를 만든다
function makeProjector() {
  let minLon = Infinity, maxLon = -Infinity, minLat = Infinity, maxLat = -Infinity;
  for (const f of districts.features) {
    for (const ring of f.geometry.coordinates) {
      for (const [lon, lat] of ring) {
        if (lon < minLon) minLon = lon;
        if (lon > maxLon) maxLon = lon;
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
      }
    }
  }
  const cx = (minLon + maxLon) / 2;
  const cy = (minLat + maxLat) / 2;
  const scale = 11 / Math.max(maxLon - minLon, maxLat - minLat);
  return (lon, lat) => [(lon - cx) * scale, (lat - cy) * scale];
}

export default function SeoulMap3D({ spots = [], fallback = null }) {
  const mountRef = useRef(null);
  const [supported] = useState(webglSupported);

  useEffect(() => {
    if (!supported || !mountRef.current) return undefined;
    const mount = mountRef.current;
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const isMobile = window.innerWidth < 640;
    const project = makeProjector();

    const width = mount.clientWidth || window.innerWidth;
    const height = mount.clientHeight || 480;

    let renderer;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: !isMobile, alpha: true });
    } catch {
      return undefined;
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, isMobile ? 1.5 : 2));
    renderer.setSize(width, height);
    renderer.setClearColor(0x000000, 0);           // 투명 — 기존 히어로 그라데이션 위에 얹힘
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.15;
    mount.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(42, width / height, 0.1, 100);
    camera.position.set(0, 9.5, 11);
    camera.lookAt(0, 0, 0);

    const group = new THREE.Group();          // 지도 평면(xz)으로 눕히고 y를 위로
    group.rotation.x = -Math.PI / 2;
    scene.add(group);

    // ── 자치구 입체 블록 ──────────────────────────────────────
    const depth = 0.34;
    const materials = [];
    for (const feature of districts.features) {
      const ring = feature.geometry.coordinates[0];
      const shape = new THREE.Shape();
      ring.forEach(([lon, lat], i) => {
        const [x, y] = project(lon, lat);
        if (i === 0) shape.moveTo(x, y);
        else shape.lineTo(x, y);
      });
      const geo = new THREE.ExtrudeGeometry(shape, { depth, bevelEnabled: false });
      const mat = new THREE.MeshStandardMaterial({
        color: BRAND, emissive: 0x0f3325, emissiveIntensity: 0.55,
        metalness: 0.25, roughness: 0.42,
      });
      materials.push(mat);
      group.add(new THREE.Mesh(geo, mat));

      // 상단 경계선 — 자치구 구분을 위한 은은한 윤곽
      const edges = new THREE.EdgesGeometry(geo, 25);
      const line = new THREE.LineSegments(
        edges, new THREE.LineBasicMaterial({ color: BRAND_SOFT, transparent: true, opacity: 0.35 }));
      group.add(line);
    }

    // ── 추천 스팟 핀(혼잡도 색) — 발광 구 + 얇은 기둥 ─────────
    const pinGroup = new THREE.Group();
    group.add(pinGroup);
    const drawnSpots = spots
      .filter((s) => typeof s.lat === 'number' && typeof s.lng === 'number')
      .slice(0, isMobile ? 10 : 18);
    for (const spot of drawnSpots) {
      const [x, y] = project(spot.lng, spot.lat);
      const color = LEVEL_COLOR[spot.level] ?? BRAND_SOFT;
      const sphere = new THREE.Mesh(
        new THREE.SphereGeometry(0.11, 16, 16),
        new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.9 }));
      sphere.position.set(x, y, depth + 0.42);
      pinGroup.add(sphere);
      const stick = new THREE.Mesh(
        new THREE.CylinderGeometry(0.015, 0.015, 0.42, 8),
        new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.55 }));
      stick.position.set(x, y, depth + 0.21);
      stick.rotation.x = Math.PI / 2;         // 기둥을 압출 방향(z)으로 세운다
      pinGroup.add(stick);
    }

    // ── 조명(프리미엄 키/림/앰비언트) ────────────────────────
    scene.add(new THREE.AmbientLight(0xdfeee7, 0.75));
    const key = new THREE.DirectionalLight(0xffffff, 1.55);
    key.position.set(6, 12, 8);
    scene.add(key);
    const rim = new THREE.DirectionalLight(0x9fe0c0, 0.7);
    rim.position.set(-8, 4, -6);
    scene.add(rim);
    const glow = new THREE.PointLight(0x87c9e8, 0.6, 40);
    glow.position.set(0, 6, 4);
    scene.add(glow);

    const controls = new OrbitControls(camera, renderer.domElement);
    controls.target.set(0, 0, 0);
    controls.enableZoom = false;
    controls.enablePan = false;
    controls.enableRotate = false;      // 사용자 조작 없이 감상용(히어로 배경)
    controls.autoRotate = !reduceMotion;
    controls.autoRotateSpeed = 0.55;
    controls.enableDamping = true;
    controls.update();

    let raf = 0;
    const renderOnce = () => renderer.render(scene, camera);
    const loop = () => { controls.update(); renderer.render(scene, camera); raf = requestAnimationFrame(loop); };
    if (reduceMotion) renderOnce();
    else loop();

    const onResize = () => {
      const w = mount.clientWidth || window.innerWidth;
      const h = mount.clientHeight || 480;
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      renderer.setSize(w, h);
      if (reduceMotion) renderOnce();
    };
    window.addEventListener('resize', onResize);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', onResize);
      controls.dispose();
      scene.traverse((obj) => {
        if (obj.geometry) obj.geometry.dispose();
        if (obj.material) {
          const mats = Array.isArray(obj.material) ? obj.material : [obj.material];
          mats.forEach((m) => m.dispose());
        }
      });
      renderer.dispose();
      if (renderer.domElement.parentNode === mount) mount.removeChild(renderer.domElement);
    };
  }, [supported, spots]);

  if (!supported) return fallback;
  return <div ref={mountRef} className="seoul-map-3d" aria-hidden="true" />;
}
