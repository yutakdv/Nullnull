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
    locale: 'en-US',
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

// FE-203-T2 rides along here for its "기본" clause, and only for that one.
// These four cases render the sheet in its populated default — a full trip
// list, the current trip marked, the place named — which IS the default state
// that clause asks for. The id is on the describe because all four show it;
// AGENTS.md allows one test to prove clauses of two cards, and the aggregator
// reads names, so a comment claiming coverage would not count.
describe('FE-203-T1 FE-203-T2 the picker reports a choice and saves nothing itself', () => {
  it('lists every trip the owner has, not just the first', () => {
    // The defect this component exists to fix: the feed took items[0] and the
    // other trips were unreachable.
    renderPicker();
    expect(screen.getByRole('button', { name: /서울 가을 여행/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /부산 겨울 여행/ })).toBeInTheDocument();
    // This is the FE-203-T2 "기본" clause: the populated default renders as
    // ITSELF, with no other state's copy alongside it. Listing the trips does
    // not say that — a sheet showing two trips AND "No trips yet" satisfies
    // the two assertions above. Measured: widening the empty guard to render
    // with a full list left all ten green before these two lines existed.
    expect(screen.queryByText(LABELS.empty)).toBeNull();
    expect(screen.queryByText(LABELS.loading)).toBeNull();
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

// FE-203-T2 is "기본/loading/empty/error/offline/stale 상태를 각각 렌더한다".
//
// Four of the six are here; 기본 is on the block above. The remaining two,
// offline and stale, CANNOT BE REACHED IN THIS COMPONENT, and that is a fact
// about where the fetch lives rather than a gap in this file.
//
// TripPicker takes `trips`, `loading` and `failed` as props and issues no
// request of its own (see its header: the sheet only CHOOSES, because a picker
// that also fetched or saved would become a second place a candidate gets
// written, which invariant 1 keeps apart). So:
//
//   offline → a thrown fetch has no response to distinguish; the caller's query
//             folds it into `failed`, which is the third case below. A prop to
//             tell the two apart would exist only for a test.
//   stale   → cache revalidation belongs to the caller's query cache. This
//             component holds no cache and cannot observe one going stale.
//
// Writing a case per literal word would mean inventing props the sheet cannot
// be put into, and an assertion about a state nothing produces is the
// "발화할 수 없는 단언" AGENTS.md rule 7② names. import-paste.test.tsx made the
// same call for the same reason and records it the same way.
describe('FE-203-T2 each state renders as itself', () => {
  it('says it is loading rather than showing an empty list', () => {
    // An empty list while loading reads as "you have no trips", which is a
    // different fact.
    renderPicker({ trips: [], loading: true, selectedTripId: null });
    expect(screen.getByRole('status')).toHaveTextContent(LABELS.loading);
    expect(screen.queryByRole('button', { name: /여행/ })).toBeNull();
    // The title's actual claim. Without this the assertions above pass while
    // the sheet renders BOTH the spinner copy and "No trips yet" — "loading"
    // and "you have none" are different facts and the empty guard is the only
    // thing keeping them apart. Measured: dropping `!loading` from that guard
    // left all ten green before this line existed.
    expect(screen.queryByText(LABELS.empty)).toBeNull();
  });

  it('offers trip creation when there are none, instead of describing the problem', async () => {
    const onCreateTrip = vi.fn();
    const user = userEvent.setup();
    renderPicker({ trips: [], selectedTripId: null, onCreateTrip });
    expect(screen.getByText(LABELS.empty)).toBeInTheDocument();
    // "Instead of describing the problem": no trip yet is a fact about the
    // account, so neither the spinner nor the failure copy belongs here.
    //
    // NOT an absence-of-trip-cards assertion, which is the trap this state
    // invites: `trips` is [] here, so no card can appear whatever the render
    // guards say, and the assertion could never fail. These two can — each
    // fires on its own when the matching guard is widened.
    expect(screen.queryByText(LABELS.loading)).toBeNull();
    expect(screen.queryByText(LABELS.error)).toBeNull();
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
    expect(within(row).getByText('10/4 – 10/7')).toBeInTheDocument();
  });
});
