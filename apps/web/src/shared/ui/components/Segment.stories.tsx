import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { Segment } from './Segment.js';

// Figma: `Nav / Segment` (C09).
//
// Interactive stories hold their own state so the pointer and keyboard paths
// are actually exercisable in Storybook, rather than frozen on one value.
const meta = {
  title: 'Nav/Segment',
  component: Segment,
} satisfies Meta<typeof Segment<string>>;

export default meta;
type Story = StoryObj<typeof meta>;

function Interactive({
  options,
  initial,
}: {
  options: readonly { value: string; label: string; disabledReason?: string }[];
  initial: string;
}) {
  const [value, setValue] = useState(initial);
  return (
    <Segment label="보기 방식" options={options} value={value} onChange={setValue} />
  );
}

export const TwoOptions: Story = {
  args: { label: '보기 방식', options: [], value: '', onChange: () => undefined },
  render: () => (
    <Interactive
      initial="list"
      options={[
        { value: 'list', label: '목록' },
        { value: 'map', label: '지도' },
      ]}
    />
  ),
};

/**
 * A disabled option states why. Map is off in P0 until a provider, licence and
 * attribution are approved, so it is shown as unavailable rather than hidden —
 * hiding it would make the list view look like the only design.
 */
export const WithDisabledOption: Story = {
  args: { label: '보기 방식', options: [], value: '', onChange: () => undefined },
  render: () => (
    <Interactive
      initial="list"
      options={[
        { value: 'list', label: '목록' },
        { value: 'map', label: '지도', disabledReason: '준비 중' },
      ]}
    />
  ),
};
