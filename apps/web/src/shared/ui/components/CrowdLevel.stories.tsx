import type { components } from '@nullnull/api-client';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { CrowdLevel } from './CrowdLevel.js';

type CrowdMetric = components['schemas']['CrowdMetric'];

const provenance = {
  attribution: '출처: 서울특별시 「서울시 실시간 도시데이터」',
  attributionShort: '출처: 서울특별시',
  officialUrl: null,
  licenseUrl: null,
  observedAt: '2026-10-04T05:35:00Z',
} as components['schemas']['DataProvenance'];

const crowd = (level: string, label: string, state: CrowdMetric['state']) =>
  ({ state, label, ordinalLevel: level, provenance }) as CrowdMetric;

const meta = {
  title: 'Data/CrowdLevel',
  component: CrowdLevel,
} satisfies Meta<typeof CrowdLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Levels: Story = {
  args: { crowd: crowd('4', '4 · 혼잡', 'LIVE') },
  render: () => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <CrowdLevel crowd={crowd('1', '1 · 매우 여유', 'LIVE')} />
      <CrowdLevel crowd={crowd('2', '2 · 여유', 'LIVE')} />
      <CrowdLevel crowd={crowd('3', '3 · 보통', 'FORECAST')} />
      <CrowdLevel crowd={crowd('4', '4 · 혼잡', 'FORECAST')} />
    </div>
  ),
};

/** Missing data says so instead of drawing an empty bar. */
export const Unavailable: Story = {
  args: { crowd: null, unavailableReason: '관측 권역 밖이에요' },
};

/** A level outside 1..4 draws no bar rather than guessing. */
export const UnknownLevel: Story = {
  args: { crowd: crowd('', '수치 없음', 'QUALITATIVE') },
};
