// @vitest-environment happy-dom
//
// ConfirmDialog behaviour, independent of any screen that uses it.
//
// Note on the environment: happy-dom implements <dialog> only partially — it
// does not fire `cancel` on Escape and does not enforce the modal focus trap.
// The component therefore handles Escape explicitly as well, which is what
// these tests exercise. The trap itself is the browser's and is covered by the
// Playwright suite rather than asserted here, because asserting it against a
// DOM that does not implement it would prove nothing.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ConfirmDialog } from '../ConfirmDialog.js';

function Harness({ onConfirm = () => undefined }: { onConfirm?: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        onClick={() => {
          setOpen(true);
        }}
        type="button"
      >
        열기
      </button>
      <ConfirmDialog
        body="저장하지 않은 변경이 있어요."
        cancelLabel="계속 편집"
        confirmLabel="나가기"
        onCancel={() => {
          setOpen(false);
        }}
        onConfirm={() => {
          setOpen(false);
          onConfirm();
        }}
        open={open}
        title="변경 내용을 버릴까요?"
      />
    </>
  );
}

describe('ConfirmDialog', () => {
  it('is not in the accessibility tree until it opens', () => {
    render(<Harness />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('is named by its title', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: '열기' }));
    expect(
      await screen.findByRole('dialog', { name: '변경 내용을 버릴까요?' }),
    ).toBeInTheDocument();
  });

  it('focuses the safe choice rather than the destructive one', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole('button', { name: '열기' }));
    // Enter on an unread dialog must not take the destructive path.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '계속 편집' })).toHaveFocus();
    });
  });

  it('closes on Escape without confirming', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    render(<Harness onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: '열기' }));
    await screen.findByRole('dialog');

    await user.keyboard('{Escape}');
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('returns focus to the control that opened it', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const trigger = screen.getByRole('button', { name: '열기' });
    await user.click(trigger);
    await user.click(await screen.findByRole('button', { name: '계속 편집' }));
    await waitFor(() => {
      expect(trigger).toHaveFocus();
    });
  });

  it('runs the confirm action only when confirmed', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    render(<Harness onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: '열기' }));
    await user.click(await screen.findByRole('button', { name: '나가기' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});
