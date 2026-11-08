package pe.buildshield.inventory.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Raíz de agregado de negocio: identificador UUID, organización (de {@link OrganizationScopedEntity}),
 * auditoría de creación y modificación, y {@code version} para bloqueo optimista.
 *
 * <p>Como {@code AbstractAggregateRoot} de Spring Data, permite registrar eventos de dominio que el
 * repositorio publica al guardar.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableAbstractAggregateRoot<A extends AuditableAbstractAggregateRoot<A>>
        extends OrganizationScopedEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Transient
    private final transient List<Object> domainEvents = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    /** Registra un evento de dominio que se publicará al guardar el agregado. */
    protected <E> E registerEvent(E event) {
        domainEvents.add(event);
        return event;
    }

    @DomainEvents
    protected Collection<Object> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    @AfterDomainEventPublication
    protected void clearDomainEvents() {
        domainEvents.clear();
    }
}
