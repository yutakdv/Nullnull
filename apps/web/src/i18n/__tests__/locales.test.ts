import { describe, expect, it } from 'vitest';
import {
  DEFAULT_LOCALE,
  SUPPORTED_LOCALES,
  isSupportedLocale,
  matchSupportedLocale,
} from '../locales.js';

describe('P0 locale policy', () => {
  it('supports exactly Korean and English', () => {
    expect([...SUPPORTED_LOCALES]).toEqual(['ko-KR', 'en-US']);
  });

  it('never treats Japanese or Chinese as selectable', () => {
    expect(isSupportedLocale('ja-JP')).toBe(false);
    expect(isSupportedLocale('zh-CN')).toBe(false);
  });

  it('matches browser languages onto supported locales only', () => {
    expect(matchSupportedLocale(['en-GB', 'en'])).toBe('en-US');
    expect(matchSupportedLocale(['ko'])).toBe('ko-KR');
    expect(matchSupportedLocale(['ja-JP'])).toBeNull();
  });

  it('falls back to Korean', () => {
    expect(DEFAULT_LOCALE).toBe('ko-KR');
  });
});
