import type { Meta, StoryObj } from '@storybook/react-vite';
import { PlaceThumbnail } from './PlaceThumbnail.js';

// A place's thumbnail and the credit that licenses it (CMP-ATT-001).
//
// The two stories below are the whole contract: an image the server credited,
// and an image it did not. The second renders nothing on purpose — "a card
// that cannot name the image's source must not show the image" — so the empty
// frame is the behaviour under review, not a broken story.
const meta = {
  title: 'Data/PlaceThumbnail',
  component: PlaceThumbnail,
} satisfies Meta<typeof PlaceThumbnail>;

export default meta;
type Story = StoryObj<typeof meta>;

const IMAGE = 'https://cdn.example.test/places/gyeongbokgung.jpg';

/** Credited by the server, so it is shown with that credit beneath it. */
export const Credited: Story = {
  args: {
    place: {
      thumbnailUrl: IMAGE,
      thumbnailAttribution: '출처: ⓒ한국관광공사',
    },
    size: 66,
  },
};

/**
 * No credit, so no image.
 *
 * Renders empty. Showing the picture without the line is the compliance
 * failure this component exists to make impossible.
 */
export const Uncreditable: Story = {
  args: {
    place: { thumbnailUrl: IMAGE, thumbnailAttribution: null },
    size: 66,
  },
};

/** The sizes the frames use: add-place 44, candidates 56, must-visit 66. */
export const EverySize: Story = {
  // args satisfies the typed story shape; the render below ignores them and
  // shows all three sizes side by side.
  args: {
    place: { thumbnailUrl: IMAGE, thumbnailAttribution: '출처: ⓒ한국관광공사' },
    size: 66,
  },
  render: () => (
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
      {[44, 56, 66].map((size) => (
        <PlaceThumbnail
          key={size}
          place={{ thumbnailUrl: IMAGE, thumbnailAttribution: '출처: ⓒ한국관광공사' }}
          size={size}
        />
      ))}
    </div>
  ),
};
