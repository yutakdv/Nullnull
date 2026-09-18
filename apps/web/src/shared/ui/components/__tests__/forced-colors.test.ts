// TEST_STRATEGY.md §3: "색을 제거해도 state/error/selection 식별".
//
// Forced colours (Windows high contrast) overrides background, colour and
// box-shadow. Anything carrying meaning through those alone vanishes for a
// sighted user, even when aria-pressed still reaches a screen reader — which is
// how the calendar's selected range came to be invisible there.
//
// This reads the stylesheets rather than a rendered page: Playwright's
// forcedColors option does not actually enable the media query in the Chromium
// this project pins (`matchMedia('(forced-colors: active)')` stays false), so a
// browser assertion here would measure nothing and pass regardless. Checking
// the rule exists is weaker than checking it renders, but it is honest about
// what it verifies.
//
// What it verifies, precisely: for each selector below, the file has a
// forced-colors block containing a rule that NAMES THAT SELECTOR, and that
// rule draws an edge. Both halves were once looser and the check saw almost
// nothing:
//
//   - the blocks were joined into one string, so any other rule's outline
//     satisfied every selector in the file. A component could lose its own
//     forced-colors outline and stay green on a neighbour's.
//   - the test was `/outline|border/`, which `border-radius` matches. A block
//     that only rounded a corner counted as "selection is still visible",
//     which is the opposite of what it asserts.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/** Selection styles that would otherwise be colour-only. */
const SELECTION_STYLES: { file: string; selector: string }[] = [
  { file: 'src/shared/ui/components/Chip.module.css', selector: '.chip[data-selected]' },
  {
    file: 'src/shared/ui/components/Segment.module.css',
    selector: '.option[data-selected]',
  },
  { file: 'src/shared/ui/components/TabBar.module.css', selector: '.tab[data-active]' },
  {
    file: 'src/app/onboarding/LanguageScreen.module.css',
    selector: '.selected',
  },
  { file: 'src/app/trip-create/TripWizardScreen.module.css', selector: '.dayEdge' },
];

/** The body of every `@media (forced-colors: active)` block in a file. */
function forcedColorBlocks(css: string): string[] {
  const blocks: string[] = [];
  const marker = '@media (forced-colors: active)';
  let from = css.indexOf(marker);
  while (from !== -1) {
    let depth = 0;
    let i = css.indexOf('{', from);
    const start = i;
    for (; i < css.length; i += 1) {
      if (css[i] === '{') depth += 1;
      else if (css[i] === '}') {
        depth -= 1;
        if (depth === 0) break;
      }
    }
    blocks.push(css.slice(start, i));
    from = css.indexOf(marker, i);
  }
  return blocks;
}

/**
 * The rules inside one forced-colors block, split into selector and body.
 *
 * Needed because the check is per selector: a block can hold several rules,
 * and `TripWizardScreen.module.css` holds three — a grouped
 * `.dayEdge, .dayBetween` and a `.dayEdge` that only thickens the outline. A
 * whole-block match cannot tell which rule carried the outline.
 */
function rules(block: string): { selector: string; body: string }[] {
  const out: { selector: string; body: string }[] = [];
  // Drops the block's opening brace and walks rule by rule.
  //
  // Only the opening one: `forcedColorBlocks` slices up to the index OF the
  // closing brace, so the string it returns does not include it. Trimming a
  // trailing `}` here would therefore eat the last RULE's closing brace and
  // leave that rule unparseable — measured, it dropped `.chip[data-selected]`
  // entirely and four cases failed as "not covered".
  const inner = block.slice(block.indexOf('{') + 1);
  let i = 0;
  while (i < inner.length) {
    const open = inner.indexOf('{', i);
    if (open === -1) break;
    const close = inner.indexOf('}', open);
    if (close === -1) break;
    out.push({
      selector: inner.slice(i, open).trim(),
      body: inner.slice(open + 1, close),
    });
    i = close + 1;
  }
  return out;
}

/**
 * Whether a declaration body draws something that survives forced colours.
 *
 * `border-radius` is excluded on purpose, and it is why this is not a plain
 * `/outline|border/`: rounding a corner paints no edge, so a rule that only
 * rounds would have satisfied "selection is still visible" while showing
 * nothing. The allowed spellings are the ones that actually put ink on screen:
 *
 *   - `outline` and `outline-width`/`outline-style` (`outline-offset` alone
 *     moves an outline that must already exist, so it is not accepted alone)
 *   - `border:`, `border-width`, `border-style`, and the per-side forms
 *     (`border-top: …`), which `ConfirmDialog.module.css` already uses
 *
 * A `border-radius` declaration matches none of these: the character after
 * `border` is `-`, and `radius` is not among the suffixes listed.
 */
function drawsAnEdge(body: string): boolean {
  return /(^|[;{\s])(outline(-width|-style)?|border(-(top|right|bottom|left))?(-width|-style)?)\s*:/.test(
    body,
  );
}

describe('selection survives with colour removed', () => {
  it.each(SELECTION_STYLES)(
    '$selector keeps a border or outline in forced colours',
    ({ file, selector }) => {
      const css = readFileSync(file, 'utf8');
      const blocks = forcedColorBlocks(css);

      expect(
        blocks.length,
        `${file} has no @media (forced-colors: active) block, so ${selector} is colour-only there`,
      ).toBeGreaterThan(0);

      // The class name, minus any attribute part, is what appears in the block.
      const className = selector.split('[')[0] ?? selector;
      // Only the rules that actually name this selector. Judging the whole
      // file's forced-colors text meant any OTHER rule's outline satisfied
      // every case here — `Chip.module.css` could lose its own outline and
      // still pass on a neighbour's.
      const owned = blocks.flatMap(rules).filter(({ selector: sel }) =>
        // A grouped selector counts for each of its parts, which is how
        // `.dayEdge, .dayBetween` covers `.dayEdge`. Compared on the part
        // before any attribute so `.chip[data-selected]` matches `.chip`.
        sel.split(',').some((part) => part.trim().split('[')[0]?.trim() === className),
      );

      expect(
        owned.length,
        `${file}: ${selector} is not covered by the forced-colors block`,
      ).toBeGreaterThan(0);
      expect(
        owned.some(({ body }) => drawsAnEdge(body)),
        `${file}: the forced-colors rules for ${selector} set neither outline nor border ` +
          `(border-radius alone does not count — it rounds a corner without drawing an edge)`,
      ).toBe(true);
    },
  );

  it('finds the styles it claims to check', () => {
    // A renamed file would make every case above vacuous.
    for (const { file, selector } of SELECTION_STYLES) {
      const css = readFileSync(file, 'utf8');
      const className = selector.split('[')[0] ?? selector;
      expect(css.includes(className), `${file} no longer defines ${selector}`).toBe(true);
    }
  });
});
