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

// Cause ② from #272: the candidate the fallback picks is unmounted by the very
// change that closed the dialog.
//
// The shape is TripScreen's: the confirm lives inside the moved item's own
// <article> (TripScreen.tsx renders LockRow and ItemMoveControls there), so
// completing a move to another day unmounts that <article> and every candidate
// under `dialog.parentElement` goes with it. The fallback then focuses a
// detached node, which is a no-op, and focus stays on <body>.
//
// This IS observable in happy-dom, unlike cause ①, and that asymmetry was
// measured rather than assumed: happy-dom lets `.focus()` succeed inside a
// CLOSED DIALOG (so ① needs an e2e run to see its consequence) but correctly
// refuses `.focus()` on a DETACHED node, leaving activeElement where it was.
// So the landing can be asserted here — which is what these tests do.
describe('ConfirmDialog restores focus when its own container unmounts', () => {
  // The itinerary the traveller keeps working in after the move: a landmark
  // outside the row, which is what survives when the row goes.
  function MovableRow() {
    const [open, setOpen] = useState(false);
    const [moved, setMoved] = useState(false);
    return (
      <div>
        <button type="button">일정 다시 계산</button>
        {moved ? null : (
          // The <article> that the completed move unmounts, with the dialog and
          // every one of its in-row candidates inside it.
          <article>
            <button type="button">경복궁 잠금 해제</button>
            <button onClick={() => setOpen(true)} type="button">
              경복궁을 다른 날로
            </button>
            <ConfirmDialog
              cancelLabel="취소"
              confirmLabel="옮기기"
              onCancel={() => setOpen(false)}
              onConfirm={() => {
                // The move lands: the dialog closes AND the row unmounts in the
                // same commit, which is the whole of the defect.
                setOpen(false);
                setMoved(true);
              }}
              open={open}
              title="3일차로 옮길까요?"
            />
          </article>
        )}
      </div>
    );
  }

  it('does not leave focus on <body> when the move unmounts the row', async () => {
    const user = userEvent.setup();
    render(<MovableRow />);

    const trigger = screen.getByRole('button', { name: '경복궁을 다른 날로' });
    await user.click(trigger);
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    // The row really is gone — without this the test could pass by the move
    // never happening, and the assertion below would be about nothing.
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '경복궁 잠금 해제' })).toBeNull();
    });

    await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      expect(
        active === document.body || active === null,
        'focus fell to <body>: the next Tab restarts at the top of the page',
      ).toBe(false);
    });
  });

  it('falls back to the main landmark when the row was the only control', async () => {
    // The last resort, and it needs its own shape: when the unmounted row held
    // every focusable control on the page, the outward search finds nothing and
    // `previous` stays null. Without a landmark to land on, focus would sit on
    // <body> — the same defect, reached by a different route.
    //
    // Written after measuring that removing the landmark branch left the two
    // tests above green: they always have a surviving sibling control, so they
    // never reach it. An untested branch here is the kind this repo keeps
    // getting bitten by.
    function OnlyRow() {
      const [open, setOpen] = useState(false);
      const [moved, setMoved] = useState(false);
      return (
        <main>
          {moved ? (
            <p>3일차로 옮겼어요</p>
          ) : (
            <article>
              <button onClick={() => setOpen(true)} type="button">
                경복궁을 다른 날로
              </button>
              <ConfirmDialog
                cancelLabel="취소"
                confirmLabel="옮기기"
                onCancel={() => setOpen(false)}
                onConfirm={() => {
                  setOpen(false);
                  setMoved(true);
                }}
                open={open}
                title="3일차로 옮길까요?"
              />
            </article>
          )}
        </main>
      );
    }

    const user = userEvent.setup();
    render(<OnlyRow />);
    await user.click(screen.getByRole('button', { name: '경복궁을 다른 날로' }));
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    await waitFor(() => {
      expect(screen.getByText('3일차로 옮겼어요')).toBeInTheDocument();
    });

    await waitFor(() => {
      expect(document.activeElement?.tagName).toBe('MAIN');
    });

    // Chromium drops focus back to <body> if tabindex is removed while <main>
    // is still focused. Keep it programmatically focusable until focus leaves,
    // then restore the page's original markup.
    const main = document.querySelector('main');
    expect(main?.hasAttribute('tabindex')).toBe(true);
    const next = document.createElement('button');
    document.body.append(next);
    next.focus();
    expect(main?.hasAttribute('tabindex')).toBe(false);
    next.remove();
  });

  it('leaves focus on a node that is still in the page', async () => {
    // Separate from the clause above because they fail differently: a fallback
    // that picks a detached node in-row satisfies neither, but one that picks a
    // node which is merely unfocusable would still be "not body" in happy-dom.
    // Asserting connectedness names what the traveller needs — a real place to
    // Tab from.
    const user = userEvent.setup();
    render(<MovableRow />);

    await user.click(screen.getByRole('button', { name: '경복궁을 다른 날로' }));
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '경복궁 잠금 해제' })).toBeNull();
    });

    await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      expect(active?.isConnected ?? false).toBe(true);
    });
  });
});

