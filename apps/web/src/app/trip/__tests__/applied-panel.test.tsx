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

describe('the panel offers undo only when the server says so', () => {
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
