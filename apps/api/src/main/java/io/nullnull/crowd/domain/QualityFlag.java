package io.nullnull.crowd.domain;

/**
 * Collector-side quality findings carried with a snapshot (docs/data/SOURCE_CATALOG.md §7 quality
 * incident registry, §8). Provider drift is quarantined rather than guessed.
 */
public enum QualityFlag { PROVIDER_INCIDENT, SCHEMA_DRIFT, MAPPING_UNCERTAIN, OBSERVED_AT_SKEW, PARTIAL_PAYLOAD }
