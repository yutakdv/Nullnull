// @vitest-environment happy-dom
//
// C02 `Sheet / TripPicker` (FR-CAN-01, S03-C1 `399:658` / S06-1 `409:1595`).
//
// The sheet only CHOOSES. It sends nothing, so these assert what it shows and
// what it reports back — the save itself belongs to the caller's mutation, and
// keeping it there is what stops a picker from becoming a second place where a
// candidate gets written (invariant 1).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TripPicker, type TripPickerProps } from '../TripPicker.js';

const LABELS = {
  title: 'Which trip?',
  cancel: 'Cancel',
  loading: 'Loading trips',
  empty: 'No trips yet',
  createTrip: 'Create a trip',
  error: "Couldn't load trips",
  retry: 'Try again',
};

const TRIPS = [
  {
    id: 'trip-1',
    title: '서울 가을 여행',
    startDate: '2026-10-04',
    endDate: '2026-10-07',
    timezone: 'Asia/Seoul',
    status: 'ACTIVE',
    version: 3,
    candidateCount: 5,
  },
  {
    id: 'trip-2',
    title: '부산 겨울 여행',
    startDate: '2026-12-20',
    endDate: '2026-12-23',
    timezone: 'Asia/Seoul',
    status: 'DRAFT',
    version: 1,
    candidateCount: 0,
  },
  // `satisfies`, not `as unknown as`: the cast laundered the fixture past
  // typecheck, and it was hiding a status the contract does not have
  // (SCHEDULED belongs to CandidateStatus, not TripStatus). A wrong value here
  // must fail `npm run typecheck` rather than teach the next reader a state
  // the server can never send.
] satisfies TripPickerProps['trips'];

function renderPicker(overrides: Partial<TripPickerProps> = {}) {
  const props: TripPickerProps = {
    open: true,
    placeName: '경복궁',
    trips: TRIPS,
    selectedTripId: 'trip-1',
    labels: LABELS,
    onPick: vi.fn(),
    onCancel: vi.fn(),
    ...overrides,
  };
  return { props, ...render(<TripPicker {...props} />) };
}

describe('FE-203-T1 the picker reports a choice and saves nothing itself', () => {
  it('lists every trip the owner has, not just the first', () => {
    // The defect this component exists to fix: the feed took items[0] and the
    // other trips were unreachable.
    renderPicker();
    expect(screen.getByRole('button', { name: /서울 가을 여행/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /부산 겨울 여행/ })).toBeInTheDocument();
  });

  it('answers with the trip id the user pressed', async () => {
    const user = userEvent.setup();
    const { props } = renderPicker();
    await user.click(screen.getByRole('button', { name: /부산 겨울 여행/ }));
    expect(props.onPick).toHaveBeenCalledWith('trip-2');
  });

  it('marks the trip already being collected into without removing it', () => {
    // Removing the current trip from the list would move every other row under
    // the user's finger between one save and the next.
    renderPicker();
    const current = screen.getByRole('button', { name: /서울 가을 여행/ });
    expect(current).toHaveAttribute('aria-current', 'true');
    expect(current).toBeEnabled();
  });

  it('names the place being filed', () => {
    // Without it the sheet asks "which trip?" about nothing in particular, and
    // that is the same question on every card.
    renderPicker();
    expect(screen.getByText('경복궁')).toBeInTheDocument();
  });
});

describe('FE-203-T2 each state renders as itself', () => {
  it('says it is loading rather than showing an empty list', () => {
    // An empty list while loading reads as "you have no trips", which is a
    // different fact.
    renderPicker({ trips: [], loading: true, selectedTripId: null });
    expect(screen.getByRole('status')).toHaveTextContent(LABELS.loading);
    expect(screen.queryByRole('button', { name: /여행/ })).toBeNull();
  });

  it('offers trip creation when there are none, instead of describing the problem', async () => {
    const onCreateTrip = vi.fn();
    const user = userEvent.setup();
    renderPicker({ trips: [], selectedTripId: null, onCreateTrip });
    expect(screen.getByText(LABELS.empty)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: LABELS.createTrip }));
    expect(onCreateTrip).toHaveBeenCalled();
  });

  it('distinguishes a failed list from an empty one, and offers a retry', async () => {
    // Empty is a fact about the account; failed is a fact about the request.
    // Only one of them is worth retrying.
    const onRetry = vi.fn();
    const user = userEvent.setup();
    renderPicker({ trips: [], selectedTripId: null, failed: true, onRetry });
    expect(screen.getByRole('alert')).toHaveTextContent(LABELS.error);
    expect(screen.queryByText(LABELS.empty)).toBeNull();
    await user.click(screen.getByRole('button', { name: LABELS.retry }));
    expect(onRetry).toHaveBeenCalled();
  });
});

describe('FE-203-T3 the sheet is operable without a mouse', () => {
  it('opens focused on the one control every state has', () => {
    // Focusing a trip row would land on nothing while the list is loading or
    // empty, so focus goes to Cancel.
    renderPicker();
    expect(screen.getByRole('button', { name: LABELS.cancel })).toHaveFocus();
  });

  it('closes on Escape', async () => {
    const user = userEvent.setup();
    const { props } = renderPicker();
    await user.keyboard('{Escape}');
    expect(props.onCancel).toHaveBeenCalled();
  });

  it('names each trip row with its title and dates', () => {
    // The accessible name is what a screen-reader user chooses by; two trips to
    // the same city are told apart by their dates.
    renderPicker();
    const row = screen.getByRole('button', { name: /서울 가을 여행/ });
    expect(within(row).getByText(/2026-10-04/)).toBeInTheDocument();
  });
});
