// FE-603-T12 at the places a response URL reaches an attribute.
//
// safe-url.test.ts measures the guard itself. This file measures that the
// guard is WIRED: until it was, `isSafeUrl` was imported by its own test and
// nothing else, so every rejection that file proves never reached a page. A
// guard that exists and a guard that runs are different claims.
//
// The shared-component sites are here. The fifth, the post cover, is a screen
// and needs the router and the mock server, so it lives in post.test.tsx.
//
// Each site is measured in both directions, because each direction alone is
// satisfied by a useless wiring: "the javascript: URL is not rendered" is true
// of a component that renders no link at all, and "the https URL is rendered"
// is true of one that checks nothing.
import type { components } from '@nullnull/api-client';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DataAttribution, FeedPostCard, PlaceThumbnail } from '../../ui/index.js';

type FeedCard = components['schemas']['FeedCard'];

/** The shape a browser runs from an `<a href>` on click. */
const SCRIPT = 'javascript:alert(1)';
const SAFE = 'https://cdn.example.test/a.jpg';
const OFFICIAL = 'https://api.example.test/official';
const LICENSE = 'https://api.example.test/licence';
const CREDIT = '출처: ⓒ한국관광공사';

describe('FE-603-T12 DataAttribution links only to an https page', () => {
  const credit = { attribution: CREDIT, officialUrl: OFFICIAL, licenseUrl: LICENSE };

  it('links the credit to the official page the server named', () => {
    render(<DataAttribution provenance={credit} />);
    expect(screen.getByRole('link', { name: CREDIT })).toHaveAttribute('href', OFFICIAL);
  });

  it('shows the credit as plain words when its page is a script', () => {
    // The credit itself is a licence obligation (CMP-ATT-001), so a bad link
    // drops the link and keeps the words - the same thing a null officialUrl
    // draws.
    render(<DataAttribution provenance={{ ...credit, officialUrl: SCRIPT }} />);
    expect(screen.getByText(CREDIT)).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('links the licence terms the server named', () => {
    render(<DataAttribution provenance={credit} showLicense termsLabel="TERMS" />);
    expect(screen.getByRole('link', { name: 'TERMS' })).toHaveAttribute('href', LICENSE);
  });

  it('drops the licence link when its page is a script', () => {
    render(
      <DataAttribution
        provenance={{ ...credit, licenseUrl: SCRIPT }}
        showLicense
        termsLabel="TERMS"
      />,
    );
    expect(screen.queryByRole('link', { name: 'TERMS' })).toBeNull();
    expect(screen.queryByText('TERMS')).toBeNull();
    // Only the licence link goes: the credit keeps its own, safe, link.
    expect(screen.getByRole('link', { name: CREDIT })).toHaveAttribute('href', OFFICIAL);
  });
});

describe('FE-603-T12 PlaceThumbnail draws only an https image', () => {
  it('draws the image from the URL the server named', () => {
    render(
      <PlaceThumbnail
        place={{ thumbnailUrl: SAFE, thumbnailAttribution: CREDIT }}
        size={56}
      />,
    );
    expect(screen.getByRole('presentation', { hidden: true })).toHaveAttribute(
      'src',
      SAFE,
    );
  });

  it('draws nothing - image or credit - when the URL is a script', () => {
    // The credit belongs to the picture; with no picture it would credit
    // nothing, which is why the whole component goes, as it does for a missing
    // URL.
    const { container } = render(
      <PlaceThumbnail
        place={{ thumbnailUrl: SCRIPT, thumbnailAttribution: CREDIT }}
        size={56}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});

describe('FE-603-T12 FeedPostCard draws only an https cover', () => {
  const card = {
    post: {
      id: '018f3f8e-0000-7a21-8d31-31d315b93911',
      title: '수문장 교대의식은 10시부터',
      coverUrl: SAFE,
      publishedAt: '2026-10-02T00:00:00Z',
    },
    primaryPlace: { id: '018f3f8e-9b67-7a21-8d31-31d315b93911', name: '경복궁' },
    crowd: null,
    savedPost: false,
    candidateState: 'NOT_SAVED',
  } as unknown as FeedCard;

  /** By tag: the crowd bar is role="img" too, and is not the cover. */
  function coverImages(): NodeListOf<HTMLImageElement> {
    return screen.getByRole('article').querySelectorAll('img');
  }

  it('draws the cover from the URL the server named', () => {
    render(<FeedPostCard card={card} />);
    expect(coverImages()).toHaveLength(1);
    expect(coverImages()[0]).toHaveAttribute('src', SAFE);
  });

  it('draws no cover when the URL is a script, and still opens the post', () => {
    render(<FeedPostCard card={{ ...card, post: { ...card.post, coverUrl: SCRIPT } }} />);
    expect(coverImages()).toHaveLength(0);
    // The cover is also the control that opens the post; losing the picture
    // must not lose the way in. This only finds the button in the DOM: jsdom
    // has no layout, so whether it is still visible and 44px tall is measured
    // in a browser by FE-603-T13 (e2e/feed-refused-cover.spec.ts).
    expect(
      within(screen.getByRole('article')).getByRole('button', { name: card.post.title }),
    ).toBeInTheDocument();
  });
});
