import type { components } from '@nullnull/api-client';
import { CrowdLevel } from './CrowdLevel.js';
import { DataAttribution } from './DataAttribution.js';
import { TripAddButton, type TripAddState } from './TripAddButton.js';
import styles from './FeedPostCard.module.css';

// Figma: `Card / FeedPost` (C34). Takes one FeedCard from listFeed.
//
// Two separate saves exist and this card keeps them apart (CLAUDE.md
// invariant 1): `savedPost` bookmarks the post, `candidateState` says
// whether the place is a candidate of the selected trip. Neither creates a
// TripItem.
//
// Attribution and crowd state come from the response. FCR-011 split them:
// the state label says what kind of reading it is, the attribution says who
// published it, and the two are never merged into one credit.

type FeedCard = components['schemas']['FeedCard'];
type CandidateState = FeedCard['candidateState'];

export interface FeedPostCardProps {
  card: FeedCard;
  onOpenPost?: (postId: string) => void;
  onAddCandidate?: (placeId: string) => void;
  /** Overrides the derived button state while a save is in flight or failed. */
  addState?: TripAddState;
}

/** The API's candidate state maps onto the add button's own states. */
function toAddState(state: CandidateState): TripAddState {
  switch (state) {
    case 'SAVED_TO_SELECTED_TRIP':
    case 'SCHEDULED_IN_SELECTED_TRIP':
      return 'saved';
    case 'NO_TRIP_SELECTED':
      return 'no-trip';
    default:
      return 'idle';
  }
}

export function FeedPostCard({
  card,
  onOpenPost,
  onAddCandidate,
  addState,
}: FeedPostCardProps) {
  const { post, primaryPlace, crowd } = card;
  return (
    <article className={styles.card}>
      <button
        type="button"
        className={styles.cover}
        onClick={() => onOpenPost?.(post.id)}
        aria-label={post.title}
      >
        <img src={post.coverUrl} alt="" loading="lazy" />
      </button>

      <div className={styles.body}>
        <CrowdLevel crowd={crowd ?? null} />
        <h3 className={styles.title}>{post.title}</h3>
        <p className={styles.region}>{primaryPlace.name}</p>
        {crowd ? <DataAttribution provenance={crowd.provenance} compact /> : null}
      </div>

      <div className={styles.action}>
        <TripAddButton
          state={addState ?? toAddState(card.candidateState)}
          onClick={() => onAddCandidate?.(primaryPlace.id)}
        />
      </div>
    </article>
  );
}
