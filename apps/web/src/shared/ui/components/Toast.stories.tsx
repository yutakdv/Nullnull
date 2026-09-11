import type { Meta, StoryObj } from '@storybook/react-vite';
import { Toast } from './Toast.js';

// Figma: `Feedback / Toast` (C35). tone=info|error.
//
// A toast is supplementary feedback, never the only place a result lives: an
// undo offered here also exists as persistent UI (FCR-015), because a toast
// that disappears takes the recovery path with it.
const meta = {
  title: 'Feedback/Toast',
  component: Toast,
  args: { message: '후보로 담았어요' },
} satisfies Meta<typeof Toast>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Info: Story = {};

export const WithAction: Story = {
  args: { message: '일정에 적용했어요', actionLabel: '되돌리기' },
};

export const Error: Story = {
  args: { message: '일정은 바뀌지 않았어요', tone: 'error', actionLabel: '다시 시도' },
};

/** Long copy wraps rather than clipping; the message is the point. */
export const LongMessage: Story = {
  args: {
    message: '확인한 후보에서는 더 나은 변경을 찾지 못했어요. 현재 일정을 유지할게요.',
    actionLabel: '확인',
  },
  decorators: [
    (Story) => (
      <div style={{ width: 320 }}>
        <Story />
      </div>
    ),
  ],
};
