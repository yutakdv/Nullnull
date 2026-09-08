import type { Meta, StoryObj } from '@storybook/react-vite';
import { MetricDelta } from './MetricDelta.js';

// Figma: `Data / MetricDelta` (C06).
const meta = {
  title: 'Data/MetricDelta',
  component: MetricDelta,
} satisfies Meta<typeof MetricDelta>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Improved: Story = {
  args: {
    label: '혼잡 단계',
    eligible: true,
    value: '4 · 혼잡 → 1 · 매우 여유',
    direction: 'improved',
  },
};

export const Unchanged: Story = {
  args: { label: '이동거리 변화', eligible: true, value: '변화 없음' },
};

/**
 * The server said the pair is not comparable, so no number appears at all.
 * A missing value is never filled in with 0.
 */
export const NotComparable: Story = {
  args: {
    label: '이동시간 변화',
    eligible: false,
    value: '18분',
    reason: '경로 provider 미정 · 확인 불가',
  },
};

/** Long Korean reason at 360px. */
export const LongReason: Story = {
  args: {
    label: '피크 시간대 겹침',
    eligible: false,
    reason: '같은 예보 발표본이 아니라 두 시점을 나란히 비교할 수 없어요',
  },
};
