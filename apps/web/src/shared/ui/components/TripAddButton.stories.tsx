import type { Meta, StoryObj } from '@storybook/react-vite';
import { TripAddButton, type TripAddState } from './TripAddButton.js';

// Figma: `Action / TripAddButton` (C04).
const meta = {
  title: 'Action/TripAddButton',
  component: TripAddButton,
} satisfies Meta<typeof TripAddButton>;

export default meta;
type Story = StoryObj<typeof meta>;

const ALL: TripAddState[] = ['idle', 'saved', 'duplicate', 'no-trip', 'loading', 'error'];

/** Six states, each with its own accessible name. */
export const AllStates: Story = {
  args: { state: 'idle' },
  render: () => (
    <div style={{ display: 'flex', gap: 12 }}>
      {ALL.map((state) => (
        <TripAddButton key={state} state={state} />
      ))}
    </div>
  ),
};

/** Only the in-flight request blocks input. */
export const Loading: Story = { args: { state: 'loading' } };

/** A failure stays retryable. */
export const Error: Story = { args: { state: 'error' } };
