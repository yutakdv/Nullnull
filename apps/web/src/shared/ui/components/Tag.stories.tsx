import type { Meta, StoryObj } from '@storybook/react-vite';
import { Tag } from './Tag.js';

// Figma: `Data / Tag` (C47). tone=solid|outline.
const meta = {
  title: 'Data/Tag',
  component: Tag,
  args: { label: '공식 혼잡 예측' },
} satisfies Meta<typeof Tag>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Solid: Story = {};
export const Outline: Story = { args: { tone: 'outline' } };

/** Long labels wrap rather than clipping, which 200% zoom relies on. */
export const LongLabel: Story = {
  args: { label: '공식 혼잡 예측 범위 밖 · 주말·공휴일만 안내' },
  decorators: [
    (Story) => (
      <div style={{ width: 200 }}>
        <Story />
      </div>
    ),
  ],
};
