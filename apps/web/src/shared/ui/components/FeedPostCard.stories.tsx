import type { components } from '@nullnull/api-client';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { FeedPostCard } from './FeedPostCard.js';

type FeedCard = components['schemas']['FeedCard'];

const seoulProvenance = {
  attribution:
    '출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)',
  attributionShort: '출처: 서울특별시',
  officialUrl: null,
  licenseUrl: null,
  observedAt: '2026-10-04T05:35:00Z',
} as components['schemas']['DataProvenance'];

const ktoProvenance = {
  attribution: '출처: ⓒ한국관광공사',
  attributionShort: '출처: ⓒ한국관광공사',
  officialUrl: null,
  licenseUrl: null,
  observedAt: null,
} as components['schemas']['DataProvenance'];

const base: FeedCard = {
  post: {
    id: '018f3f8e-0000-7a21-8d31-31d315b93911',
    title: '수문장 교대의식은 10시부터',
    coverUrl: 'https://placehold.co/344x458/e5e7ea/71757b?text=Gyeongbokgung',
    publishedAt: '2026-10-02T00:00:00Z',
  },
  primaryPlace: {
    id: '018f3f8e-9b67-7a21-8d31-31d315b93911',
    name: '경복궁 · 종로구',
  } as FeedCard['primaryPlace'],
  crowd: {
    state: 'LIVE',
    label: '4 · 혼잡',
    ordinalLevel: '4',
    provenance: seoulProvenance,
  } as components['schemas']['CrowdMetric'],
  savedPost: false,
  candidateState: 'NOT_SAVED',
};

const meta = {
  title: 'Card/FeedPost',
  component: FeedPostCard,
  // Cards sit in a 172px column in the real feed.
  decorators: [(Story) => <div style={{ width: 172 }}>{<Story />}</div>],
} satisfies Meta<typeof FeedPostCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Live reading credited to Seoul, not to the tourism agency. */
export const LiveObservation: Story = { args: { card: base } };

/** Forecast reading credited to KTO. The two sources never merge. */
export const Forecast: Story = {
  args: {
    card: {
      ...base,
      post: { ...base.post, title: '도심 속 숲길 산책' },
      crowd: {
        state: 'FORECAST',
        label: '1 · 매우 여유',
        ordinalLevel: '1',
        provenance: ktoProvenance,
      } as components['schemas']['CrowdMetric'],
    },
  },
};

/** No crowd data at all: the card says so rather than hiding the row. */
export const NoCrowdData: Story = {
  args: { card: { ...base, crowd: null } },
};

/** Already a candidate of the selected trip. */
export const AlreadySaved: Story = {
  args: { card: { ...base, candidateState: 'SAVED_TO_SELECTED_TRIP' } },
};

/** No trip picked yet, so the button offers to create one. */
export const NoTripSelected: Story = {
  args: { card: { ...base, candidateState: 'NO_TRIP_SELECTED' } },
};

/** Long Korean title at the narrowest supported width. */
export const LongKoreanTitle: Story = {
  args: {
    card: {
      ...base,
      post: {
        ...base.post,
        title:
          '수문장 교대의식은 오전 10시부터 광화문 앞에서 시작하니까 조금 일찍 가세요',
      },
    },
  },
};

/** English copy, which runs longer for the same meaning. */
export const EnglishTitle: Story = {
  args: {
    card: {
      ...base,
      post: {
        ...base.post,
        title: 'The royal guard changing ceremony starts at 10 in the morning',
      },
      primaryPlace: {
        ...base.primaryPlace,
        name: 'Gyeongbokgung Palace · Jongno-gu',
      },
    },
  },
};
