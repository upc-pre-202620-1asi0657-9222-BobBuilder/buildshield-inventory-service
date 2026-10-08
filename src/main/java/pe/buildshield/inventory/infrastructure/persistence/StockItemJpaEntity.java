package pe.buildshield.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import pe.buildshield.inventory.domain.model.StockItem;
import pe.buildshield.inventory.shared.persistence.AuditableAbstractAggregateRoot;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tabla stock_items. La columna {@code version} (bloqueo optimista, heredada con {@code @Version}) es la que
 * condiciona los UPDATE de {@link JdbcStockRepository}. Por JPA solo se lee, con el filtro multiempresa.
 */
@Entity
@Table(name = "stock_items")
public class StockItemJpaEntity extends AuditableAbstractAggregateRoot<StockItemJpaEntity> {

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;

    @Column(name = "available_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal availableQty;

    @Column(name = "reserved_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal reservedQty;

    protected StockItemJpaEntity() {
    }

    StockItem toDomain() {
        return new StockItem(getId(), locationId, materialId, availableQty, reservedQty, getVersion());
    }
}
