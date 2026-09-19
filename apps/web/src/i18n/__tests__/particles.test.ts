// #279 하6: FE templates fixed the particle, so half of every sentence was
// wrong Korean.
//
// The interesting cases are not "does 경복궁 take 을" — they are the ones a
// hard-coded spelling gets wrong, so each block below pairs a consonant-final
// word with a vowel-final one and asserts they differ. A test that only checked
// one side would pass for the fixed spelling this replaces.
import { describe, expect, it } from 'vitest';
import { euro, eulReul, iGa, waGwa } from '../particles.js';

describe('#279 하6 a particle follows the sound of the word before it', () => {
  describe('을/를', () => {
    it('takes 을 after a final consonant', () => {
      expect(eulReul('경복궁')).toBe('을');
      expect(eulReul('서울숲')).toBe('을');
    });

    it('takes 를 after a vowel', () => {
      // The case the old '{name}을' got wrong.
      expect(eulReul('제주')).toBe('를');
      expect(eulReul('명동거리')).toBe('를');
    });
  });

  describe('와/과', () => {
    it('takes 와 after a vowel and 과 after a consonant', () => {
      expect(waGwa('제주')).toBe('와');
      expect(waGwa('경복궁')).toBe('과');
    });

    it('reads a version number by its Korean sound, not its digit', () => {
      // `v{restored}와` was the reported case. "v1" is said 브이일 and ends in
      // ㄹ, so it takes 과 — the fixed 와 was wrong for it.
      expect(waGwa('1')).toBe('과');
      expect(waGwa('2')).toBe('와');
      expect(waGwa('3')).toBe('과');
    });
  });

  describe('(으)로', () => {
    it('takes 로 after a vowel', () => {
      expect(euro('제주')).toBe('로');
    });

    it('takes 로 after ㄹ, which is the exception 을/를 does not have', () => {
      // 서울 ends in a consonant, so it takes 을 — but 로, not 으로. This is
      // the pair that shows 로 is not just "the vowel one".
      expect(eulReul('서울')).toBe('을');
      expect(euro('서울')).toBe('로');
    });

    it('takes 으로 after any other consonant', () => {
      expect(euro('경복궁')).toBe('으로');
    });
  });

  describe('이/가', () => {
    it('follows the same split', () => {
      expect(iGa('경복궁')).toBe('이');
      expect(iGa('제주')).toBe('가');
    });
  });

  describe('a sound it cannot read', () => {
    // apps/ai's rule, kept deliberately: a guess here produces confident wrong
    // Korean, while the written-out pair is merely formal. The alternative —
    // defaulting to one spelling — is what this whole file exists to undo.
    it('writes out the pair for a Latin ending', () => {
      expect(eulReul('Seoul Forest')).toBe('을(를)');
      expect(iGa('KTO')).toBe('이(가)');
      expect(euro('Gangnam')).toBe('(으)로');
      expect(waGwa('v')).toBe('와(과)');
    });

    it('writes out the pair for punctuation', () => {
      expect(eulReul('(주)한국관광공사)')).toBe('을(를)');
    });
  });

  describe('numbers are read aloud, not looked at', () => {
    it('gives each digit the ending of its Korean word', () => {
      // 일 육 칠 팔 end in a consonant; 이 사 오 구 do not.
      expect(eulReul('1')).toBe('을');
      expect(eulReul('2')).toBe('를');
      expect(eulReul('6')).toBe('을');
      expect(eulReul('9')).toBe('를');
    });

    it('reads a trailing zero as its place unit', () => {
      // 10 is 십, which ends in ㅂ — not 영. A digit table that stopped at the
      // last character would call this a vowel ending.
      expect(eulReul('10')).toBe('을');
      expect(eulReul('100')).toBe('을');
    });

    it('declines to guess past 조, where the unit stops being predictable', () => {
      // Same cutoff as apps/ai: twelve zeros reads 조, which ends in a vowel,
      // and beyond that the spoken form is not claimed.
      expect(eulReul(`1${'0'.repeat(12)}`)).toBe('을(를)');
    });
  });
});
