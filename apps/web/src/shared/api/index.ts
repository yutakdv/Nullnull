// Request-boundary helpers. Mirrors the barrel convention of shared/ui.
export {
  isProblem,
  toProblem,
  PROBLEM_CODES,
  type Problem,
  type ProblemCode,
  type FieldError,
} from './problem.js';
export {
  PROBLEM_POLICY,
  shouldRetry,
  retryDelayMs,
  type ProblemPolicy,
  type RetryPolicy,
  type RecoveryKind,
  type Severity,
  type RetryContext,
} from './problem-policy.js';
export {
  problemPresentation,
  unknownFailurePresentation,
  type ProblemPresentation,
} from './problem-message.js';
export { createQueryClient } from './query-client.js';
export {
  apiBaseUrl,
  currentCsrfToken,
  getApiClient,
  sessionQueryKey,
  currentDeletionToken,
  forgetDeletionToken,
  useCreateTrip,
  useDeletionStatus,
  useRequestDeletion,
  useOptimizationHistory,
  usePlaceSearch,
  useSessionBootstrap,
  useTrip,
  useTrips,
  useReplaceTripInterests,
  useUpdateTrip,
  useTripCandidates,
  useCandidateMatches,
  useAddTripItem,
  candidatesQueryKey,
  useUpdatePreferences,
  tripQueryKey,
  type TripWithETag,
  type TripMutationWithETag,
} from './session.js';
