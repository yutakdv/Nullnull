import type { Meta, StoryObj } from '@storybook/react-vite';
import { crowdFixtures, placeFixtures } from '@nullnull/contracts';
import { PlaceAttribution } from './PlaceAttribution.js';

// A place's credits (CMP-ATT-001), from the contract fixtures only.
//
// The case the component exists for — a place whose name comes from a second
// dataset (KTO_ENG_SERVICE) — has no fixture yet, so it is not drawn here; the
// unit test builds that one credit from the V050 registry row, which a story
// may not do (TEST_STRATEGY.md:117).
const meta = {
  title: 'Data/PlaceAttribution',
  component: PlaceAttribution,
} satisfies Meta<typeof PlaceAttribution>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The record, the name and the address all credit KorService2, so one credit
 * is drawn, not three.
 */
export const OneDataset: Story = {
  args: { place: placeFixtures.detail, compact: true },
};

/**
 * With the concentration forecast in the same unit. Both read the same words
 * and link different datasets, so the forecast's names its source beside it.
 */
export const WithForecast: Story = {
  args: {
    place: placeFixtures.detail,
    also: crowdFixtures.seriesForecast.points
      .slice(0, 1)
      .map((point) => point.provenance),
    compact: true,
  },
};