// Cause ④ from #272: the opener survives the change but cannot take focus.
//
// Found in a browser (#272, session 51) after the unmount fix had landed and
// the restored e2e test still failed — 6/6 once it waited 600ms for focus to
// settle, which is the same coin-flip shape the issue measured originally.
// Instrumentation showed the restore routine running, the target connected and
// visible, and `disabled=true`: ItemMoveControls disables the move trigger
// while `busy`, and the reorder mutation is still in flight at the moment the
// confirm closes. `.focus()` on a disabled button is a no-op.
//
// This one IS fully measurable here, unlike ① and ③. Measured: happy-dom
// refuses focus on a disabled button and leaves activeElement where it was
// (`dis_tookFocus=false`), matching a real browser — while it wrongly allows
// focus on `inert` and `display:none` elements. So the landing assertion below
// is real, not a stand-in.
describe('ConfirmDialog restores focus when the opener cannot take it', () => {
  // The opener is alive, connected and visible — and disabled, because the
  // mutation it started has not settled yet.
  //
  // The confirm is UNMOUNTED once the move is under way, and that detail is
  // load-bearing rather than decoration: with the dialog left mounted, focus
  // simply stays on its own confirm button and the defect does not appear at
  // all (measured — the first version of this harness passed against the
  // unfixed component for exactly that reason). MoveDaySheet's confirm is
  // rendered conditionally, so the real screen has no such button to hold
  // focus.
  function BusyOpener() {
    const [open, setOpen] = useState(false);
    const [busy, setBusy] = useState(false);
    return (
      <div>
        <button type="button">일정 다시 계산</button>
        <article>
          <button disabled={busy} onClick={() => setOpen(true)} type="button">
            경복궁을 다른 날로
          </button>
          {open || !busy ? (
            <ConfirmDialog
              cancelLabel="취소"
              confirmLabel="옮기기"
              onCancel={() => setOpen(false)}
              onConfirm={() => {
                // The real order: the request starts and the dialog closes in
                // the same commit, so the trigger is disabled exactly when the
                // restore runs.
                setBusy(true);
                setOpen(false);
              }}
              open={open}
              title="3일차로 옮길까요?"
            />
          ) : null}
        </article>
      </div>
    );
  }

  it('does not leave focus on <body> when the opener is disabled', async () => {
    const user = userEvent.setup();
    render(<BusyOpener />);

    const trigger = screen.getByRole('button', { name: '경복궁을 다른 날로' });
    await user.click(trigger);
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    // The premise really holds: still in the page, and refusing focus. Without
    // this the test could pass by the opener never becoming disabled, and the
    // assertion below would be about a case that never happened.
    await waitFor(() => {
      expect(trigger).toBeDisabled();
    });
    expect(trigger.isConnected).toBe(true);

    await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      expect(
        active === document.body || active === null,
        'focus fell to <body>: the next Tab restarts at the top of the page',
      ).toBe(false);
    });
  });

  it('skips candidates the selector cannot exclude: disabled fieldset, inert', async () => {
    // The two cases `button:not([disabled])` cannot express, because both are
    // inherited rather than marked on the control: a <fieldset disabled>
    // disables its controls without giving them the attribute, and `inert`
    // applies to a whole subtree. Measured in happy-dom — both match FOCUSABLE.
    //
    // Written after a mutation showed the candidate-side filter was reachable
    // but untested: removing it left all other tests green while the scan
    // rejected four candidates in this shape.
    function UnfocusableNeighbours() {
      const [open, setOpen] = useState(false);
      const [busy, setBusy] = useState(false);
      return (
        <main>
          <fieldset disabled>
            <button type="button">비활성 묶음 안 버튼</button>
          </fieldset>
          <div inert>
            <button type="button">inert 안 버튼</button>
          </div>
          <article>
            <button disabled={busy} onClick={() => setOpen(true)} type="button">
              여는 버튼
            </button>
            {open || !busy ? (
              <ConfirmDialog
                cancelLabel="취소"
                confirmLabel="옮기기"
                onCancel={() => setOpen(false)}
                onConfirm={() => {
                  setBusy(true);
                  setOpen(false);
                }}
                open={open}
                title="3일차로 옮길까요?"
              />
            ) : null}
          </article>
        </main>
      );
    }

    const user = userEvent.setup();
    render(<UnfocusableNeighbours />);
    await user.click(screen.getByRole('button', { name: '여는 버튼' }));
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '여는 버튼' })).toBeDisabled();
    });

    await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      // Neither unfocusable neighbour, and not <body>: the landmark is the
      // honest answer when nothing on the page can hold focus.
      expect(active?.textContent).not.toBe('비활성 묶음 안 버튼');
      expect(active?.textContent).not.toBe('inert 안 버튼');
      expect(active).not.toBe(document.body);
    });
  });

  it('does not claim the disabled opener took focus', async () => {
    // The sharper form: "not body" would also be satisfied by focus sitting on
    // some third thing. What the traveller needs is that focus is NOT on the
    // control that cannot hold it.
    const user = userEvent.setup();
    render(<BusyOpener />);

    const trigger = screen.getByRole('button', { name: '경복궁을 다른 날로' });
    await user.click(trigger);
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    await waitFor(() => {
      expect(trigger).toBeDisabled();
    });

    await waitFor(() => {
      expect(document.activeElement).not.toBe(trigger);
      expect(document.activeElement).not.toBe(document.body);
    });
  });
});

