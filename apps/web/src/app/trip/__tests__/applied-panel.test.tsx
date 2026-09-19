// @vitest-environment happy-dom
//
// The applied-optimization panel (S09-3, FE-503). Figma: 417:2412 AVAILABLE,
// 724:4602 submitting, 724:4730 REVERTED, 724:4858 EXPIRED.
//
// The promise this file guards: the panel offers undo when the SERVER says it
// can, and never otherwise. The contract is explicit —
//
//   "Absence means unknown support, never permission to enable undo using
//    client time."
//
// so the absent case has its own test and is the one that matters most. A
// panel that guessed from `revertUntil` and a device clock would pass every
// other assertion here.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AppliedPanel, type AppliedPanelProps } from '../AppliedPanel.js';

// Deliberately distinctive, like proposal-card.test.tsx's: an assertion must
// not be satisfiable by some other element's text.
const LABELS = {
  badgeAvailable: 'BADGE_AVAILABLE',
  badgeSubmitting: 'BADGE_SUBMITTING',
  badgeReverted: 'BADGE_REVERTED',
  badgeExpired: 'BADGE_EXPIRED',
  revisionAvailable: 'REVISION_AVAILABLE',
  revisionExpired: 'REVISION_EXPIRED',
  revisionReverted: 'REVISION_REVERTED',
  revert: 'REVERT_BUTTON',
  reverting: 'REVERTING_BUTTON',
  expired: 'EXPIRED_BUTTON',
  badgeFailed: 'BADGE_FAILED',
  retry: 'RETRY_BUTTON',
};

// The numbers are run-applied.json's: inputTripVersion 2, the APPLY decision's
// resultingTripVersion 3. Kept as the fixture has them so the panel is
// exercised against the shape the server actually sends.
function panel(overrides: Partial<AppliedPanelProps> = {}) {
  const props: AppliedPanelProps = {
    availability: 'AVAILABLE',
    summary: 'SERVER_SUMMARY',
    fromVersion: 2,
    toVersion: 3,
    appliedAt: 'APPLIED_AT',
    revertUntil: 'REVERT_UNTIL',
    labels: LABELS,
    ...overrides,
  };
  return render(<AppliedPanel {...props} />);
}

function revertButton() {
  return screen.queryByRole('button');
}

