// Locale parity and placeholder substitution.
//
// Both are things a hand-edited message table loses quietly: a key added to one
// locale renders as `undefined` in the other, and a placeholder whose value is
// never passed ships as literal `{count}` or, worse, "undefined".
import { describe, expect, it } from 'vitest';
import { messages } from '../messages.js';
import { PARTICLES } from '../particles.js';

const ko = messages['ko-KR'];
const en = messages['en-US'];

describe('every locale carries the same keys', () => {
  it('has no key that exists in only one locale', () => {
    const koKeys = Object.keys(ko).sort();
    const enKeys = Object.keys(en).sort();
    expect(koKeys).toEqual(enKeys);
  });

  it('has no empty string standing in for a missing translation', () => {
    for (const [key, value] of Object.entries(ko)) {
      expect(value, `ko-KR ${key} is empty`).not.toBe('');
    }
    for (const [key, value] of Object.entries(en)) {
      expect(value, `en-US ${key} is empty`).not.toBe('');
    }
  });
});

describe('placeholders match across locales', () => {
  /**
   * The NAMES a template interpolates, ignoring any particle marker.
   *
   * `{name:을}` and `{name}` are the same slot filled by the same caller value
   * — the marker only decides what Korean particle follows it (#279 하6), and
   * en never carries one. Matching on `\{(\w+)\}` alone read the ko side as
   * having no placeholder at all, so this test failed the moment the markers
   * landed. That failure was correct as a signal and wrong as a verdict: the
   * regex, not the message, was out of date.
   */
  function placeholders(text: string): string[] {
    // `[^}]` and not `\w` for the marker: JavaScript's `\w` is ASCII-only, so
    // it does not match 을. Measured — the first spelling of this fix silently
    // matched nothing and read the ko side as having no placeholders, which is
    // the same false reading it was meant to repair.
    return [...text.matchAll(/\{(\w+)(?::[^}]+)?\}/g)].map((m) => m[1] ?? '').sort();
  }

  it('uses the same placeholder names in both locales', () => {
    for (const key of Object.keys(ko) as (keyof typeof ko)[]) {
      // A message that takes {count} in one language and {n} in the other
      // renders the placeholder literally in whichever one the caller does not
      // match.
      expect(placeholders(ko[key]), `mismatch on ${key}`).toEqual(placeholders(en[key]));
    }
  });

  it('names a particle this build knows how to choose', () => {
    // A marker is only a marker if `interpolate` can resolve it; an unknown one
    // ships as a literal `{name:음}`. This is the cheap half of that guard —
    // the spelling — and the sound itself is particles.test.ts's.
    for (const [key, text] of Object.entries(ko)) {
      for (const match of text.matchAll(/\{\w+:([^}]+)\}/g)) {
        expect(Object.keys(PARTICLES), `unknown particle in ${key}`).toContain(match[1]);
      }
    }
  });

  it('puts no particle marker in en, which has no particles', () => {
    for (const [key, text] of Object.entries(en)) {
      expect(text, `en-US ${key} carries a particle marker`).not.toMatch(/\{\w+:[^}]+\}/);
    }
  });
});
