import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import type { components } from '@nullnull/api-client/types';
import { useI18n } from '../../i18n/I18nProvider.js';
import { useSavedPosts, useUnsavePost } from '../../shared/api/index.js';
import { NavBar } from '../../shared/ui/index.js';
import styles from './ProfileScreen.module.css';

type SavedPostItem = components['schemas']['SavedPostItem'];

function SavedPostRow({
  item,
  onRemoved,
}: {
  item: SavedPostItem;
  onRemoved: () => void;
}) {
  const { t } = useI18n();
  const unsave = useUnsavePost(item.post.id);
  return (
    <li className={styles.tripRow}>
      <Link
        className={styles.row}
        to={`/posts/${item.post.id}`}
        state={{ from: '/profile/saved-posts' }}
      >
        <span className={styles.rowText}>
          <span className={styles.rowTitle}>{item.post.title}</span>
          {item.post.excerpt ? (
            <span className={styles.rowNote}>{item.post.excerpt}</span>
          ) : null}
        </span>
      </Link>
      <button
        aria-label={t(
          unsave.isPending ? 'savedPosts.unsavingItem' : 'savedPosts.unsaveItem',
          { title: item.post.title },
        )}
        className={styles.rowDelete}
        disabled={unsave.isPending}
        onClick={() => unsave.mutate(undefined, { onSuccess: onRemoved })}
        type="button"
      >
        <span aria-hidden="true">{unsave.isPending ? '…' : '✕'}</span>
      </button>
      {unsave.isError ? <span role="alert">{t('savedPosts.unsaveFailed')}</span> : null}
    </li>
  );
}

/** A-07: SavedPost retrieval remains separate from candidates and scheduled items. */
export function SavedPostsScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const saved = useSavedPosts();
  const [removed, setRemoved] = useState(false);
  const items = saved.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section className={styles.screen} aria-labelledby="saved-posts-heading">
      <NavBar backLabel={t('savedPosts.back')} onBack={() => void navigate('/profile')} />
      <h1 className={styles.title} id="saved-posts-heading">
        {t('savedPosts.title')}
      </h1>
      {saved.isPending ? <p role="status">{t('savedPosts.loading')}</p> : null}
      {saved.isError ? (
        <p role="alert">
          {t('savedPosts.error')}{' '}
          <button
            type="button"
            className={styles.retry}
            onClick={() => void saved.refetch()}
          >
            {t('savedPosts.retry')}
          </button>
        </p>
      ) : null}
      {saved.isSuccess && items.length === 0 ? (
        <p className={styles.card}>{t('savedPosts.empty')}</p>
      ) : null}
      {items.length > 0 ? (
        <ul className={`${styles.card} ${styles.rows}`}>
          {items.map((item) => (
            <SavedPostRow
              item={item}
              key={item.post.id}
              onRemoved={() => setRemoved(true)}
            />
          ))}
        </ul>
      ) : null}
      {saved.hasNextPage ? (
        <button
          className={styles.retry}
          disabled={saved.isFetchingNextPage}
          onClick={() => void saved.fetchNextPage()}
          type="button"
        >
          {t('savedPosts.loadMore')}
        </button>
      ) : null}
      {removed ? (
        <p role="status" aria-live="polite">
          {t('savedPosts.removed')}
        </p>
      ) : null}
    </section>
  );
}
