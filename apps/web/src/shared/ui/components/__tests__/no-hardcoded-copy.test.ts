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
import { basename, join, relative, resolve } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

// vitest runs with apps/web as the root, the same base the fixture test uses.
const COMPONENTS = resolve(process.cwd(), 'src/shared/ui/components');
const SRC = resolve(process.cwd(), 'src');
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

/** The roots walked for Korean literals, relative to src. */
const ROOTS = ['app', 'shared'];

/**
 * Directories under the roots whose Korean is data, not copy: the MSW mock
 * server answers with server-shaped records (a place called 한옥마을, a
 * server-written summary). It runs only with VITE_API_MOCKING and says what the
 * server would say, which is never the client's to translate.
 */
const DATA_DIRS = ['shared/testing/'];

/**
 * Files that may hold Korean outside the locale, each with its reason and the
 * EXACT literals it holds. Exact, so a new Korean string added to an excepted
 * file still fails here - an exception is for these strings, not the file.
 */
const EXCEPTIONS: Record<string, { reason: string; literals: readonly string[] }> = {
  'app/App.tsx': {
    reason:
      'RootErrorBoundary renders outside I18nProvider, so it says Korean and ' +
      'English side by side, each line marked with its own lang (FE-001-T5).',
    literals: ['널널', '앱을 시작하지 못했어요. 새로고침해주세요.'],
  },
  'app/data-guide/guide-sources.ts': {
    reason:
      "The server's approved credit wording and source names, written out " +
      'verbatim and checked against the server by data-guide.test.tsx. A credit ' +
      'is never translated (CMP-ATT-003).',
    literals: [
      '한국관광공사 국문 관광정보',
      '출처: ⓒ한국관광공사',
      '한국관광공사 영문 관광정보',
      '출처: ⓒ한국관광공사',
      '한국관광공사 관광지 집중률 예측',
      '출처: ⓒ한국관광공사',
      '서울 실시간 도시데이터',
      '출처: 서울특별시 「서울시 실시간 도시데이터」(2022년 공개, 공공누리 제1유형)',
    ],
  },
};

/**
 * Every .ts/.tsx file under src/app and src/shared, walked recursively.
 *
 * This walk has been too narrow twice. It first read ONE flat directory, so 23
 * shared components were checked while 28 screens were not - that is how the
 * trip wizard shipped `['일','월','화','수','목','금','토']` as its calendar
 * headers. Then it read screens, but only `.tsx` under src/app and only quoted
 * strings: a `.ts` file there (guide-sources.ts) and all of src/shared outside
 * the components folder went unread.
 *
 * The components that keep Korean defaults (DEFAULTS_WITH_OVERRIDE) are left
 * out: the describe above holds them to their own rule (a prop, and the locale
 * before the default).
 */
function codeFiles(): { name: string; path: string }[] {
  const found: { name: string; path: string }[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== '__tests__') walk(full);
      } else if (
        /\.tsx?$/.test(entry.name) &&
        !entry.name.endsWith('.d.ts') &&
        !entry.name.endsWith('.stories.tsx') &&
        !entry.name.includes('.test.')
      ) {
        found.push({ name: relative(SRC, full), path: full });
      }
    }
  };
  for (const root of ROOTS) walk(join(SRC, root));
  return found.filter(
    ({ name, path }) =>
      !DATA_DIRS.some((dir) => name.startsWith(dir)) &&
      !(path.startsWith(COMPONENTS) && basename(path) in DEFAULTS_WITH_OVERRIDE),
  );
}

/**
 * The Korean-bearing literals in a file, read by the TypeScript parser: JSX
 * text, string literals, and every chunk of a template literal.
 *
 * Not a regex over quotes. That regex could not see JSX text at all - `<p>안녕</p>`
 * has no quote around it - which is how App.tsx's Korean line sat unnoticed.
 * Comments are not nodes, so Figma wording quoted in a comment is not copy.
 */
function koreanLiterals(path: string): string[] {
  const file = ts.createSourceFile(
    path,
    readFileSync(path, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
    path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  const found: string[] = [];
  const visit = (node: ts.Node) => {
    if (
      ts.isJsxText(node) ||
      ts.isStringLiteral(node) ||
      ts.isNoSubstitutionTemplateLiteral(node) ||
      ts.isTemplateHead(node) ||
      ts.isTemplateMiddle(node) ||
      ts.isTemplateTail(node)
    ) {
      const text = node.text.trim();
      if (HANGUL.test(text)) found.push(text);
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
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

describe('FE-001-T6 app and shared code keep their Korean in the locale', () => {
  const files = codeFiles();

  it('finds the files to scan at all, under every directory it walks', () => {
    // Without this the suite below would silently pass if the walk broke or a
    // directory moved - zero cases is not zero violations. Each directory
    // directly under the roots must contribute, so a walk that stopped at one
    // root, or skipped `.ts`, fails here rather than passing on fewer files.
    expect(files.length).toBeGreaterThan(80);
    for (const root of ROOTS) {
      for (const entry of readdirSync(join(SRC, root), { withFileTypes: true })) {
        const dir = `${root}/${entry.name}/`;
        if (!entry.isDirectory() || entry.name === '__tests__') continue;
        if (DATA_DIRS.includes(dir)) continue;
        expect(
          files.some(({ name }) => name.startsWith(dir)),
          `${dir} contributed no file`,
        ).toBe(true);
      }
    }
    expect(files.some(({ name }) => name.endsWith('.ts'))).toBe(true);
  });

  it('keeps every exception pointed at a file it scans', () => {
    // An exception for a file that moved or was deleted is stale, and would
    // quietly excuse the next file given its name.
    for (const name of Object.keys(EXCEPTIONS)) {
      expect(files.map((file) => file.name)).toContain(name);
    }
  });

  it.each(files)('$name has no Korean literal outside the locale', ({ name, path }) => {
    // Screens and shared code read copy through the locale (useI18n,
    // useOptionalI18n), so a Korean literal here is text an English reader
    // would be shown - unless the file is an exception, for exactly its
    // listed strings.
    const expected = [...(EXCEPTIONS[name]?.literals ?? [])].sort();
    expect(koreanLiterals(path).sort(), `${name} holds Korean copy`).toEqual(expected);
  });
});
