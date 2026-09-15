import type { Meta, StoryObj } from '@storybook/react-vite';
import { TripPicker } from './TripPicker.js';

// C02 `Sheet / TripPicker`. The catalog names its states as
// `loading`, `empty`, `saving`, `saved`, `duplicate`, `error`, `trips` —
// the first two, the last two and `trips` belong to the picker; `saving`,
// `saved` and `duplicate` are outcomes of the caller's mutation and are shown
// on the button that opened it (TripAddButton), not inside the sheet.
const meta = {
  title: 'Sheet/TripPicker',
  component: TripPicker,
} satisfies Meta<typeof TripPicker>;

export default meta;
type Story = StoryObj<typeof meta>;

const LABELS = {
  title: '어느 여행에 담을까요?',
  cancel: '취소',
  loading: '여행을 불러오는 중이에요',
  empty: '아직 여행이 없어요',
  createTrip: '여행 만들기',
  error: '여행 목록을 불러오지 못했어요',
  retry: '다시 시도',
};

const TRIPS = [
  {
    id: '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01',
    title: '서울 가을 여행',
    startDate: '2026-10-04',
    endDate: '2026-10-07',
    timezone: 'Asia/Seoul',
    status: 'SCHEDULED',
    version: 3,
    candidateCount: 5,
  },
  {
    id: '018f4a10-2c31-7d42-9a55-6b1f0c3e8a02',
    title: '부산 겨울 여행',
    startDate: '2026-12-20',
    endDate: '2026-12-23',
    timezone: 'Asia/Seoul',
    status: 'DRAFT',
    version: 1,
    candidateCount: 0,
  },
] as never;

/** The state the sheet exists for: several trips, one of them current. */
export const Trips: Story = {
  args: {
    open: true,
    placeName: '경복궁',
    trips: TRIPS,
    selectedTripId: '018f4a10-2c31-7d42-9a55-6b1f0c3e8a01',
    labels: LABELS,
    onPick: () => undefined,
    onCancel: () => undefined,
  },
};

/** Loading says so rather than showing an empty list, which reads as "no trips". */
export const Loading: Story = {
  args: { ...Trips.args, trips: [], selectedTripId: null, loading: true },
};

/**
 * No trip yet.
 *
 * Not an error — it is where every new owner starts — so the sheet offers the
 * one action that changes it instead of describing the problem.
 */
export const Empty: Story = {
  args: {
    ...Trips.args,
    trips: [],
    selectedTripId: null,
    onCreateTrip: () => undefined,
  },
};

/** The list could not be read. Distinct from empty: retrying is worth offering. */
export const Failed: Story = {
  args: {
    ...Trips.args,
    trips: [],
    selectedTripId: null,
    failed: true,
    onRetry: () => undefined,
  },
};
