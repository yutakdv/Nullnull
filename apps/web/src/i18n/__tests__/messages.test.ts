// Locale parity and placeholder substitution.
//
// Both are things a hand-edited message table loses quietly: a key added to one
// locale renders as `undefined` in the other, and a placeholder whose value is
// never passed ships as literal `{count}` or, worse, "undefined".
import { describe, expect, it } from 'vitest';
import { messages } from '../messages.js';

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
  function placeholders(text: string): string[] {
    return [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1] ?? '').sort();
  }

  it('uses the same placeholder names in both locales', () => {
    for (const key of Object.keys(ko) as (keyof typeof ko)[]) {
      // A message that takes {count} in one language and {n} in the other
      // renders the placeholder literally in whichever one the caller does not
      // match.
      expect(placeholders(ko[key]), `mismatch on ${key}`).toEqual(placeholders(en[key]));
    }
  });
});
