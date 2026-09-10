package io.nullnull.identity.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@link OwnerEntity}. Package-private: no module reaches this type. */
interface OwnerJpaRepository extends JpaRepository<OwnerEntity, UUID> {

    /**
     * {@code SELECT ... FOR UPDATE} on a non-deleted owner. PESSIMISTIC_WRITE rather than a shared lock:
     * commands that also update the owner row would otherwise have to upgrade a shared lock and could
     * deadlock, and the documented lock order gives the owner row a single strength.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OwnerEntity o where o.id = :id and o.deletedAt is null")
    Optional<OwnerEntity> lockAlive(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OwnerEntity o where o.id = :id")
    Optional<OwnerEntity> lockAny(@Param("id") UUID id);
}
