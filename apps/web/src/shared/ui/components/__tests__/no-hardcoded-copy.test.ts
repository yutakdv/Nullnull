// Shared components must not carry user-visible copy in one language.
//
// The gap this closes: every component in this folder rendered Korean
// regardless of the selected locale, and nothing caught it — no lint rule, no
// test, no CI check. An English user saw "내 여행에 담기" as the accessible name
// of the feed's add button.
//
// The rule is not "no Korean in this folder". Comments explain Figma wording
// and quote the catalogue, and each component keeps a Korean DEFAULT for a
// render with no I18nProvider at all (a bare unit test; the Storybook stories
// run inside one, .storybook/preview.tsx). What is forbidden is
// a Korean string that reaches the user inside the app: a component with
// defaults takes a caller's words through a prop, and with none it reads the
// selected locale (useOptionalI18n) before it ever reaches its defaults.
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

// vitest runs with apps/web as the root, the same base the fixture test uses.
const COMPONENTS = resolve(process.cwd(), 'src/shared/ui/components');
const APP = resolve(process.cwd(), 'src/app');
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
 * every user-visible string it owns, and reads the locale when that prop is
 * not given. The test below checks both rather than trusting them, so adding a
 * name here without the prop or the locale fallback still fails.
 */
const DEFAULTS_WITH_OVERRIDE: Record<string, string> = {
  'StateLabel.tsx': 'labels',
  'TripAddButton.tsx': 'labels',
  'DataAttribution.tsx': 'termsLabel',
  'CrowdLevel.tsx': 'levelLabel',
  'NavBar.tsx': 'backLabel',
  'MustVisitBadge.tsx': 'label',
  'DecisionBar.tsx': 'labels',
  'CandidateCard.tsx': 'labels',
  'TripItemCard.tsx': 'labels',
};

// There was a KNOWN_UNFIXED list here - components that hardcoded Korean with
// no override, allowed to shrink but never grow. Its last three left it
// together (#310 FE follow-up 6): CandidateCard and TripItemCard now take
// labels and read the locale, and MetricDelta's only Korean was a fallback
// reason that is now required by type instead. With no list, every component
// in this folder is held to the rule below; bringing the list back is a
// visible edit, not a quiet one.

function componentFiles(): string[] {
  return readdirSync(COMPONENTS).filter(
    (name) =>
      name.endsWith('.tsx') && !name.endsWith('.stories.tsx') && !name.includes('.test.'),
  );
}

/**
 * Every screen under src/app, walked recursively.
 *
 * The guard above reads ONE flat directory, so 23 shared components were
 * checked while 28 screens were not — and that is how the trip wizard shipped
 * `['일','월','화','수','목','금','토']` as its calendar headers, giving an
 * English reader a Korean calendar. Screens are where user-visible copy
 * actually lives, so scanning components alone checks the smaller half.
 *
 * Screens take no label props, so there is no override list here: a screen
 * reads the active locale through useI18n. Any Korean literal in one is a
 * string an English reader would be shown.
 */
function screenFiles(): { name: string; path: string }[] {
  const found: { name: string; path: string }[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== '__tests__') walk(full);
      } else if (
        entry.name.endsWith('.tsx') &&
        !entry.name.endsWith('.stories.tsx') &&
        !entry.name.includes('.test.')
      ) {
        found.push({ name: relative(APP, full), path: full });
      }
    }
  };
  walk(APP);
  return found;
}

describe('shared components do not lock the user into one language', () => {
  it.each(componentFiles())('%s has no unreachable Korean string', (name) => {
    const body = code(readFileSync(join(COMPONENTS, name), 'utf8'));
    if (!HANGUL.test(body)) return;
    // It has Korean, so it must be a default with a documented override.
    const prop = DEFAULTS_WITH_OVERRIDE[name];
    expect(
      prop,
      `${name} contains Korean but is not listed in DEFAULTS_WITH_OVERRIDE. ` +
        'Take the copy as a prop and fall back to the locale (useOptionalI18n).',
    ).toBeDefined();
    expect(
      body,
      `${name} keeps Korean defaults but never reads its "${prop}" prop.`,
    ).toContain(prop as string);
  });

  it('every component promising an override declares the prop and reads the locale', () => {
    // The prop alone was the old promise, and it left the default one caller's
    // omission away from an English screen: every screen had to remember every
    // key. The locale fallback is what takes the default out of the app's reach
    // (FE-001-T4, measured by render in locale-fallback.test.tsx); this pins
    // that each listed component has it, so a new one cannot join the list
    // with the prop alone.
    for (const [name, prop] of Object.entries(DEFAULTS_WITH_OVERRIDE)) {
      const source = readFileSync(join(COMPONENTS, name), 'utf8');
      expect(source, `${name} must declare the ${prop} prop`).toMatch(
        new RegExp(`${prop}\\??:`),
      );
      expect(
        code(source),
        `${name} keeps Korean defaults but never reads the locale (useOptionalI18n)`,
      ).toMatch(/\buseOptionalI18n\(\)/);
    }
  });
});

describe('screens do not lock the user into one language', () => {
  const screens = screenFiles();

  it('finds the screens to scan at all', () => {
    // Without this the suite below would silently pass if the walk broke or the
    // directory moved — zero cases is not zero violations. The repository has
    // well over twenty screens; the floor only has to be high enough that an
    // empty or one-file result fails.
    expect(screens.length).toBeGreaterThan(20);
  });

  it.each(screens)('$name has no hardcoded Korean string', ({ path }) => {
    const body = code(readFileSync(path, 'utf8'));
    const literals = [...body.matchAll(/['"`]([^'"`\n]*[가-힣][^'"`\n]*)['"`]/g)].map(
      (match) => match[1],
    );
    // A screen renders copy through useI18n, so a Korean literal here is text
    // an English reader would be shown.
    expect(literals, `${path} hardcodes Korean copy`).toEqual([]);
  });
});
