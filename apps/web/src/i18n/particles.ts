// Korean particles that follow the sound of the word before them (#279 하6).
//
// A Korean particle is chosen by whether the word before it ends in a
// consonant. 경복궁 and 명동 end in ㅇ and take 을; 제주 and 서울숲... 서울숲
// ends in ㅍ and takes 을, while 제주 ends in a vowel and takes 를. One spelling
// cannot serve both, so every template that hard-coded one was wrong for half
// the place names it renders.
//
// FE had them hard-coded: `{name}을 {day}로 옮겼어요` reads correctly for
// 경복궁 and incorrectly for 제주, and `v{restored}와` is right for v3 and wrong
// for v1 (said "브이일", ending in ㄹ).
//
// THE APPROACH IS BACKEND'S, not a second invention. apps/ai's
// explain/templates.py solved this first (#260) and this mirrors its rules so
// the same name is not declined two ways in one screen:
//
//   - the final consonant (jongseong) is read from the Hangul syllable block
//   - a digit is read as the Korean word for it, because "v1" is said "브이일"
//     and ends in ㄹ
//   - when the ending cannot be told — a Latin letter, a bracket — the
//     written-out pair ("을(를)") is used rather than a guess
//
// It adds 을/를, which BE has no need for: its one sentence uses 이/가 and
// (으)로 only.

/** Hangul syllables have 28 possible finals; 0 is "no final consonant". */
const NO_FINAL = 0;
/** ㄹ behaves like no-final for 로, which is why it is named separately. */
const RIEUL = 8;

/**
 * How each digit ends when READ ALOUD in Korean: 영 일 이 삼 사 오 육 칠 팔 구.
 *
 * Copied from apps/ai's `_DIGIT_FINAL` so "v1" declines the same way in a FE
 * toast and in a server sentence.
 */
const DIGIT_FINAL: Record<string, number> = {
  '0': 21,
  '1': 8,
  '2': 0,
  '3': 16,
  '4': 0,
  '5': 0,
  '6': 1,
  '7': 8,
  '8': 8,
  '9': 0,
};

/**
 * The final consonant of the spoken word (0 for none), or null when unknown.
 *
 * Null is not a failure to handle — it is the honest answer for "Seoul Forest"
 * or "(주)한국", and the caller renders the written-out pair for it.
 */
function finalConsonant(word: string): number | null {
  const last = word.slice(-1);
  if (last >= '가' && last <= '힣') {
    return (last.charCodeAt(0) - '가'.charCodeAt(0)) % 28;
  }
  if (!(last in DIGIT_FINAL)) return null;
  if (last !== '0') return DIGIT_FINAL[last] ?? null;
  // A trailing zero is read as a place unit — 십 백 천 만 억 — and each of those
  // ends in a consonant that is not ㄹ, so every one takes the same particle as
  // 영 does. 조 (twelve zeros) ends in none, so past that the sound is not
  // claimed. Same cutoff as apps/ai.
  const zeros = word.length - word.replace(/0+$/, '').length;
  return zeros < 12 ? (DIGIT_FINAL['0'] ?? null) : null;
}

/** 을 after a consonant, 를 after a vowel, 을(를) when the sound is unknown. */
export function eulReul(word: string): string {
  const final = finalConsonant(word);
  if (final === null) return '을(를)';
  return final === NO_FINAL ? '를' : '을';
}

/** 이 after a consonant, 가 after a vowel, 이(가) when unknown. */
export function iGa(word: string): string {
  const final = finalConsonant(word);
  if (final === null) return '이(가)';
  return final === NO_FINAL ? '가' : '이';
}

/** 과 after a consonant, 와 after a vowel, 와(과) when unknown. */
export function waGwa(word: string): string {
  const final = finalConsonant(word);
  if (final === null) return '와(과)';
  return final === NO_FINAL ? '와' : '과';
}

/** 로 after a vowel or ㄹ, 으로 otherwise, (으)로 when unknown. */
export function euro(word: string): string {
  const final = finalConsonant(word);
  if (final === null) return '(으)로';
  return final === NO_FINAL || final === RIEUL ? '로' : '으로';
}

/**
 * The particle markers a template may carry, and how each is resolved.
 *
 * Written as `{name:을}` in a message, so the template still READS as the
 * sentence it produces — a translator sees 을 and knows which particle this is,
 * rather than a code they have to look up. The marker is the common spelling;
 * the function decides the actual one from the value that lands there.
 */
export const PARTICLES: Record<string, (word: string) => string> = {
  을: eulReul,
  를: eulReul,
  이: iGa,
  가: iGa,
  와: waGwa,
  과: waGwa,
  로: euro,
  으로: euro,
};
