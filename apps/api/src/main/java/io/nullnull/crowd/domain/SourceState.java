package io.nullnull.crowd.domain;

/**
 * How a crowd value was produced (docs/data/SOURCE_CATALOG.md §8). Synthetic seed, real observation,
 * forecast, replay and absence stay distinct in the API and on screen; they are never merged.
 */
public enum SourceState { LIVE, FORECAST, REPLAY, QUALITATIVE, STALE, UNAVAILABLE }
