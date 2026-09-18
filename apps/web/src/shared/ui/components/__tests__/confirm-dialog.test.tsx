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

// The restore fallback, for the case where the opener is gone by the time the
// dialog closes: one surface opens this dialog and dismisses itself doing it,
// so `restoreTo` holds a detached node and focus has to go somewhere else.
//
// What this file CAN measure and what it cannot, measured rather than assumed:
// happy-dom's `parentElement.querySelectorAll` DOES return elements inside a
// closed <dialog>, exactly as a browser does — so WHICH element the fallback
// picks is observable here. But happy-dom lets `.focus()` succeed on an element
// inside a closed <dialog>, where a real browser makes it a no-op — so the
// CONSEQUENCE (focus ends up on <body>) is not observable here, and the e2e
// suite is where that was seen.
//
// So this asserts the choice, not the landing. That is the whole of the fix:
// a closed sibling dialog's button must not be chosen.
describe('ConfirmDialog restores focus past closed sibling dialogs', () => {
  it('skips a closed sibling dialog and takes the nearest real control', async () => {
    const user = userEvent.setup();

    // The shape TripScreen actually renders: LockRow's confirm sits mounted and
    // closed in the same container as the one being opened and closed
    // (TripScreen.tsx:358,362). Its Cancel button is earlier in document order
    // than the live dialog, so before the fix it won the "previous focusable"
    // search — and it is unfocusable in a browser.
    function Siblings() {
      const [open, setOpen] = useState(false);
      return (
        <div>
          <button onClick={() => setOpen(true)} type="button">
            실제 컨트롤
          </button>
          <dialog>
            <button type="button">닫힌 형제의 취소</button>
          </dialog>
          <ConfirmDialog
            cancelLabel="취소"
            confirmLabel="확인"
            onCancel={() => setOpen(false)}
            onConfirm={() => setOpen(false)}
            open={open}
            title="확인"
          />
        </div>
      );
    }

    render(<Siblings />);
    const opener = screen.getByRole('button', { name: '실제 컨트롤' });
    await user.click(opener);

    // Detach the opener while the dialog is up, which is the situation the
    // fallback exists for: `restoreTo` now points at a node out of the page.
    opener.remove();

    await user.click(await screen.findByRole('button', { name: '취소' }));

    await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      // Not the closed sibling's button. Naming the element rather than
      // asserting "not body" is deliberate: "not body" passes in happy-dom even
      // with the bug present, because happy-dom focuses the unfocusable button
      // successfully.
      expect(active?.textContent).not.toBe('닫힌 형제의 취소');
    });
  });
});
