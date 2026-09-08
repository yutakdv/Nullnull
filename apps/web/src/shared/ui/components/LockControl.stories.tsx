import type { Meta, StoryObj } from '@storybook/react-vite';
import { LockControl } from './LockControl.js';

// Figma: `Form / LockControl` (C08).
const meta = {
  title: 'Form/LockControl',
  component: LockControl,
} satisfies Meta<typeof LockControl>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The four lock types are independent; one row never implies another. */
export const Independent: Story = {
  args: { kind: 'date', state: 'unlocked', label: '날짜 고정' },
  render: () => (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      <LockControl kind="date" state="user-locked" label="날짜 고정" />
      <LockControl kind="time" state="unlocked" label="시간 고정" />
      <LockControl
        kind="reservation"
        state="reservation-locked"
        label="예약 고정"
        disabledReason="예약에서 관리해요"
      />
    </div>
  ),
};

/** A reservation lock is shown, not offered as a toggle. */
export const ReservationOwnedElsewhere: Story = {
  args: {
    kind: 'reservation',
    state: 'reservation-locked',
    label: '예약 고정',
    disabledReason: '예약에서 관리해요',
  },
};

export const Disabled: Story = {
  args: {
    kind: 'time',
    state: 'disabled',
    label: '시간 고정',
    disabledReason: '편집 모드에서 바꿀 수 있어요',
  },
};
