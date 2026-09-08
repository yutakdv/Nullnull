import type { Meta, StoryObj } from '@storybook/react-vite';
import { StateLabel } from './StateLabel.js';

// Figma: `Data / StateLabel` (C07).
const meta = {
  title: 'Data/StateLabel',
  component: StateLabel,
} satisfies Meta<typeof StateLabel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** All six states side by side. Each reads differently on its own. */
export const AllStates: Story = {
  args: { state: 'LIVE' },
  render: () => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <StateLabel state="LIVE" observedAt="14:35 기준" />
      <StateLabel state="FORECAST" />
      <StateLabel state="QUALITATIVE" />
      <StateLabel state="STALE" />
      <StateLabel state="UNAVAILABLE" />
      <StateLabel state="REPLAY" />
    </div>
  ),
};

/** A recording must never read as a current observation. */
export const ReplayIsNotLive: Story = {
  args: { state: 'REPLAY', observedAt: '어제 14:35 관측' },
};

export const Unavailable: Story = {
  args: { state: 'UNAVAILABLE' },
};
