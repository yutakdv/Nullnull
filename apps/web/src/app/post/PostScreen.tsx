import { useNavigate, useParams } from 'react-router';
import { useI18n } from '../../i18n/I18nProvider.js';
import {
  isProblem,
  usePost,
  useSavePost,
  useUnsavePost,
} from '../../shared/api/index.js';
import { NavBar, PlaceAttribution } from '../../shared/ui/index.js';
import { isSafeUrl } from '../../shared/url/safe-url.js';
import styles from './PostScreen.module.css';

// Figma: S03-D post detail `804:4595` (FR-PST-01, FR-PST-02).
//
// The distinction this screen exists to keep: saving a POST and adding a PLACE
// to a trip are different actions on different resources (invariant 1).
//
//   - Saving writes to /posts/{postId}/saved. No trip is in that path, there
//     is no ETag, and nothing here touches a trip cache. A trip's schedule
//     version cannot move as a result.
//   - The places below are read-only here. PostDetail.places is a plain
//     PlaceSummary[] — unlike FeedCard it carries no candidateState — so this
//     screen has nothing to say about whether a place is in a trip, and says
//     nothing. Adding a place as a candidate is FE-303's surface.
//
// MOCK DATA: getPost has no approved example, so the msw fixture behind it is
// a schema-valid guess (packages/contracts). The screen calls the real
// generated client, so BA-032 landing removes the fixture and handler only.

export function PostScreen() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const { postId = null } = useParams<{ postId: string }>();
  const post = usePost(postId);
  const save = useSavePost(postId ?? '');
  const unsave = useUnsavePost(postId ?? '');

  const pending = save.isPending || unsave.isPending;
  const failed = save.isError || unsave.isError;

  function back() {
    void navigate('/feed');
  }

  if (post.isPending) {
    return (
      <section aria-labelledby="post-heading" className={styles.screen}>
        <NavBar backLabel={t('post.back')} onBack={back} />
        <h1 className={styles.title} id="post-heading">
          {t('post.loading')}
        </h1>
        <p className={styles.state} role="status">
          {t('post.loading')}
        </p>
      </section>
    );
  }

  if (post.isError || !post.data) {
    // A deep link to a post that does not exist is not a failure worth
    // retrying, and FR-PST-01 asks for the two to be distinguishable.
    const missing = isProblem(post.error) && post.error.code === 'NOT_FOUND';
    return (
      <section aria-labelledby="post-heading" className={styles.screen}>
        <NavBar backLabel={t('post.back')} onBack={back} />
        <h1 className={styles.title} id="post-heading">
          {missing ? t('post.notFound') : t('post.error')}
        </h1>
        <p className={styles.state} role="alert">
          {missing ? t('post.notFound') : t('post.error')}
        </p>
        {missing ? null : (
          <button
            className={styles.retry}
            onClick={() => {
              void post.refetch();
            }}
            type="button"
          >
            {t('post.retry')}
          </button>
        )}
      </section>
    );
  }

  const detail = post.data;

  // What the cover's licence permits, read from the asset rather than assumed.
  //
  // `attributionRequired` with no `attributionText` is the one combination that
  // cannot be rendered compliantly: the licence demands a credit and the server
  // has not supplied the words for it. Showing the picture bare would breach the
  // licence and composing a credit would breach CMP-ATT-003, so the image is
  // withheld — the same choice PlaceThumbnail makes for an uncreditable place
  // photo. An asset that requires nothing (the first-party case, which is what
  // every published cover is today) renders exactly as before.
  const asset = detail.coverAsset ?? null;
  const coverCredit =
    asset?.attributionRequired === true ? (asset.attributionText ?? null) : null;
  // An https cover only (FE-603-T12). A refused URL withholds the image and
  // its credit together: a credit line under no picture credits nothing.
  const coverBlocked =
    !isSafeUrl(detail.coverUrl) ||
    (asset?.attributionRequired === true && coverCredit === null);

  return (
    <section aria-labelledby="post-heading" className={styles.screen}>
      <NavBar backLabel={t('post.back')} onBack={back} />

      {/* CMP-ATT-001 for the cover image.
          `coverAsset` carries the reviewed licence for this picture, and it
          became populated with BA-032/A-024 — it used to be null for every
          post, which is why nothing here read it. Two of its fields are
          obligations, not decoration: `attributionRequired` says the licence
          demands a credit, and `attributionText` is the approved wording to
          show (CMP-ATT-003 — the client never composes one).
          So a cover whose licence requires a credit we do not have is not
          rendered, the same rule PlaceThumbnail applies to place images.
          `alt` comes from the asset when the server supplies one; the empty
          fallback keeps it decorative rather than inventing a description. */}
      {coverBlocked ? null : (
        <img
          alt={detail.coverAsset?.alt ?? ''}
          className={styles.cover}
          src={detail.coverUrl}
        />
      )}
      {coverBlocked || coverCredit === null ? null : (
        <p className={styles.coverCredit}>{coverCredit}</p>
      )}

      <div className={styles.titleBlock}>
        <h1 className={styles.title} id="post-heading">
          {detail.title}
        </h1>
        {detail.excerpt ? <p className={styles.excerpt}>{detail.excerpt}</p> : null}
        <p className={styles.body}>{detail.body}</p>
      </div>

      {failed ? (
        <p className={styles.state} role="alert">
          {t('post.saveFailed')}
        </p>
      ) : null}

      {detail.places.length > 0 ? (
        <section aria-labelledby="post-places" className={styles.places}>
          <h2 className={styles.sectionHead} id="post-places">
            {t('post.places')}
          </h2>
          <ul className={styles.placeList}>
            {detail.places.map((place) => (
              <li className={styles.place} key={place.id}>
                <span className={styles.placeName}>{place.name}</span>
                {/* categoryName, never categoryCode: the contract calls the
                    code opaque provider text and says to render nothing when
                    the name is null. */}
                {place.categoryName ? (
                  <span className={styles.placeMeta}>{place.categoryName}</span>
                ) : null}
                {place.address ? (
                  <span className={styles.placeMeta}>{place.address}</span>
                ) : null}
                {/* CMP-ATT-001: a KTO-sourced place carries its credit
                    wherever it appears, shown verbatim (CMP-ATT-003). */}
                <PlaceAttribution compact place={place} />
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className={styles.saveRow}>
        <button
          className={styles.save}
          disabled={pending}
          onClick={() => {
            if (detail.saved) {
              unsave.mutate();
              return;
            }
            save.mutate();
          }}
          type="button"
        >
          {pending ? t('post.saving') : detail.saved ? t('post.unsave') : t('post.save')}
        </button>
        {/* Stated, not implied: a user who just pressed save needs to know the
            trip did not change. The words never say 담기 or 일정, which belong
            to the candidate flow. */}
        <span className={styles.saveNote}>{t('post.saveNote')}</span>
      </div>
    </section>
  );
}
