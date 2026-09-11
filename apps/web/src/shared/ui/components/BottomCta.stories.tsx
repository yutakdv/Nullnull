import type { Meta, StoryObj } from '@storybook/react-vite';
import { BottomCta } from './BottomCta.js';

// Figma: `Action / Bottom CTA` (C11). type=단독|보조링크.
//
// COMPONENT_CATALOG §4 requires interactive nodes to show pointer, keyboard,
// disabled and loading states, so those are the stories rather than a single
// happy-path render.
const meta = {
  title: 'Action/BottomCta',
  component: BottomCta,
  args: { label: '다음' },
} satisfies Meta<typeof BottomCta>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** With the secondary link the wizard steps use for "skip". */
export const WithSecondary: Story = {
  args: {
    label: '이대로 채우기',
    secondary: (
      <button type="button" style={{ border: 0, background: 'none' }}>
        건너뛰기
      </button>
    ),
  },
};

/**
 * Disabled while a form is incomplete. The label still says what the button
 * would do, so the state is readable without inferring it from the greying.
 */
export const Disabled: Story = {
  args: { label: '날짜를 선택해주세요', disabled: true },
};

/**
 * In flight. The label carries the state because a spinner alone tells a screen
 * reader nothing, and the button stays disabled so a second submit cannot fire.
 */
export const Submitting: Story = {
  args: { label: '여행을 만들고 있어요', disabled: true },
};

/** The longest Korean label the P0 screens use, at the narrowest width. */
export const LongLabelAtNarrowWidth: Story = {
  args: { label: '최신 일정으로 다시 계산하기' },
  parameters: { viewport: { defaultViewport: 'mobile1' } },
  decorators: [
    (Story) => (
      <div style={{ width: 320 }}>
        <Story />
      </div>
    ),
  ],
};
