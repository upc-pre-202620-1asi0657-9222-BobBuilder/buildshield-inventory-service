-- Sprint 4 (US40, US41 y US43): tipos de movimiento nuevos, conteos de validación con ajuste, origen de lo recibido
-- en obra y devoluciones de saldos al almacén de origen. Como en V1, los ids de otros servicios no llevan llave
-- foránea; todas las tablas se filtran por organization_id.

-- Movimientos: el inventario inicial y las reposiciones del almacén se distinguen de lo recibido en obra (ENTRY);
-- los ajustes por conteo y las devoluciones tienen su propio tipo.
ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movements_type;
ALTER TABLE stock_movements ALTER COLUMN type TYPE VARCHAR(20);
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_type CHECK (type IN (
    'ENTRY', 'DEDUCTION', 'RESERVE', 'RELEASE', 'DISPATCH',
    'INITIAL', 'REPLENISHMENT', 'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'RETURN_OUT', 'RETURN_IN'));

-- Historial paginado de movimientos (US40): del más reciente al más antiguo dentro de la organización. seq desempata
-- los movimientos con la misma hora (los de una misma transacción) en el orden en que se registraron.
ALTER TABLE stock_movements ADD COLUMN seq BIGSERIAL;
CREATE INDEX idx_stock_movements_org_occurred ON stock_movements (organization_id, occurred_at DESC, seq DESC);

-- Conteo de validación de un almacén (US41). Un solo conteo abierto por almacén.
CREATE TABLE stock_counts (
    id                UUID PRIMARY KEY,
    organization_id   UUID         NOT NULL,
    warehouse_id      UUID         NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    note              VARCHAR(200),
    started_by        UUID         NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_by       UUID,
    finished_at       TIMESTAMPTZ,
    review_note       VARCHAR(500),
    reviewed_by       UUID,
    reviewed_at       TIMESTAMPTZ,
    adjustment_reason VARCHAR(500),
    adjusted_by       UUID,
    adjusted_at       TIMESTAMPTZ,
    cancelled_by      UUID,
    cancelled_at      TIMESTAMPTZ,
    version           BIGINT       NOT NULL,
    CONSTRAINT uk_stock_counts_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_stock_counts_status CHECK (status IN (
        'IN_PROGRESS', 'MATCHED', 'DIFFERENCES_FOUND', 'REVIEWED', 'ADJUSTED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uk_stock_counts_open_per_warehouse ON stock_counts (organization_id, warehouse_id)
    WHERE status IN ('IN_PROGRESS', 'DIFFERENCES_FOUND', 'REVIEWED');
CREATE INDEX idx_stock_counts_warehouse ON stock_counts (organization_id, warehouse_id, started_at DESC);

-- Líneas del conteo: lo contado y, al finalizar, lo que decía el sistema (disponible más reservado).
CREATE TABLE stock_count_lines (
    count_id        UUID           NOT NULL,
    organization_id UUID           NOT NULL,
    material_id     UUID           NOT NULL,
    position        INT            NOT NULL,
    counted_qty     NUMERIC(14, 3),
    system_qty      NUMERIC(14, 3),
    difference      NUMERIC(14, 3),
    PRIMARY KEY (count_id, material_id),
    CONSTRAINT fk_stock_count_lines_count
        FOREIGN KEY (organization_id, count_id) REFERENCES stock_counts (organization_id, id),
    CONSTRAINT ck_stock_count_lines_quantities CHECK (counted_qty IS NULL OR counted_qty >= 0)
);

-- De qué almacén recibió cada obra cada material (para devolver al almacén de origen, US43).
CREATE TABLE worksite_supplies (
    organization_id  UUID           NOT NULL,
    worksite_id      UUID           NOT NULL,
    material_id      UUID           NOT NULL,
    warehouse_id     UUID           NOT NULL,
    received_qty     NUMERIC(14, 3) NOT NULL,
    last_received_at TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (organization_id, worksite_id, material_id, warehouse_id),
    CONSTRAINT ck_worksite_supplies_quantity CHECK (received_qty > 0)
);

-- Devolución del saldo de una obra a su almacén de origen (US43).
CREATE TABLE material_returns (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL,
    worksite_id      UUID         NOT NULL,
    warehouse_id     UUID         NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    note             VARCHAR(500),
    registered_by    UUID         NOT NULL,
    registered_at    TIMESTAMPTZ  NOT NULL,
    requested_by     UUID,
    requested_at     TIMESTAMPTZ,
    decided_by       UUID,
    decided_at       TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    dispatched_by    UUID,
    dispatched_at    TIMESTAMPTZ,
    received_by      UUID,
    received_at      TIMESTAMPTZ,
    cancelled_by     UUID,
    cancelled_at     TIMESTAMPTZ,
    version          BIGINT       NOT NULL,
    CONSTRAINT uk_material_returns_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_material_returns_status CHECK (status IN (
        'REGISTERED', 'REQUESTED', 'APPROVED', 'REJECTED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_material_returns_worksite ON material_returns (organization_id, worksite_id, registered_at DESC);
CREATE INDEX idx_material_returns_warehouse ON material_returns (organization_id, warehouse_id, registered_at DESC);

-- Líneas del saldo: lo declarado y, al verificar en el almacén, lo aceptado y lo rechazado con su motivo.
CREATE TABLE material_return_lines (
    id               UUID PRIMARY KEY,
    return_id        UUID           NOT NULL,
    organization_id  UUID           NOT NULL,
    material_id      UUID           NOT NULL,
    position         INT            NOT NULL,
    quantity         NUMERIC(14, 3) NOT NULL,
    detail           VARCHAR(200),
    accepted_qty     NUMERIC(14, 3),
    rejected_qty     NUMERIC(14, 3),
    rejection_reason VARCHAR(200),
    CONSTRAINT uk_material_return_lines_material UNIQUE (return_id, material_id),
    CONSTRAINT fk_material_return_lines_return
        FOREIGN KEY (organization_id, return_id) REFERENCES material_returns (organization_id, id),
    -- Nunca se acepta más de lo devuelto: lo aceptado más lo rechazado es lo declarado.
    CONSTRAINT ck_material_return_lines_quantities CHECK (quantity > 0
        AND (accepted_qty IS NULL OR (accepted_qty >= 0 AND rejected_qty >= 0
             AND accepted_qty + rejected_qty = quantity)))
);
