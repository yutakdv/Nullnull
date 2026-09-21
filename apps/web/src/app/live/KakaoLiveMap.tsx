import type { components } from '@nullnull/api-client';
import { useEffect, useRef, useState } from 'react';
import styles from './KakaoLiveMap.module.css';

type LiveArea = components['schemas']['LiveArea'];

interface KakaoLiveMapProps {
  areas: LiveArea[];
  label: string;
  onSelectArea?: (areaId: string) => void;
  unavailableDetail: string;
  unavailableTitle: string;
}

interface KakaoMapApi {
  maps: {
    CustomOverlay: new (options: { content: HTMLElement; position: unknown }) => {
      setMap(map: unknown | null): void;
    };
    LatLng: new (latitude: number, longitude: number) => unknown;
    Map: new (
      container: HTMLElement,
      options: { center: unknown; level: number },
    ) => unknown;
    load(callback: () => void): void;
  };
}

declare global {
  interface Window {
    kakao?: KakaoMapApi;
  }
}

let sdkPromise: Promise<KakaoMapApi> | null = null;

function loadKakaoMapSdk(appKey: string) {
  if (window.kakao?.maps) return Promise.resolve(window.kakao);
  if (sdkPromise) return sdkPromise;

  sdkPromise = new Promise<KakaoMapApi>((resolve, reject) => {
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(appKey)}&autoload=false`;
    script.onload = () => {
      if (!window.kakao?.maps) {
        reject(new Error('Kakao Maps SDK did not initialize'));
        return;
      }
      window.kakao.maps.load(() => resolve(window.kakao as KakaoMapApi));
    };
    script.onerror = () => reject(new Error('Kakao Maps SDK failed to load'));
    document.head.append(script);
  }).catch((error: unknown) => {
    sdkPromise = null;
    throw error;
  });

  return sdkPromise;
}

export function KakaoLiveMap({
  areas,
  label,
  onSelectArea,
  unavailableDetail,
  unavailableTitle,
}: KakaoLiveMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [unavailable, setUnavailable] = useState(false);

  useEffect(() => {
    const appKey = import.meta.env.VITE_KAKAO_MAP_APP_KEY?.trim();
    const container = containerRef.current;
    if (!appKey || !container) {
      setUnavailable(true);
      return;
    }

    let active = true;
    const overlays: Array<{ setMap(map: unknown | null): void }> = [];
    void loadKakaoMapSdk(appKey)
      .then((kakao) => {
        if (!active) return;
        const center = new kakao.maps.LatLng(37.5665, 126.978);
        const map = new kakao.maps.Map(container, { center, level: 7 });

        for (const area of areas) {
          if (!area.centroid) continue;
          const marker = document.createElement('button');
          marker.className = styles.marker ?? '';
          marker.type = 'button';
          marker.textContent = area.name;
          marker.addEventListener('click', () => onSelectArea?.(area.id));
          const overlay = new kakao.maps.CustomOverlay({
            content: marker,
            position: new kakao.maps.LatLng(
              area.centroid.latitude,
              area.centroid.longitude,
            ),
          });
          overlay.setMap(map);
          overlays.push(overlay);
        }
        setUnavailable(false);
      })
      .catch(() => {
        if (active) setUnavailable(true);
      });

    return () => {
      active = false;
      overlays.forEach((overlay) => overlay.setMap(null));
    };
  }, [areas, onSelectArea]);

  return (
    <div aria-label={label} className={styles.root} role="region">
      <div className={styles.map} ref={containerRef} />
      {unavailable ? (
        <div className={styles.unavailable} role="status">
          <strong>{unavailableTitle}</strong>
          <span>{unavailableDetail}</span>
        </div>
      ) : null}
    </div>
  );
}
