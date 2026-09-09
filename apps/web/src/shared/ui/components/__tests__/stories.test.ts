// COMPONENT_CATALOG §4: every component in the barrel is registered, and
// interactive nodes show pointer, keyboard, disabled and loading states.
//
// Storybook is where those states are reviewed, so a component without a story
// has variants nobody can look at. This test names the gap rather than letting
// it sit: the allowlist below is the record of what is still missing and why,
// so adding a component silently is not possible.
import { readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const dir = 'src/shared/ui/components';

/**
 * Components with no story yet, each with the reason.
 *
 * Both need a contract fixture to render honestly — a hand-written object in a
 * story would be the parallel model TEST_STRATEGY.md:117 forbids — so they wait
 * for BE/AI's real responses rather than getting invented data.
 */
const AWAITING_FIXTURES = new Set(['CandidateCard', 'DataAttribution']);

function componentNames(): string[] {
  return readdirSync(dir)
    .filter((f) => f.endsWith('.tsx') && !f.endsWith('.stories.tsx'))
    .map((f) => f.replace('.tsx', ''));
}

describe('every component is reviewable in Storybook', () => {
  const components = componentNames();
  const withStories = new Set(
    readdirSync(dir)
      .filter((f) => f.endsWith('.stories.tsx'))
      .map((f) => f.replace('.stories.tsx', '')),
  );

  it('finds the component set', () => {
    // A glob that stops matching would make every assertion below vacuous.
    expect(components.length).toBeGreaterThan(15);
  });

  it.each(componentNames())('%s has a story or a recorded reason', (name) => {
    const covered = withStories.has(name) || AWAITING_FIXTURES.has(name);
    expect(
      covered,
      `${name} has no story. Add one, or add it to AWAITING_FIXTURES with why.`,
    ).toBe(true);
  });

  it('keeps the exemption list honest', () => {
    // An exemption for a component that now has a story is stale, and hides the
    // next one that genuinely lacks it.
    for (const name of AWAITING_FIXTURES) {
      expect(
        withStories.has(name),
        `${name} is exempt but now has a story; remove it from AWAITING_FIXTURES.`,
      ).toBe(false);
      expect(componentNames()).toContain(name);
    }
  });
});

describe('interactive stories cover the states the catalog requires', () => {
  // §4: "interactive node는 pointer, keyboard, disabled, loading 상태를 검증한다".
  // Disabled is the one most often skipped, and the one that hides a control
  // the user cannot reach, so it is asserted for the components that have it.
  const needsDisabled = ['BottomCta', 'Chip', 'SearchField', 'Segment'];

  it.each(needsDisabled)('%s shows a disabled state', (name) => {
    const source = readFileSync(`${dir}/${name}.stories.tsx`, 'utf8');
    expect(source).toMatch(/disabled|disabledReason/i);
  });
});
