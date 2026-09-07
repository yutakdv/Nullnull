import { useI18n } from '../i18n/I18nProvider.js';

// Routes exist before their screens do. This makes that explicit rather than
// rendering an empty page, and it never claims a feature works.
export function PlaceholderScreen({ routeId }: { routeId: string }) {
  const { t, locale } = useI18n();
  return (
    <section aria-labelledby="placeholder-heading">
      <h1 id="placeholder-heading">{t('app.name')}</h1>
      <p data-testid="placeholder-route">{routeId}</p>
      <p data-testid="placeholder-locale">{locale}</p>
    </section>
  );
}
