package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.domain.Owner;
import io.nullnull.identity.domain.OwnerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of V002__owners.sql. {@code spring.jpa.hibernate.ddl-auto=validate} means a mapping that
 * drifts from the migration fails startup, which is the point. The identifier is application-generated
 * (UUID v7), so there is no generator here. This class and its conversions never leave
 * identity.infrastructure.
 */
@Entity
@Table(name = "owners")
class OwnerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private OwnerKind kind;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "locale", nullable = false, length = 35)
    private String locale;

    @Column(name = "timezone", nullable = false, length = 100)
    private String timezone;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    @Column(name = "active_trip_id")
    private UUID activeTripId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected OwnerEntity() {
        // for JPA
    }

    static OwnerEntity fromDomain(Owner owner) {
        OwnerEntity entity = new OwnerEntity();
        entity.id = owner.id();
        entity.kind = owner.kind();
        entity.accountId = owner.accountId();
        entity.locale = owner.locale();
        entity.timezone = owner.timezone();
        entity.onboardingCompleted = owner.onboardingCompleted();
        entity.activeTripId = owner.activeTripId();
        entity.createdAt = owner.createdAt();
        entity.deletedAt = owner.deletedAt();
        return entity;
    }

    void updatePreferences(Owner owner) {
        locale = owner.locale();
        timezone = owner.timezone();
        onboardingCompleted = owner.onboardingCompleted();
        activeTripId = owner.activeTripId();
    }

    void markDeleted(Instant at) {
        deletedAt = at;
        activeTripId = null;
        onboardingCompleted = false;
    }

    void scrubDeleted() {
        accountId = null;
        locale = "ko-KR";
        timezone = "UTC";
        onboardingCompleted = false;
        activeTripId = null;
    }

    Owner toDomain() {
        return new Owner(id, kind, accountId, locale, timezone, onboardingCompleted, activeTripId,
                createdAt, deletedAt);
    }
}
