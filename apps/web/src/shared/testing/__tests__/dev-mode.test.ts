import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const webRoot = resolve(process.cwd());
const packageJson = JSON.parse(
  readFileSync(resolve(webRoot, 'package.json'), 'utf8'),
) as { scripts: Record<string, string> };
const playwrightConfig = readFileSync(resolve(webRoot, 'playwright.config.ts'), 'utf8');
const viteConfig = readFileSync(resolve(webRoot, 'vite.config.ts'), 'utf8');

describe('local API mode', () => {
  it('uses the real API by default and makes fixtures explicit', () => {
    expect(packageJson.scripts.dev).not.toContain('VITE_API_MOCKING=on');
    expect(packageJson.scripts['dev:mock']).toContain('VITE_API_MOCKING=on');
  });

  it('keeps deterministic browser tests on the explicit mock command', () => {
    expect(playwrightConfig).toContain("command: 'npm run dev:mock -- --host 127.0.0.1'");
  });

  it('uses the upstream Host only when a deployed API target is configured', () => {
    expect(viteConfig).toContain(
      "const apiTarget = configuredApiTarget ?? 'http://localhost:8080'",
    );
    expect(viteConfig).toContain('changeOrigin: configuredApiTarget !== undefined');
  });
});
