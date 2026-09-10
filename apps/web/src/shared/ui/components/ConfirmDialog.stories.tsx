import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { ConfirmDialog } from './ConfirmDialog.js';

// Figma: S07-9 폐기 dialog `413:2020`.
//
// Rendered through a trigger rather than with `open` forced on, because the
// behaviour worth reviewing is the part a static screenshot cannot show: the
// focus trap, Escape, and focus returning to the button that opened it.
const meta = {
  title: 'Overlay/ConfirmDialog',
  component: ConfirmDialog,
  args: {
    open: true,
    title: '변경 내용을 버릴까요?',
    body: '저장하지 않은 변경이 있어요. 버리면 되돌릴 수 없어요.',
    confirmLabel: '나가기',
    cancelLabel: '계속 편집',
    onConfirm: () => undefined,
    onCancel: () => undefined,
  },
} satisfies Meta<typeof ConfirmDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The dirty-exit case. The cancel button — the one that keeps the user's work —
 * takes focus, so Enter on an unread dialog never discards an edit.
 */
export const DiscardChanges: Story = {
  render: (args) => {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button
          onClick={() => {
            setOpen(true);
          }}
          type="button"
        >
          편집 종료
        </button>
        <ConfirmDialog
          {...args}
          onCancel={() => {
            setOpen(false);
          }}
          onConfirm={() => {
            setOpen(false);
          }}
          open={open}
        />
      </>
    );
  },
};

/** Without a body, for a confirm whose title says everything. */
export const TitleOnly: Story = {
  args: { body: undefined, title: '이 항목을 삭제할까요?', confirmLabel: '삭제' },
};

/**
 * Destructive carries the same visual weight on purpose: the wording is what
 * warns, so the meaning survives forced colours and colour-blindness.
 */
export const Destructive: Story = {
  args: { destructive: true },
};
