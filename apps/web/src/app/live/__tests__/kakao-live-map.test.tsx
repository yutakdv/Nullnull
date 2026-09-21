// @vitest-environment happy-dom
import type { components } from '@nullnull/api-client';
import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { KakaoLiveMap } from '../KakaoLiveMap.js';

type LiveArea = components['schemas']['LiveArea'];

const area = {
  id: '018f5a10-2c31-7f40-9a11-0c1d2e3f4c01',
  name: '광화문·덕수궁',
  centroid: null,
  boundaryGeoJson: null,
  crowd: null,
} as unknown as LiveArea;

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  delete window.kakao;
});

describe('KakaoLiveMap', () => {
  it('keeps the list usable and names the unavailable map when no key is configured', () => {
    vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', '');

    render(
      <KakaoLiveMap
        areas={[area]}
        label="카카오 지도"
        unavailableDetail="목록은 계속 이용할 수 있어요"
        unavailableTitle="지도를 불러오지 못했어요"
      />,
    );

    expect(screen.getByRole('region', { name: '카카오 지도' })).toBeVisible();
    expect(screen.getByText('지도를 불러오지 못했어요')).toBeVisible();
    expect(screen.queryByText('광화문·덕수궁')).not.toBeInTheDocument();
  });

  it('waits for the SDK load callback before creating a map after remounting', async () => {
    vi.stubEnv('VITE_KAKAO_MAP_APP_KEY', 'test-key');
    const onSelectArea = vi.fn();
    const mappedArea = {
      ...area,
      centroid: { latitude: 37.5759, longitude: 126.9768 },
    } as LiveArea;
    let finishLoading: (() => void) | undefined;
    let script: HTMLScriptElement | undefined;
    vi.spyOn(document.head, 'append').mockImplementation((...nodes) => {
      script = nodes[0] as HTMLScriptElement;
    });

    const first = render(
      <KakaoLiveMap
        areas={[mappedArea]}
        label="카카오 지도"
        onSelectArea={onSelectArea}
        unavailableDetail="목록은 계속 이용할 수 있어요"
        unavailableTitle="지도를 불러오지 못했어요"
      />,
    );
    expect(script?.src).toContain('appkey=test-key');

    window.kakao = {
      maps: {
        CustomOverlay: class {
          constructor(private options: { content: HTMLElement; position: unknown }) {}
          setMap(map: { container: HTMLElement } | null) {
            if (map) map.container.append(this.options.content);
            else this.options.content.remove();
          }
        },
        LatLng: class {
          constructor(
            public latitude: number,
            public longitude: number,
          ) {}
        },
        Map: class {
          constructor(public container: HTMLElement) {}
        },
        load: (callback) => {
          finishLoading = callback;
        },
      },
    };
    script?.dispatchEvent(new Event('load'));
    first.unmount();

    render(
      <KakaoLiveMap
        areas={[mappedArea]}
        label="카카오 지도"
        onSelectArea={onSelectArea}
        unavailableDetail="목록은 계속 이용할 수 있어요"
        unavailableTitle="지도를 불러오지 못했어요"
      />,
    );

    await act(async () => {
      await Promise.resolve();
    });
    expect(
      screen.queryByRole('button', { name: mappedArea.name }),
    ).not.toBeInTheDocument();
    await act(async () => {
      finishLoading?.();
    });
    const marker = await screen.findByRole('button', { name: mappedArea.name });
    marker.click();
    expect(onSelectArea).toHaveBeenCalledWith(mappedArea.id);
    expect(screen.queryByText('지도를 불러오지 못했어요')).not.toBeInTheDocument();
  });
});
