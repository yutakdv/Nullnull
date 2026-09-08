import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      'dist',
      'coverage',
      'playwright-report',
      'test-results',
      'storybook-static',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.mjs'],
    languageOptions: {
      globals: { URL: 'readonly', process: 'readonly', console: 'readonly' },
    },
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
    },
    rules: {
      // Response types come from the generated client; `any` would silently
      // bypass the contract (.claude/rules/frontend.md).
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
);
