// FE-001-T5: the root error boundary marks each of its lines with that line's
// language.
//
// The boundary sits outside I18nProvider, so it cannot ask for the locale; it
// says the same thing in Korean and in English, side by side. Which language a
// screen reader reads each line in comes from the nearest `lang`. The English
// line always carried one. The Korean line inherited `<html lang>` - and that is
// NOT index.html's "ko" by the time a crash lands: I18nProvider sets it to the
// active locale and nothing resets it when the provider goes. After an English
// session the Korean sentence was read with English rules.
import { render, screen } from '@testing-library/react';
import { act, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '../../i18n/I18nProvider.js';
import { RootErrorBoundary } from '../App.js';

/** Renders once, then throws on the next render - after the provider's effects ran. */
let arm: () => void = () => undefined;
function LaterCrash() {
  const [armed, setArmed] = useState(false);
  arm = () => {
    setArmed(true);
  };
  if (armed) throw new Error('provider tree crashed');
  return <p>running</p>;
}

let htmlLang = '';
beforeEach(() => {
  htmlLang = document.documentElement.lang;
  // React logs the caught render error; keep a passing run readable.
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
});
afterEach(() => {
  document.documentElement.lang = htmlLang;
  localStorage.removeItem('nullnull.locale');
  vi.restoreAllMocks();
});

describe('FE-001-T5 the root error boundary marks each line with its own language', () => {
  it('FE-001-T5 reads the Korean line as Korean after an English session', async () => {
    localStorage.setItem('nullnull.locale', 'en-US');
    render(
      <RootErrorBoundary>
        <I18nProvider>
          <LaterCrash />
        </I18nProvider>
      </RootErrorBoundary>,
    );
    await screen.findByText('running');
    // The premise, measured rather than assumed: the provider set the page to
    // English, and the page is still English once the provider is gone and the
    // boundary is what renders.
    expect(document.documentElement.lang).toBe('en-US');

    act(() => {
      arm();
    });

    const korean = await screen.findByText('앱을 시작하지 못했어요. 새로고침해주세요.');
    expect(document.documentElement.lang).toBe('en-US');
    expect(korean.closest('[lang]')).toHaveAttribute('lang', 'ko');
    expect(screen.getByText('The app could not start. Please reload.')).toHaveAttribute(
      'lang',
      'en',
    );
    // The heading's Korean half too; "Nullnull" reads under the page's language.
    expect(screen.getByText('널널').closest('[lang]')).toHaveAttribute('lang', 'ko');
  });
});
