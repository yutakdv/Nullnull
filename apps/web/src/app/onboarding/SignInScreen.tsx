import { useId, useState } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import { BottomCta } from '../../shared/ui/index.js';
import styles from './SignInScreen.module.css';

// Figma: A-4 sign-in `804:4537`.
//
// This screen sends nothing. docs/api/openapi.yaml has no auth operation
// (measured: `operationId: .*(login|signin|auth|oauth|register)` matches 0
// lines), so there is no endpoint to post to and no error code to render. #264
// asks BE for the contract; #265 tracks this screen.
//
// Submitting therefore validates only the official demo credentials in the
// browser, then navigates to the feed. They are not sent anywhere and no
// account is created or linked, so the lead copy explicitly says cross-device
// continuity is still coming rather than promising a contract that does not
// exist. Wiring a fetch to a guessed path would 404 and read to the traveller
// as "my password is wrong".
//
// profile.test.tsx asserts no request matching /login|auth|session\/account/
// leaves the app. That assertion stays true here and is what guards this
// promise: whoever adds `useSignIn` has to face it deliberately.
//
// AGENTS.md rule 14: signing in is an ADDITION. The anonymous path stays whole,
// which is why the secondary line offers to keep browsing rather than treating
// this screen as a gate.

const DEMO_ACCOUNT = 'openapi';
const DEMO_PASSWORD = '2026openapi!';

export function SignInScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const idField = useId();
  const passwordField = useId();
  const failureMessage = useId();

  // Controlled because the submit button's disabled state is derived from them.
  // An uncontrolled form would need a separate "has the user typed" signal.
  const [account, setAccount] = useState(DEMO_ACCOUNT);
  const [password, setPassword] = useState(DEMO_PASSWORD);
  const [failed, setFailed] = useState(false);

  const ready = account !== '' && password !== '';

  function submit(event: React.FormEvent<HTMLFormElement>) {
    // preventDefault first: without it the browser submits the form itself and
    // reloads the app with the password in the query string — invariant 10.
    event.preventDefault();
    // Demo-only validation. Exact equality is intentional: trimming would
    // silently accept a different credential, while partial matching would
    // make this screen a misleading stand-in for real authentication.
    if (account !== DEMO_ACCOUNT || password !== DEMO_PASSWORD) {
      setFailed(true);
      return;
    }
    setFailed(false);
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
              aria-describedby={failed ? failureMessage : undefined}
              aria-invalid={failed || undefined}
              autoComplete="username"
              className={styles.input}
              id={idField}
              onChange={(event) => {
                setAccount(event.target.value);
                setFailed(false);
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
              aria-describedby={failed ? failureMessage : undefined}
              aria-invalid={failed || undefined}
              autoComplete="current-password"
              className={styles.input}
              id={passwordField}
              onChange={(event) => {
                setPassword(event.target.value);
                setFailed(false);
              }}
              placeholder={t('signIn.password.placeholder')}
              type="password"
              value={password}
            />
          </div>
        </div>

        {failed ? (
          <p className={styles.notice} id={failureMessage} role="alert">
            {t('signIn.failed')}
          </p>
        ) : null}

        <BottomCta
          disabled={!ready}
          label={t('signIn.submit')}
          secondary={
            <button
              className={styles.anonymous}
              onClick={() => {
                void navigate('/feed', { replace: true });
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
