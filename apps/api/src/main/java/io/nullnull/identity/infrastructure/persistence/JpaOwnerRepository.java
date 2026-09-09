package io.nullnull.identity.infrastructure.persistence;

import io.nullnull.identity.application.OwnerRepository;
import io.nullnull.identity.domain.Owner;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner aggregate is a plain read/write, so it uses JPA; only the idempotency reservation needs
 * hand-written SQL. Entities are converted at this boundary and never escape it.
 */
@Repository
public class JpaOwnerRepository implements OwnerRepository {

    private final OwnerJpaRepository owners;
    private final EntityManager entityManager;

    JpaOwnerRepository(OwnerJpaRepository owners, EntityManager entityManager) {
        this.owners = owners;
        this.entityManager = entityManager;
    }

    /**
     * {@code persist} rather than {@code save}: the identifier is assigned by the application, so
     * {@code save} would treat a new owner as detached and issue a needless select before the insert.
     */
    @Override
    @Transactional
    public Owner create(Owner owner) {
        OwnerEntity entity = OwnerEntity.fromDomain(owner);
        entityManager.persist(entity);
        // Session creation uses JDBC in this transaction and needs the owner FK visible now.
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Owner> findById(UUID id) {
        return owners.findById(id).map(OwnerEntity::toDomain);
    }

    /** MANDATORY: a row lock taken in its own short transaction would be released immediately. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Owner> lockAlive(UUID id) {
        // This is the contended lock: every command for one owner waits here, so an expired
        // lock_timeout must surface as the module's own exception rather than a driver failure.
        return BoundedLockWait.on(() -> owners.lockAlive(id)).map(OwnerEntity::toDomain);
    }
}
