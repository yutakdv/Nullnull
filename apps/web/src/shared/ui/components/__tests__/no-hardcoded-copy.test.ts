// Shared components must not carry user-visible copy in one language.
//
// The gap this closes: every component in this folder rendered Korean
// regardless of the selected locale, and nothing caught it — no lint rule, no
// test, no CI check. An English user saw "내 여행에 담기" as the accessible name
// of the feed's add button.
//
// The rule is not "no Korean in this folder". Comments explain Figma wording
// and quote the catalogue, and each component keeps a Korean DEFAULT so the
// Storybook stories can mount it without an I18nProvider. What is forbidden is
// a Korean string that reaches the user with NO way for a caller to replace
// it — the app passes the selected locale's words through props.
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// vitest runs with apps/web as the root, the same base the fixture test uses.
const COMPONENTS = resolve(process.cwd(), 'src/shared/ui/components');
const HANGUL = /[가-힣]/;

/** Strips comments and import lines so only real code is scanned. */
function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter((line) => !line.trimStart().startsWith('//'))
    .filter((line) => !line.trimStart().startsWith('import '))
    .join('\n');
}

/**
 * Components allowed to hold Korean defaults, and the prop that overrides each.
 *
 * Being on this list is a promise: the component takes a prop that replaces
 * every user-visible string it owns. The test below checks that promise rather
 * than trusting it, so adding a name here without the prop still fails.
 */
const DEFAULTS_WITH_OVERRIDE: Record<string, string> = {
  'StateLabel.tsx': 'labels',
  'TripAddButton.tsx': 'labels',
  'DataAttribution.tsx': 'termsLabel',
  'CrowdLevel.tsx': 'levelLabel',
  'NavBar.tsx': 'backLabel',
  'MustVisitBadge.tsx': 'label',
};

/**
 * Components that still hardcode copy with no override.
 *
 * Every entry is a known defect, not an exemption: these render Korean for an
 * English user. They are listed so the guard can protect the components that
 * are fixed while the rest are worked through, and the list may only shrink —
 * a component removed from here can never come back.
 */
const KNOWN_UNFIXED = new Set([
  'CandidateCard.tsx',
  'DecisionBar.tsx',
  'MetricDelta.tsx',
  'TripItemCard.tsx',
]);

function componentFiles(): string[] {
  return readdirSync(COMPONENTS).filter(
    (name) =>
      name.endsWith('.tsx') && !name.endsWith('.stories.tsx') && !name.includes('.test.'),
  );
}

describe('shared components do not lock the user into one language', () => {
  it.each(componentFiles().filter((name) => !KNOWN_UNFIXED.has(name)))(
    '%s has no unreachable Korean string',
    (name) => {
      const body = code(readFileSync(join(COMPONENTS, name), 'utf8'));
      if (!HANGUL.test(body)) return;
      // It has Korean, so it must be a default with a documented override.
      const prop = DEFAULTS_WITH_OVERRIDE[name];
      expect(
        prop,
        `${name} contains Korean but is not listed in DEFAULTS_WITH_OVERRIDE. ` +
          'Either take the copy as a prop, or add it to KNOWN_UNFIXED with a reason.',
      ).toBeDefined();
      expect(
        body,
        `${name} keeps Korean defaults but never reads its "${prop}" prop.`,
      ).toContain(prop as string);
    },
  );

  it('every component promising an override actually declares the prop', () => {
    for (const [name, prop] of Object.entries(DEFAULTS_WITH_OVERRIDE)) {
      const source = readFileSync(join(COMPONENTS, name), 'utf8');
      expect(source, `${name} must declare the ${prop} prop`).toMatch(
        new RegExp(`${prop}\\??:`),
      );
    }
  });

  it('the unfixed list only shrinks', () => {
    // A guard whose exemption list can grow protects nothing. This pins the
    // count: fixing a component means deleting its entry and lowering this
    // number, and adding a new offender fails here.
    expect(KNOWN_UNFIXED.size).toBeLessThanOrEqual(4);
    for (const name of KNOWN_UNFIXED) {
      const body = code(readFileSync(join(COMPONENTS, name), 'utf8'));
      expect(
        HANGUL.test(body),
        `${name} no longer has hardcoded Korean — remove it from KNOWN_UNFIXED.`,
      ).toBe(true);
    }
  });
});