// Cause ⑤ from #272: the candidate the search finds is REPLACED, not removed.
//
// Found in a browser (session 51) with the cause-④ fix in place. Its trace
// shows the fix working — the disabled opener is rejected and the search runs —
// and then picking `Set Time locked`, a control inside the very row the move
// re-renders. Focus lands on it, the row re-renders, and the node it landed on
// is swapped for a new one. Final state: <body>, 3/3 runs.
//
// This is NOT cause ②, and the difference decides the fix. There the <article>
// unmounted, so every in-row candidate was already detached and `isConnected`
// saw it. Here the row SURVIVES (51 measured `rows:3`, unchanged across the
// move) and React replaces its children. Until the replacement, the candidate
// is connected, enabled and focusable — `canTakeFocus` and the landing check
// are both true at the moment they run, and become false afterwards.
//
// So no post-hoc verification can catch it: the check is correct when it runs.
// Measured here: a keyed re-render detaches the old node
// (`keyedStillConnected: false`) and focus is lost, while a node outside the
// re-rendered subtree keeps it (`focusStuckToSurvivor: true`). The fix is
// therefore to prefer a candidate that outlives the change, not to re-check
// later — waiting makes it worse, which is what 51's `waitForTimeout(600)`
// measured: 6/6 failures rather than fewer.
describe('ConfirmDialog restores focus when the row is re-rendered, not removed', () => {
  // The shape of the defect: the row stays in the page but its contents are
  // replaced, exactly as a completed move re-renders the itinerary row.
  function RerenderingRow() {
    const [open, setOpen] = useState(false);
    const [moved, setMoved] = useState(0);
    return (
      <main>
        <section>
          <h1>서울 가을 여행</h1>
          {/* Outside the row and outside the dialog: what a traveller can
              still Tab from once the row has been redrawn. */}
          <button type="button">일정 다시 계산</button>
        </section>
        <section>
          {/* `key` forces React to replace the subtree rather than update it,
              which is what the real move does to the row it rewrites. */}
          <article key={moved}>
            <button type="button">Set Time locked</button>
            <button onClick={() => setOpen(true)} type="button">
              경복궁을 다른 날로
            </button>
            <ConfirmDialog
              cancelLabel="취소"
              confirmLabel="옮기기"
              onCancel={() => setOpen(false)}
              onConfirm={() => {
                setOpen(false);
                setMoved((n) => n + 1);
              }}
              open={open}
              title="3일차로 옮길까요?"
            />
          </article>
        </section>
      </main>
    );
  }

  // WHICH OF THESE TWO ACTUALLY FIRES HERE, measured rather than assumed: only
  // the second. happy-dom leaves `activeElement` pointing at the REPLACED node
  // and still reports `isConnected: true` for it, where a real browser resets
  // focus to <body> — so the `<body>` clause below passes even with the defect
  // present, exactly as it did before this fix. It is kept because it names
  // what the traveller actually loses and it is the clause the e2e suite can
  // assert; the clause that fails here is the one that checks focus is not
  // inside the row about to be rewritten.
  it('does not leave focus on <body> when the row is redrawn', async () => {
    const user = userEvent.setup();
    render(<RerenderingRow />);

    await user.click(screen.getByRole('button', { name: '경복궁을 다른 날로' }));
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    // The premise: the row really was replaced, and really is still here.
    // Without both, this test could pass against a page where nothing happened.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Set Time locked' })).toBeInTheDocument();
    });

    await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      expect(
        active === document.body || active === null,
        'focus fell to <body>: the next Tab restarts at the top of the page',
      ).toBe(false);
    });
  });

  it('puts focus somewhere the redraw cannot take away', async () => {
    // The sharper clause: "not body" would also be satisfied by landing on a
    // node inside the row that is about to be replaced again. What the
    // traveller needs is a place that survives the change.
    const user = userEvent.setup();
    render(<RerenderingRow />);

    await user.click(screen.getByRole('button', { name: '경복궁을 다른 날로' }));
    await user.click(await screen.findByRole('button', { name: '옮기기' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Set Time locked' })).toBeInTheDocument();
    });

    await waitFor(() => {
      const active = document.activeElement as HTMLElement | null;
      expect(active?.isConnected ?? false).toBe(true);
      // Not inside the row that the move rewrites.
      expect(active?.closest('article')).toBeNull();
    });
  });
});
