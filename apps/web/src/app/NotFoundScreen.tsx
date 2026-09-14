import { Link } from 'react-router';
import { useI18n } from '../i18n/I18nProvider.js';

export function NotFoundScreen() {
  const { t } = useI18n();
  return (
    <section aria-labelledby="not-found-heading">
      <h1 id="not-found-heading">{t('app.notFound.title')}</h1>
      <Link to="/">{t('app.notFound.back')}</Link>
    </section>
  );
}
