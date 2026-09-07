import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import tokens from '../tokens.json' with { type: 'json' };

// Read the generated CSS as text so the test verifies the actual build output,
// not a re-derivation of it.
const css = readFileSync(join(process.cwd(), 'src/design/tokens.css'), 'utf8');

describe('design tokens', () => {
  it('emits every semantic colour as an alias of a primitive', () => {
    for (const [name, alias] of Object.entries(tokens.semantic)) {
      const variable = '--' + name.replaceAll('/', '-');
      const target = '--' + alias.replaceAll('/', '-');
      expect(css).toContain(`${variable}: var(${target});`);
      expect(tokens.primitive).toHaveProperty(alias);
    }
  });

  it('emits every numeric token in px', () => {
    for (const [name, value] of Object.entries(tokens.number)) {
      expect(css).toContain(`--${name.replaceAll('/', '-')}: ${value}px;`);
    }
  });

  it('binds every text style to a font-size token that exists', () => {
    for (const style of tokens.textStyles) {
      expect(tokens.number).toHaveProperty(style.sizeVar);
      expect(tokens.number[style.sizeVar as keyof typeof tokens.number]).toBe(style.size);
    }
  });

  it('keeps 11px and 12px separate, per the design decision', () => {
    expect(tokens.number['font/caption']).toBe(11);
    expect(tokens.number['font/label']).toBe(12);
  });
});
