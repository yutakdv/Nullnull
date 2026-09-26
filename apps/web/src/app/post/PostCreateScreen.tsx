import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import type { components } from '@nullnull/api-client';
import { useI18n } from '../../i18n/I18nProvider.js';
import { isProblem, usePlaceSearch, useTrips } from '../../shared/api/index.js';
import {
  useCreatePost,
  useCreatePostImageUpload,
} from '../../shared/api/post-authoring.js';
import {
  ConfirmDialog,
  NavBar,
  PlaceAttribution,
  SearchField,
} from '../../shared/ui/index.js';
import { imageChecksum, uploadPostImage, validatePostImage } from './authoring.js';
import { PlaceSearchMore } from '../../shared/search/PlaceSearchMore.js';
import styles from './PostCreateScreen.module.css';

type Ticket = components['schemas']['UploadTicket'];
type PostRequest = components['schemas']['CreatePostRequest'];
type Place = components['schemas']['PlaceSummary'];

// #312 / Figma 04 (920:257). Existing feed stays public; a trip is the
// authoring workflow prerequisite, not a replacement for session authorization.
export function PostCreateScreen() {
  const { locale, t } = useI18n();
  const navigate = useNavigate();
  const trips = useTrips();
  const reserve = useCreatePostImageUpload();
  const publish = useCreatePost();
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [alt, setAlt] = useState('');
  const [query, setQuery] = useState('');
  const search = usePlaceSearch(query, locale);
  const resultList = useRef<HTMLUListElement>(null);
  const [places, setPlaces] = useState<Place[]>([]);
  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [notice, setNotice] = useState<
    | 'formatError'
    | 'sizeError'
    | 'uploadFailed'
    | 'cancelled'
    | 'publishFailed'
    | 'rejected'
    | null
  >(null);
  const [leaving, setLeaving] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const reservation = useRef<{ file: File; key: string; ticket?: Ticket } | null>(null);
  const pendingPost = useRef<{ request: PostRequest; idempotencyKey: string } | null>(
    null,
  );
  const sending = useRef(false);
  const mounted = useRef(true);
  const fileInput = useRef<HTMLInputElement>(null);
  const hasTrip = trips.isSuccess && trips.data.items.length > 0;
  const locked = publish.isPending || uncertain;
  const dirty = file !== null || title !== '' || body !== '';

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      controller.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (!file) {
      setPreview(null);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  async function upload(selected: File) {
    controller.current?.abort();
    const attempt = new AbortController();
    controller.current = attempt;
    setUploading(true);
    setProgress(0);
    setNotice(null);
    setTicket(null);
    try {
      let entry = reservation.current;
      if (
        !entry ||
        entry.file !== selected ||
        (entry.ticket && Date.parse(entry.ticket.expiresAt) <= Date.now())
      ) {
        entry = { file: selected, key: crypto.randomUUID() };
        reservation.current = entry;
      }
      if (!entry.ticket) {
        const checksumSha256 = await imageChecksum(selected);
        attempt.signal.throwIfAborted();
        entry.ticket = await reserve.mutateAsync({
          idempotencyKey: entry.key,
          request: {
            contentType: selected.type as 'image/jpeg' | 'image/png',
            contentLength: selected.size,
            checksumSha256,
          },
        });
      }
      attempt.signal.throwIfAborted();
      await uploadPostImage(selected, entry.ticket, attempt.signal, setProgress);
      if (mounted.current && controller.current === attempt) setTicket(entry.ticket);
    } catch {
      if (mounted.current && controller.current === attempt)
        setNotice(attempt.signal.aborted ? 'cancelled' : 'uploadFailed');
    } finally {
      if (mounted.current && controller.current === attempt) setUploading(false);
    }
  }

  function choose(event: ChangeEvent<HTMLInputElement>) {
    const selected = event.target.files?.[0];
    event.target.value = '';
    if (!selected) return;
    const error = validatePostImage(selected);
    if (error) {
      setNotice(error === 'format' ? 'formatError' : 'sizeError');
      return;
    }
    setFile(selected);
    pendingPost.current = null;
    void upload(selected);
  }

  function remove() {
    controller.current?.abort();
    controller.current = null;
    reservation.current = null;
    pendingPost.current = null;
    setFile(null);
    setTicket(null);
    setUploading(false);
    setNotice(null);
    fileInput.current?.focus();
  }

  async function submit() {
    if (
      sending.current ||
      !hasTrip ||
      uploading ||
      !ticket ||
      !title.trim() ||
      !body.trim() ||
      places.length === 0
    )
      return;
    sending.current = true;
    setNotice(null);
    pendingPost.current ??= {
      request: {
        uploadId: ticket.uploadId,
        title: title.trim(),
        body: body.trim(),
        altText: alt.trim() || null,
        placeIds: places.map((place) => place.id),
      },
      idempotencyKey: crypto.randomUUID(),
    };
    try {
      const result = await publish.mutateAsync(pendingPost.current);
      if (mounted.current) {
        setUncertain(false);
        void navigate(`/posts/${result.postId}`, { replace: true });
      }
    } catch (error) {
      if (!mounted.current) return;
      // An ambiguous network/server failure must retry exactly the same key
      // and payload, even if the ticket appears expired in the meantime.
      if (isProblem(error) && [400, 404, 422].includes(error.status)) {
        pendingPost.current = null;
        setUncertain(false);
        setNotice('rejected');
        if (error.status === 404 || error.status === 422) {
          setTicket(null);
          reservation.current = null;
        }
      } else {
        setUncertain(true);
        setNotice('publishFailed');
      }
    } finally {
      sending.current = false;
    }
  }

  return (
    <section className={styles.screen} aria-labelledby="author-heading">
      <NavBar
        title={t('author.heading')}
        backLabel={t('author.back')}
        onBack={() => {
          if (publish.isPending) return;
          if (dirty) setLeaving(true);
          else void navigate('/feed');
        }}
      />
      <h1 id="author-heading" className={styles.heading}>
        {t('author.heading')}
      </h1>
      {trips.isPending ? (
        <p role="status">{t('author.loadingTrips')}</p>
      ) : trips.isError ? (
        <div role="alert">
          <p>{t('author.failedTrips')}</p>
          <button
            type="button"
            onClick={() => {
              void trips.refetch();
            }}
          >
            {t('author.retry')}
          </button>
        </div>
      ) : !hasTrip ? (
        <div className={styles.card}>
          <p>{t('author.noTrip')}</p>
          <Link to="/start">{t('author.createTrip')}</Link>
          <Link to="/feed">{t('author.back')}</Link>
        </div>
      ) : (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
          className={styles.form}
        >
          <fieldset disabled={locked} className={styles.card}>
            <legend>{t('author.photo')}</legend>
            {preview ? (
              <img className={styles.preview} src={preview} alt={alt} />
            ) : (
              <div className={styles.placeholder} aria-hidden="true">
                +
              </div>
            )}
            <label className={styles.fileLabel}>
              {file ? t('author.change') : t('author.choose')}
              <input
                ref={fileInput}
                type="file"
                accept="image/jpeg,image/png"
                aria-describedby="author-formats"
                onChange={choose}
              />
            </label>
            <p id="author-formats" className={styles.note}>
              {t('author.formats')}
            </p>
            {file ? (
              <button type="button" onClick={remove}>
                {t('author.remove')}
              </button>
            ) : null}
            {uploading ? (
              <>
                <progress max={100} value={progress} aria-label={t('author.uploading')} />
                <p role="status">
                  {t('author.uploading')} {progress}%
                </p>
                <button type="button" onClick={() => controller.current?.abort()}>
                  {t('author.cancelUpload')}
                </button>
              </>
            ) : ticket ? (
              <p role="status">{t('author.uploaded')}</p>
            ) : file ? (
              <button
                type="button"
                onClick={() => {
                  void upload(file);
                }}
              >
                {t('author.retryUpload')}
              </button>
            ) : null}
          </fieldset>
          <fieldset disabled={locked} className={styles.fields}>
            <label className={styles.textField}>
              {t('author.title')}
              <input
                value={title}
                maxLength={200}
                required
                onChange={(event) => setTitle(event.target.value)}
              />
            </label>
            <label className={styles.textField}>
              {t('author.caption')}
              <textarea
                value={body}
                maxLength={20000}
                required
                rows={5}
                onChange={(event) => setBody(event.target.value)}
              />
            </label>
            <label className={styles.textField}>
              {t('author.alt')}
              <input
                value={alt}
                maxLength={500}
                onChange={(event) => setAlt(event.target.value)}
              />
            </label>
            <h2>{t('author.places')}</h2>
            <SearchField
              label={t('author.search')}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
            {/* A failed first page. A failed later page keeps these results and
                reports beside its own control. */}
            {search.isError && !search.isFetchNextPageError ? (
              <p role="alert">
                {t('author.searchFailed')}{' '}
                <button
                  type="button"
                  onClick={() => {
                    void search.refetch();
                  }}
                >
                  {t('author.retry')}
                </button>
              </p>
            ) : null}
            {query.trim() && search.isSuccess && search.data.items.length === 0 ? (
              <p role="status">{t('author.emptySearch')}</p>
            ) : null}
            <ul className={styles.places} ref={resultList}>
              {search.data?.items.map((place) => (
                <li key={place.id}>
                  <label>
                    <input
                      type="checkbox"
                      checked={places.some((p) => p.id === place.id)}
                      disabled={
                        places.length >= 50 && !places.some((p) => p.id === place.id)
                      }
                      onChange={(event) =>
                        setPlaces((current) =>
                          event.target.checked
                            ? [...current, place]
                            : current.filter((p) => p.id !== place.id),
                        )
                      }
                    />
                    {place.name}
                  </label>
                  <PlaceAttribution place={place} />
                </li>
              ))}
            </ul>
            <PlaceSearchMore list={resultList} search={search} />
            {places.length ? (
              <ul className={styles.places}>
                {places.map((place) => (
                  <li key={place.id}>
                    <button
                      type="button"
                      onClick={() =>
                        setPlaces((current) => current.filter((p) => p.id !== place.id))
                      }
                    >
                      {place.name} ×
                    </button>
                    {/* The chip stands for the place while the post is written,
                        so it carries the place's credit too — beside the
                        button, since a link cannot sit inside one. */}
                    <PlaceAttribution compact place={place} />
                  </li>
                ))}
              </ul>
            ) : null}
          </fieldset>
          {notice ? <p role="alert">{t(`author.${notice}`)}</p> : null}
          <footer className={styles.footer}>
            <p className={styles.note}>{t('author.publicNote')}</p>
            <p className={styles.note}>{t('author.rights')}</p>
            {publish.isPending ? <p role="status">{t('author.publishing')}</p> : null}
            <button
              className={styles.primary}
              type="submit"
              disabled={
                uploading ||
                publish.isPending ||
                !ticket ||
                !title.trim() ||
                !body.trim() ||
                places.length === 0
              }
            >
              {uncertain ? t('author.retryPublish') : t('author.publish')}
            </button>
          </footer>
        </form>
      )}
      <ConfirmDialog
        open={leaving}
        title={t('author.leave')}
        confirmLabel={t('author.discard')}
        cancelLabel={t('author.keep')}
        destructive
        onCancel={() => setLeaving(false)}
        onConfirm={() => {
          controller.current?.abort();
          void navigate('/feed');
        }}
      />
    </section>
  );
}
