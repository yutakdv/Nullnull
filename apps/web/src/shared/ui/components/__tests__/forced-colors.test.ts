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
function forcedColorBlocks(css: string): string {
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
  return blocks.join('\n');
}

describe('selection survives with colour removed', () => {
  it.each(SELECTION_STYLES)(
    '$selector keeps a border or outline in forced colours',
    ({ file, selector }) => {
      const css = readFileSync(file, 'utf8');
      const forced = forcedColorBlocks(css);

      expect(
        forced,
        `${file} has no @media (forced-colors: active) block, so ${selector} is colour-only there`,
      ).not.toBe('');

      // The class name, minus any attribute part, is what appears in the block.
      const className = selector.split('[')[0] ?? selector;
      expect(
        forced.includes(className),
        `${file}: ${selector} is not covered by the forced-colors block`,
      ).toBe(true);
      expect(
        /outline|border/.test(forced),
        `${file}: the forced-colors block sets neither outline nor border`,
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
