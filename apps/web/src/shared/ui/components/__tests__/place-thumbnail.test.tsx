// CMP-ATT-001 for images: no credit, no picture.
//
// The contract states the rule on the field: thumbnailAttribution is the
// "ready-to-render credit for thumbnailUrl, or null when the reviewed licence
// requires none", and "a card that cannot name the image's source must not
// show the image".
//
// This is asserted on the shared component rather than on each screen because
// the component is what makes the rule unrepeatable — before it, four screens
// rendered a place thumbnail and none of them credited it.
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PlaceThumbnail } from '../PlaceThumbnail.js';

const CREDIT = '출처: ⓒ한국관광공사';
const URL = 'https://cdn.example.test/places/01.jpg';

describe('PlaceThumbnail shows an image only when it can name the source', () => {
  it('renders the image and its credit together', () => {
    render(
      <PlaceThumbnail
        place={{ thumbnailUrl: URL, thumbnailAttribution: CREDIT }}
        size={56}
      />,
    );
    expect(screen.getByRole('presentation', { hidden: true })).toHaveAttribute(
      'src',
      URL,
    );
    expect(screen.getByText(CREDIT)).toBeInTheDocument();
  });

  it('shows nothing at all when the credit is missing', () => {
    // The failure this prevents is showing the picture and dropping the line:
    // redistributable is not the same as creditless, so an uncreditable image
    // is not displayed rather than displayed bare.
    const { container } = render(
      <PlaceThumbnail
        place={{ thumbnailUrl: URL, thumbnailAttribution: null }}
        size={56}
      />,
    );
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole('presentation', { hidden: true })).toBeNull();
  });

  it('shows nothing when there is no image', () => {
    const { container } = render(
      <PlaceThumbnail
        place={{ thumbnailUrl: null, thumbnailAttribution: CREDIT }}
        size={56}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the server credit verbatim, never a composed one', () => {
    // CMP-ATT-003: the client does not build a credit or name a provider of
    // its own. Whatever the server approved is what appears.
    const odd = '출처: ⓒ한국관광공사 · 서울 열린데이터광장 (2026 스냅숏)';
    render(
      <PlaceThumbnail
        place={{ thumbnailUrl: URL, thumbnailAttribution: odd }}
        size={44}
      />,
    );
    expect(screen.getByText(odd)).toBeInTheDocument();
  });
});
