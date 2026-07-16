import js from '@eslint/js';
import globals from 'globals';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import prettier from 'eslint-config-prettier';

export default [
  { ignores: ['dist/**', 'node_modules/**'] },
  js.configs.recommended,
  react.configs.flat.recommended,
  react.configs.flat['jsx-runtime'], // React 17+ 자동 JSX 런타임 — import React 불필요
  reactHooks.configs.flat['recommended-latest'],
  prettier,
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2024,
      sourceType: 'module',
      globals: { ...globals.browser },
    },
    settings: { react: { version: 'detect' } },
    rules: {
      // 익명 MVP — PropTypes 대신 파일 상단 주석·백엔드 명세로 데이터 계약을 문서화한다
      'react/prop-types': 'off',
      'no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      // 기존 데이터 페칭 패턴(effect 안 setState) 유지 — 동작 보존이 우선이라 off.
      // react-query 등으로 옮기는 시점에 다시 켠다(REFACTORING.md 후속 과제 참고).
      'react-hooks/set-state-in-effect': 'off',
    },
  },
];
