import type { AttributionSource } from '../../shared/ui/index.js';

// The credits S15 draws for the data the app shows. This screen calls no API,
// so these are the server's source-registry values written out - each one
// checked by data-guide.test.tsx against what the server really sends (the
// approved examples, or the registry migration where no example carries the
// source yet). Never edit a value here without the server's changing first.
//
// One entry per dataset, never per provider: the KTO datasets share the credit
// text and differ in their official page (SOURCE_CATALOG: provenance is drawn
// per record, never merged into one ⓒ한국관광공사 line).
export interface GuideSource extends AttributionSource {
  source: string;
  sourceDisplayName: string;
  attribution: string;
  officialUrl: string;
  licenseUrl: string;
}

export const GUIDE_SOURCES: readonly GuideSource[] = [
  {
    // Place records and their Korean text (KorService2).
    source: 'KTO_KOR_SERVICE_2',
    sourceDisplayName: '한국관광공사 국문 관광정보',
    attribution: '출처: ⓒ한국관광공사',
    officialUrl: 'https://www.data.go.kr/data/15101578/openapi.do',
    licenseUrl: 'https://www.data.go.kr/ugs/selectPortalPolicyView.do',
  },
  {
    // English place text (EngService2, V050).
    source: 'KTO_ENG_SERVICE',
    sourceDisplayName: '한국관광공사 영문 관광정보',
    attribution: '출처: ⓒ한국관광공사',
    officialUrl: 'https://www.data.go.kr/data/15101753/openapi.do',
    licenseUrl: 'https://www.data.go.kr/ugs/selectPortalPolicyView.do',
  },
  {
    // The crowd forecast.
    source: 'KTO_CONCENTRATION_FORECAST',
    sourceDisplayName: '한국관광공사 관광지 집중률 예측',
    attribution: '출처: ⓒ한국관광공사',
    officialUrl: 'https://www.data.go.kr/data/15128555/openapi.do',
    licenseUrl: 'https://www.data.go.kr/ugs/selectPortalPolicyView.do',
  },
  {
    // Seoul live crowding.
    source: 'SEOUL_CITYDATA',
    sourceDisplayName: '서울 실시간 도시데이터',
    attribution:
      '출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)',
    officialUrl: 'https://data.seoul.go.kr/dataList/OA-21285/F/1/datasetView.do',
    licenseUrl: 'https://www.kogl.or.kr/info/licenseType1.do',
  },
];
