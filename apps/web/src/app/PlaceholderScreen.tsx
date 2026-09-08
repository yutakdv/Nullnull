import { useI18n } from '../i18n/I18nProvider.js';

// Routes exist before their screens do; say so instead of rendering nothing.
export function PlaceholderScreen({ routeId }: { routeId: string }) {
  const { t } = useI18n();
  return (
    <section aria-labelledby="placeholder-heading">
      <h1 id="placeholder-heading">{t('app.name')}</h1>
      <p data-testid="placeholder-route">{routeId}</p>
    </section>
  );
}
