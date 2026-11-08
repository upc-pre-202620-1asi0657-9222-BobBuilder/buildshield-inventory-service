-- Inventario (inventory_db). Portado de V9 y V16 del Core (esquema inventory), ahora en el esquema public de
-- su propia base. location_id (almacén u obra), material_id, order_id y order_line_id son de otros servicios:
-- sin llave foránea (copias en las tablas *_projection).

-- Existencias por lugar (almacén u obra) y material. version: bloqueo optimista (QAS02).
CREATE TABLE stock_items (
    id              UUID PRIMARY KEY,
    organization_id UUID           NOT NULL,
    location_id     UUID           NOT NULL,
    material_id     UUID           NOT NULL,
    available_qty   NUMERIC(14, 3) NOT NULL,
    reserved_qty    NUMERIC(14, 3) NOT NULL,
    version         BIGINT         NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    created_by      UUID           NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    updated_by      UUID           NOT NULL,
    CONSTRAINT uk_stock_items_location_material UNIQUE (organization_id, location_id, material_id),
    CONSTRAINT uk_stock_items_org_id UNIQUE (organization_id, id),
    -- QAS02: última defensa; el inventario nunca queda negativo aunque falle la aplicación.
    CONSTRAINT ck_stock_items_non_negative CHECK (available_qty >= 0 AND reserved_qty >= 0)
);

CREATE INDEX idx_stock_items_location ON stock_items (organization_id, location_id);

-- Movimientos: solo se agregan, nunca se modifican.
-- ENTRY (entrada o recepción en obra), DEDUCTION (descuento directo), RESERVE (disponible a reservado),
-- RELEASE (reservado a disponible) y DISPATCH (salida de lo reservado al partir el despacho).
CREATE TABLE stock_movements (
    id              UUID PRIMARY KEY,
    organization_id UUID           NOT NULL,
    stock_item_id   UUID           NOT NULL,
    type            VARCHAR(10)    NOT NULL,
    quantity        NUMERIC(14, 3) NOT NULL,
    balance_after   NUMERIC(14, 3) NOT NULL,
    reference       VARCHAR(200),
    occurred_at     TIMESTAMPTZ    NOT NULL,
    recorded_by     UUID           NOT NULL,
    CONSTRAINT fk_stock_movements_org_item
        FOREIGN KEY (organization_id, stock_item_id) REFERENCES stock_items (organization_id, id),
    CONSTRAINT ck_stock_movements_type CHECK (type IN ('ENTRY', 'DEDUCTION', 'RESERVE', 'RELEASE', 'DISPATCH')),
    CONSTRAINT ck_stock_movements_quantity CHECK (quantity > 0 AND balance_after >= 0)
);

CREATE INDEX idx_stock_movements_item ON stock_movements (stock_item_id, occurred_at);

-- Reserva por línea de pedido aprobado (saga, RF40) y su consumo al partir cada despacho (RF41).
CREATE TABLE stock_reservations (
    id              UUID PRIMARY KEY,
    organization_id UUID           NOT NULL,
    stock_item_id   UUID           NOT NULL,
    order_id        UUID           NOT NULL,
    order_line_id   UUID           NOT NULL,
    quantity        NUMERIC(14, 3) NOT NULL,
    consumed_qty    NUMERIC(14, 3) NOT NULL,
    status          VARCHAR(10)    NOT NULL,
    reserved_at     TIMESTAMPTZ    NOT NULL,
    reserved_by     UUID           NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL,
    -- Una sola reserva por línea de pedido: repetir OrderApproved no reserva dos veces.
    CONSTRAINT uk_stock_reservations_order_line UNIQUE (organization_id, order_line_id),
    CONSTRAINT fk_stock_reservations_org_item
        FOREIGN KEY (organization_id, stock_item_id) REFERENCES stock_items (organization_id, id),
    CONSTRAINT ck_stock_reservations_status CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    -- Nunca se consume más de lo reservado.
    CONSTRAINT ck_stock_reservations_quantities CHECK (quantity > 0 AND consumed_qty >= 0 AND consumed_qty <= quantity)
);

CREATE INDEX idx_stock_reservations_order ON stock_reservations (organization_id, order_id);

-- Copias locales (plan §7) alimentadas por buildshield.organization, fuera del outbox.
CREATE TABLE location_projection (
    location_id     UUID PRIMARY KEY,
    organization_id UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    address         VARCHAR(200),
    active          BOOLEAN      NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_location_projection_type CHECK (type IN ('WORKSITE', 'WAREHOUSE'))
);

CREATE INDEX idx_location_projection_organization ON location_projection (organization_id, type);

CREATE TABLE material_projection (
    material_id       UUID PRIMARY KEY,
    organization_id   UUID          NOT NULL,
    sku               VARCHAR(40)   NOT NULL,
    name              VARCHAR(150)  NOT NULL,
    unit              VARCHAR(10)   NOT NULL,
    tolerance_percent NUMERIC(5, 2) NOT NULL,
    active            BOOLEAN       NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_material_projection_organization ON material_projection (organization_id);

CREATE TABLE assignment_projection (
    assignment_id   UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    location_id     UUID        NOT NULL,
    location_type   VARCHAR(20) NOT NULL,
    active          BOOLEAN     NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_assignment_projection_user ON assignment_projection (organization_id, user_id) WHERE active;

-- Transactional Outbox (kernel compartido): eventos escritos en la misma transacción que el cambio de
-- negocio; OutboxPublisher los envía a Kafka y marca published_at.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    organization_id UUID         NOT NULL,
    actor_id        UUID,
    correlation_id  VARCHAR(64),
    payload         JSONB        NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_pending ON outbox_events (occurred_at, id) WHERE published_at IS NULL;

-- Consumidor idempotente (kernel compartido): un evento se procesa una sola vez.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
