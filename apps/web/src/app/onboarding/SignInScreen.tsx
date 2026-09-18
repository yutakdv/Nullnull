import { useId, useState } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import { BottomCta } from '../../shared/ui/index.js';
import styles from './SignInScreen.module.css';

// Figma: A-4 sign-in `746:4707`.
//
// This screen sends nothing. docs/api/openapi.yaml has no auth operation
// (measured: `operationId: .*(login|signin|auth|oauth|register)` matches 0
// lines), so there is no endpoint to post to and no error code to render. #264
// asks BE for the contract; #265 tracks this screen.
//
// Submitting therefore navigates to the feed without checking anything. That
// is the same thing continuing without an account does, which is the honest
// behaviour while there is nothing to check against: wiring a fetch to a
// guessed path would 404 and read to the traveller as "my password is wrong".
//
// profile.test.tsx asserts no request matching /login|auth|session\/account/
// leaves the app. That assertion stays true here and is what guards this
// promise: whoever adds `useSignIn` has to face it deliberately.
//
// AGENTS.md rule 14: signing in is an ADDITION. The anonymous path stays whole,
// which is why the secondary line offers to keep browsing rather than treating
// this screen as a gate.

export function SignInScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const idField = useId();
  const passwordField = useId();

  // Controlled because the submit button's disabled state is derived from them.
  // An uncontrolled form would need a separate "has the user typed" signal.
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');

  const ready = account.trim() !== '' && password !== '';

  function submit(event: React.FormEvent<HTMLFormElement>) {
    // preventDefault first: without it the browser submits the form itself and
    // reloads the app with the password in the query string — invariant 10.
    event.preventDefault();
    // Straight to the feed, and still without a request. There is no auth
    // operation to call (#264), so nothing is verified here — pressing the
    // button moves the traveller on, exactly as continuing without an account
    // does. When the contract lands, this is where `useSignIn` goes, and the
    // navigation moves into its onSuccess.
    void navigate('/feed', { replace: true });
  }

  return (
    <section className={styles.screen} aria-labelledby="signin-heading">
      <form className={styles.body} onSubmit={submit} noValidate>
        <div className={styles.copy}>
          <h1 className={styles.title} id="signin-heading">
            {t('signIn.title')}
          </h1>
          <p className={styles.lead}>{t('signIn.lead')}</p>
        </div>

        <div className={styles.fields}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor={idField}>
              {t('signIn.id.label')}
            </label>
            <input
              autoComplete="username"
              className={styles.input}
              id={idField}
              onChange={(event) => {
                setAccount(event.target.value);
              }}
              placeholder={t('signIn.id.placeholder')}
              type="text"
              value={account}
            />
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor={passwordField}>
              {t('signIn.password.label')}
            </label>
            <input
              autoComplete="current-password"
              className={styles.input}
              id={passwordField}
              onChange={(event) => {
                setPassword(event.target.value);
              }}
              placeholder={t('signIn.password.placeholder')}
              type="password"
              value={password}
            />
          </div>
        </div>

        <BottomCta
          disabled={!ready}
          label={t('signIn.submit')}
          secondary={
            <button
              className={styles.anonymous}
              onClick={() => {
                void navigate(-1);
              }}
              type="button"
            >
              {t('signIn.anonymous')}
            </button>
          }
          type="submit"
        />
      </form>
    </section>
  );
}
