// @vitest-environment happy-dom
import type { components } from '@nullnull/api-client';
import { render, screen } from '@testing-library/react';
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
});
