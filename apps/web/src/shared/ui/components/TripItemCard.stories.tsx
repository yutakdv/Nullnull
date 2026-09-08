import type { components } from '@nullnull/api-client';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { TripItemCard } from './TripItemCard.js';

type TripItem = components['schemas']['TripItem'];

const provenance = {
  attribution: '출처: ⓒ한국관광공사',
  attributionShort: '출처: ⓒ한국관광공사',
  officialUrl: null,
  licenseUrl: null,
  observedAt: null,
} as components['schemas']['DataProvenance'];

const crowd = {
  state: 'FORECAST',
  label: '4 · 혼잡',
  ordinalLevel: '4',
  provenance,
} as components['schemas']['CrowdMetric'];

const base = {
  id: '018f3f8e-1111-7a21-8d31-31d315b93911',
  place: {
    id: '018f3f8e-9b67-7a21-8d31-31d315b93911',
    name: '경복궁',
  },
  date: '2026-10-04',
  position: 0,
  startTime: '09:30',
  constraints: [],
} as unknown as TripItem;

const withLocks = (...types: Array<'MUST_VISIT' | 'DATE' | 'TIME' | 'RESERVATION'>) =>
  ({ ...base, constraints: types.map((type) => ({ type })) }) as TripItem;

const meta = {
  title: 'Card/TripItem',
  component: TripItemCard,
} satisfies Meta<typeof TripItemCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const NoLocks: Story = { args: { item: base, crowd } };

/** Each present lock renders on its own; the others stay unlocked. */
export const DateLocked: Story = { args: { item: withLocks('DATE'), crowd } };

export const MustVisitAndTime: Story = {
  args: { item: withLocks('MUST_VISIT', 'TIME'), crowd },
};

/** A reservation lock appears but is not offered as a toggle. */
export const ReservationLocked: Story = {
  args: { item: withLocks('RESERVATION'), crowd },
};

export const AllFourLocks: Story = {
  args: { item: withLocks('MUST_VISIT', 'DATE', 'TIME', 'RESERVATION'), crowd },
};

/** State is stated in words, not only by tint. */
export const Optimized: Story = {
  args: { item: withLocks('TIME'), crowd, state: 'optimized' },
};

export const Conflict: Story = {
  args: { item: withLocks('TIME'), crowd, state: 'conflict' },
};

/** No time set yet. */
export const NoStartTime: Story = {
  args: { item: { ...base, startTime: null } as TripItem, crowd },
};

/** Crowd data missing for this place. */
export const NoCrowdData: Story = { args: { item: base, crowd: null } };

/** Long place name at 360px. */
export const LongPlaceName: Story = {
  args: {
    item: {
      ...base,
      place: { ...base.place, name: '국립중앙박물관 상설전시관 특별전시실' },
    } as TripItem,
    crowd,
  },
};
