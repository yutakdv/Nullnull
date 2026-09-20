import { Link } from 'react-router';
import { useI18n } from '../i18n/I18nProvider.js';
import styles from './NotFoundScreen.module.css';

export function NotFoundScreen() {
  const { t } = useI18n();
  return (
    <section aria-labelledby="not-found-heading" className={styles.screen}>
      <h1 className={styles.title} id="not-found-heading">
        {t('app.notFound.title')}
      </h1>
      <Link className={styles.back} to="/">
        {t('app.notFound.back')}
      </Link>
    </section>
  );
}
