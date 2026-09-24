import { render, screen, within } from '@testing-library/react';
import type { components } from '@nullnull/api-client';
import { placeFixtures } from '@nullnull/contracts';
import { describe, expect, it } from 'vitest';
import { PlaceAttribution } from '../index.js';

// CMP-ATT-001 for one place: its record credit AND the credit of the text it
// shows (`textProvenance`), each drawn as the server wrote it, and never merged
// into one `ⓒ한국관광공사` line (SOURCE_CATALOG, provenance primitives).
//
// The place is the contract fixture. The English credit below is the one
// override, and its values are the reviewed registry row for KTO_ENG_SERVICE
// (V050), not invented: the Korean and English datasets carry the SAME
// attribution text and differ only in their dataset page. That sameness is
// what T7 is about.

type SourceAttribution = components['schemas']['SourceAttribution'];

const KOR_URL = 'https://www.data.go.kr/data/15101578/openapi.do';
const ENG_URL = 'https://www.data.go.kr/data/15101753/openapi.do';
const CREDIT = '출처: ⓒ한국관광공사';

const ENG: SourceAttribution = {
  source: 'KTO_ENG_SERVICE',
  sourceDisplayName: '한국관광공사 영문 관광정보',
  sourceRegistryVersion: 1,
  attribution: CREDIT,
  officialUrl: ENG_URL,
  licenseUrl: 'https://www.data.go.kr/ugs/selectPortalPolicyView.do',
  license: '이용허락범위 제한 없음 (관광정보 텍스트; 이미지 별도 심사)',
};

/** The fixture place with its name and address served from the English dataset. */
function withEnglishText() {
  const place = placeFixtures.detail;
  return {
    ...place,
    textProvenance: {
      name: { locale: 'en', sourceAttribution: ENG },
      address: { locale: 'en', sourceAttribution: ENG },
      description: null,
    },
  };
}

function creditLinks(container: HTMLElement): HTMLAnchorElement[] {
  return within(container).queryAllByRole('link', { name: CREDIT });
}

describe('FE-603-T8 one credit is drawn once per unit', () => {
  it('draws the place credit once when its text comes from the same dataset', () => {
    // The fixture's name and address both come from KorService2, the dataset
    // the place record credits. Three identical lines under one place name
    // would be three links to the same page.
    expect(placeFixtures.detail.textProvenance?.name?.sourceAttribution?.source).toBe(
      'KTO_KOR_SERVICE_2',
    );
    const { container } = render(<PlaceAttribution place={placeFixtures.detail} />);

    expect(creditLinks(container).map((link) => link.getAttribute('href'))).toEqual([
      KOR_URL,
    ]);
  });
});

describe('FE-603-T6 a text credit from another dataset is drawn on its own', () => {
  it('credits the English dataset beside the place record', () => {
    const { container } = render(<PlaceAttribution place={withEnglishText()} />);

    // Order is the record first, then the text: the record is what the unit
    // is about, and the text credit accompanies it (contract, PlaceSummary).
    expect(creditLinks(container).map((link) => link.getAttribute('href'))).toEqual([
      KOR_URL,
      ENG_URL,
    ]);
  });
});

describe('FE-603-T7 two credits with the same words name their sources', () => {
  it('adds the server display name to the later credit', () => {
    const { container } = render(<PlaceAttribution place={withEnglishText()} />);
    const [record, text] = creditLinks(container);

    // The link text stays the server's words exactly (CMP-ATT-003); the
    // display name is the context beside it, visible and announced.
    expect(text).toHaveAccessibleDescription('한국관광공사 영문 관광정보');
    expect(screen.getByText('한국관광공사 영문 관광정보')).toBeVisible();
    // The first of the pair keeps the plain credit every other screen draws.
    expect(record).not.toHaveAccessibleDescription();
  });

  it('adds nothing when the words already differ', () => {
    const seoul: SourceAttribution = {
      ...ENG,
      source: 'SEOUL_CITYDATA',
      sourceDisplayName: '서울시 실시간 도시데이터',
      attribution: '출처: 서울특별시',
    };
    const { container } = render(
      <PlaceAttribution place={placeFixtures.detail} also={[seoul]} />,
    );

    expect(
      within(container).getByRole('link', { name: '출처: 서울특별시' }),
    ).not.toHaveAccessibleDescription();
    expect(screen.queryByText('서울시 실시간 도시데이터')).toBeNull();
  });
});
