import type { Decorator, Preview } from '@storybook/react-vite';
import { I18nProvider } from '../src/i18n/I18nProvider.js';
import '../src/styles.css';

// Every story runs inside the app's own providers and design tokens, so what
// Storybook shows is what the app renders.
const withProviders: Decorator = (Story) => (
  <I18nProvider>
    <Story />
  </I18nProvider>
);

const preview: Preview = {
  decorators: [withProviders],
  parameters: {
    // 360px is the narrowest supported width and the default here, so a
    // component that only works on a wide viewport fails visibly
    // (.claude/rules/frontend.md).
    viewport: {
      options: {
        mobile360: { name: '360px', styles: { width: '360px', height: '780px' } },
        mobile768: { name: '768px', styles: { width: '768px', height: '1024px' } },
      },
    },
    a11y: {
      // Report violations rather than failing the render, so a story stays
      // viewable while its problem is visible.
      test: 'todo',
    },
  },
  initialGlobals: {
    viewport: { value: 'mobile360', isRotated: false },
  },
};

export default preview;
