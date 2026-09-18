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
// Submitting therefore shows `signIn.pending` and stops. That is a deliberate
// dead end rather than a hidden one: the form is real, the button responds, and
// the traveller is told why nothing happened. Wiring a fetch to a guessed path
// would be worse — it would 404 and read as "my password is wrong".
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
  // Only ever set by submit. Null means "the user has not asked yet", which is
  // different from "the request came back empty".
  const [notice, setNotice] = useState<string | null>(null);

  const ready = account.trim() !== '' && password !== '';

  function submit(event: React.FormEvent<HTMLFormElement>) {
    // The form must not navigate. Without this the browser would reload the
    // app with the password in the query string — invariant 10.
    event.preventDefault();
    setNotice(t('signIn.pending'));
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
                setNotice(null);
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
                setNotice(null);
              }}
              placeholder={t('signIn.password.placeholder')}
              type="password"
              value={password}
            />
          </div>

          {/* Reserved for the field errors the contract will name. Announced
              because it appears after a press, when focus is on the button. */}
          <p aria-live="polite" className={styles.notice}>
            {notice}
          </p>
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