// FE-505-T1's second half — "undo가 24시간 창을 벗어나면 명시적으로 거부된다".
// The EXPIRED case below is that clause: the button stays on screen and
// disabled rather than vanishing, so the window having closed is something the
// user can see rather than infer from an absence.
//
// FE-505-T2 as well, and the case COUNT here is still not the evidence for
// that. The first six cases are the six values of `RevertAvailability` (absent,
// NOT_APPLICABLE, AVAILABLE, submitting, REVERTED, EXPIRED) — a different axis
// that happens to have the same size as the clause list, which is exactly how a
// matrix can look complete while two clauses go unmeasured. It did: four of the
// six clauses were covered while the count read as all six. Mapped to the
// clause words:
//
//   기본    → AVAILABLE, the panel with a pressable undo
//   loading → submitting, the revert request in flight
//   empty   → absent and NOT_APPLICABLE, nothing to draw
//   stale   → EXPIRED, the 24h window closed underneath the user
//   error   → a failed revert, retryable and not (the two cases at the end)
//   offline → the same `failure` prop: a transport failure is not a Problem, so
//             it carries no code and takes the retryable branch. That mapping
//             is the WRAPPER's, so trip-applied-panel.test.tsx proves it and
//             this file only proves the prop renders.
//
// ERROR AND OFFLINE now have a seventh case below, and the S09-3 question this
// comment used to leave open has an answer. Both were measured in Figma:
//
//   * S09-3 has exactly FOUR frames (417:2412, 724:4602, 724:4730, 724:4858).
//     There is no fifth, and all four applied-panel instances share the same
//     three rows — result-row, revision-line, btn/revert — with no error slot.
//   * The S09 error reference (417:2567) lists six codes and says, in the
//     frame itself, that they render as "S09 Preview 위 배너". That is the
//     PREVIEW screen, and no code in the list is a revert or a read failure.
//
// So the design says nothing about a failed undo, and the two halves of the
// gap resolve differently rather than together:
//
//   READ FAILURE — intended. `TripAppliedPanel` returning null when
//   `run.data` is undefined folds "the read failed" into "there is nothing to
//   undo", and that is acceptable HERE because this panel is a section of the
//   trip screen rather than a screen: all four frames draw it wedged between
//   the trip header and the day cards, which stay up. The traveller is not
//   stranded, and the only loss is an undo that existed going unseen. Figma
//   draws no banner for it, so neither do we.
//
//   REVERT FAILURE — a defect, now fixed. `revert.isError` was read nowhere in
//   apps/web (measured: zero hits outside tests), so pressing 되돌리기 and
//   getting a 503 returned the button to its resting state and said nothing:
//   indistinguishable from never having pressed. Figma's silence does not
//   cover this one, for two reasons — EXPIRED (724:4869) keeps a VISIBLE
//   disabled button rather than letting the closed window be inferred from an
//   absence, which is this panel's own norm for an unavailable undo; and
//   REVERT_WINDOW_EXPIRED was already wired end to end (problem-policy.ts:171,
//   error copy, and an msw handler that emits it) with nothing rendering it.
//
// The failed case therefore uses the two slots the design already has, the
// badge and the button, instead of a banner S09-3 does not draw.
//
// An earlier version of this comment sent offline to
// `shared/testing/__tests__/offline-shell.test.ts`. That file carries only
// FE-004-T1 and renders nothing — see optimization-run.test.tsx's T2 block,
// which carried the same pointer and now explains why it did not hold.
describe('FE-505-T1 FE-505-T2 the panel offers undo only when the server says so', () => {
  it('renders nothing at all when revertAvailability is absent', async () => {
    // The contract case: the field is optional, and its absence means "unknown
    // support". The panel has `revertUntil` in hand here — a panel that
    // compared it to Date.now() would show a button, and this is the assertion
    // that catches it.
    const { container } = panel({ availability: undefined });

    expect(container).toBeEmptyDOMElement();
    expect(revertButton()).not.toBeInTheDocument();
  });

  it('renders nothing when the server says NOT_APPLICABLE', () => {
    const { container } = panel({ availability: 'NOT_APPLICABLE' });

    expect(container).toBeEmptyDOMElement();
  });

  it('offers the revert button when the server says AVAILABLE', async () => {
    const onRevert = vi.fn();
    const user = userEvent.setup();
    panel({ onRevert });

    expect(screen.getByText(LABELS.badgeAvailable)).toBeInTheDocument();
    expect(screen.getByText(LABELS.revisionAvailable)).toBeInTheDocument();

    const button = screen.getByRole('button', { name: LABELS.revert });
    expect(button).toBeEnabled();

    await user.click(button);
    expect(onRevert).toHaveBeenCalledTimes(1);
  });

  it('shows the pending button and refuses a second press while submitting', async () => {
    // Invariant 6's shape on screen: the request is in flight, so the control
    // must not queue another one.
    const onRevert = vi.fn();
    const user = userEvent.setup();
    panel({ onRevert, submitting: true });

    expect(screen.getByText(LABELS.badgeSubmitting)).toBeInTheDocument();
    const button = screen.getByRole('button', { name: LABELS.reverting });
    expect(button).toBeDisabled();

    // The revision line still describes what the APPLY did, and submitting
    // shares that string with `available`. Sharing is why the badge's own
    // cross-detection does not reach here: every other slot differs per frame,
    // so a wrong value shows up as some other case's string, but three frames
    // read the same revision. Measured: with the submitting frame's revision
    // switched to the EXPIRED wording, all ten cases of this describe stayed
    // green — a panel mid-revert could say the 24h window had closed.
    expect(screen.getByText(LABELS.revisionAvailable)).toBeInTheDocument();
    expect(screen.queryByText(LABELS.revisionExpired)).not.toBeInTheDocument();

    await user.click(button);
    expect(onRevert).not.toHaveBeenCalled();
  });

  it('keeps showing submitting even while the server still says AVAILABLE', () => {
    // The request is in flight against exactly that value. Reading the server
    // field here would flicker the button back to pressable mid-request.
    panel({ availability: 'AVAILABLE', submitting: true });

    expect(screen.getByText(LABELS.badgeSubmitting)).toBeInTheDocument();
    expect(screen.queryByText(LABELS.badgeAvailable)).not.toBeInTheDocument();
  });

  it('drops the button entirely once the decision is REVERTED', () => {
    // 724:4730: the panel shrinks to 62px because the button is gone. There is
    // nothing left to undo, so a disabled control would only invite a press.
    panel({ availability: 'REVERTED' });

    expect(screen.getByText(LABELS.badgeReverted)).toBeInTheDocument();
    expect(screen.getByText(LABELS.revisionReverted)).toBeInTheDocument();
    expect(revertButton()).not.toBeInTheDocument();
  });

  it('keeps a disabled button on EXPIRED, pointing somewhere else', async () => {
    // 724:4858 keeps the control rather than removing it: a button that
    // vanished would leave the traveller hunting for an undo that was there
    // yesterday. It says where to go instead.
    const onRevert = vi.fn();
    const user = userEvent.setup();
    panel({ availability: 'EXPIRED', onRevert });

    expect(screen.getByText(LABELS.badgeExpired)).toBeInTheDocument();
    expect(screen.getByText(LABELS.revisionExpired)).toBeInTheDocument();

    const button = screen.getByRole('button', { name: LABELS.expired });
    expect(button).toBeDisabled();

    await user.click(button);
    expect(onRevert).not.toHaveBeenCalled();
  });

  it('says a retryable revert failed, and offers the same command again', async () => {
    // The clause `error`. Before this the panel had no way to express a failed
    // attempt: `revert.isError` reached nothing, so the button returned to
    // REVERT_BUTTON and the screen was identical to never having pressed.
    //
    // The retry presses `onRevert` — the SAME callback, which replays the same
    // idempotency key. There is deliberately no second handler to press.
    const onRevert = vi.fn();
    const user = userEvent.setup();
    panel({
      onRevert,
      failure: { message: 'FAILURE_MESSAGE', retryable: true },
    });

    expect(screen.getByText(LABELS.badgeFailed)).toBeInTheDocument();
    // Announced rather than merely present: the failure lands after a press,
    // so a screen-reader user has to be told the schedule did not change.
    expect(screen.getByRole('alert')).toHaveTextContent('FAILURE_MESSAGE');
    // The revision line describes the APPLY, not the failed undo, so it keeps
    // the `available` wording — and shares that string with two other frames,
    // which is why nothing here caught it being wrong. Measured: with the
    // failed frame's revision switched to the EXPIRED wording, every case in
    // this file stayed green, so a retryable failure could tell the traveller
    // the window had closed while offering them a retry.
    expect(screen.getByText(LABELS.revisionAvailable)).toBeInTheDocument();
    expect(screen.queryByText(LABELS.revisionExpired)).not.toBeInTheDocument();

    const button = screen.getByRole('button', { name: LABELS.retry });
    expect(button).toBeEnabled();

    await user.click(button);
    expect(onRevert).toHaveBeenCalledTimes(1);
  });

  it('refuses a retry when the failure was the window closing', async () => {
    // The half that makes the case above mean something. Folding both failures
    // into one "it failed" state would pass that test while telling the
    // traveller to try again in the one case where trying cannot work:
    // REVERT_WINDOW_EXPIRED is retry:'none' and recovery:'none' in
    // problem-policy.ts, and the server has already refused for good.
    //
    // `retryable` is the caller's reading of that policy. This file asserts
    // only that the panel obeys it — which is why a failure with the same
    // words but retryable:false must NOT be pressable.
    const onRevert = vi.fn();
    const user = userEvent.setup();
    panel({
      onRevert,
      failure: { message: 'TERMINAL_MESSAGE', retryable: false },
    });

    expect(screen.getByText(LABELS.badgeFailed)).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('TERMINAL_MESSAGE');

    const button = screen.getByRole('button', { name: LABELS.retry });
    expect(button).toBeDisabled();

    await user.click(button);
    expect(onRevert).not.toHaveBeenCalled();
  });

  it('does not draw a failure onto a state that has no undo to fail', () => {
    // A failure reported against REVERTED would otherwise conjure a panel out
    // of a state whose frame has no button at all. The wrapper cannot produce
    // this — it only mutates from AVAILABLE — but the prop admits it, and a
    // frame nothing can reach is the mirror of an assertion nothing can fire.
    panel({ availability: 'REVERTED', failure: { message: 'X', retryable: true } });

    expect(screen.getByText(LABELS.badgeReverted)).toBeInTheDocument();
    expect(screen.queryByText(LABELS.badgeFailed)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('the panel states the server sentence rather than composing one', () => {
  it('renders the summary verbatim', () => {
    // Invariant 9: the panel does not decide what changed. A sentence built
    // here from versions and place names would be a claim with no source.
    panel({ summary: '경복궁이 day 3 (10/6)으로 옮겨졌어요' });

    expect(screen.getByText('경복궁이 day 3 (10/6)으로 옮겨졌어요')).toBeInTheDocument();
  });

  it('renders the caller’s finished revision line without reassembling it', () => {
    // The caller's t() has already substituted the versions and both times.
    // This file asserts the string arrives whole: a panel that concatenated
    // clauses would break a locale that orders them differently.
    panel({ labels: { ...LABELS, revisionAvailable: '일정 v2 → v3 · 전부 한 문장' } });

    expect(screen.getByText('일정 v2 → v3 · 전부 한 문장')).toBeInTheDocument();
  });
});
