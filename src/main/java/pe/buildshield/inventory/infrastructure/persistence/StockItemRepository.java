package pe.buildshield.inventory.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Spring Data JPA de stock_items (solo lectura; filtrado por la organización del contexto). */
interface StockItemRepository extends JpaRepository<StockItemJpaEntity, UUID> {

    List<StockItemJpaEntity> findByLocationIdInOrderByLocationIdAscMaterialIdAsc(Collection<UUID> locationIds);
}
