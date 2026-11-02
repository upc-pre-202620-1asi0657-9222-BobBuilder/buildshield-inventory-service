-- Alineación con inventory_db del ERD corregido (wiki/informe-avance2/er-corregido.md, Figura 34.3). Lo que el ERD no
-- puede guardar y se pierde, con la historia de usuario afectada, está en DESVIACIONES.md.

-- 1. Ajustes por conteo físico (reemplazan a stock_counts y stock_count_lines). Se conservan los conteos cerrados
-- (MATCHED y ADJUSTED): una fila por material contado que tenga existencia. Los abiertos y los anulados no cambiaron
-- el inventario y el ERD no tiene dónde guardarlos.
CREATE TABLE stock_adjustments (
    id              UUID PRIMARY KEY,
    organization_id UUID           NOT NULL,
    stock_item_id   UUID           NOT NULL,
    counted_qty     NUMERIC(14, 3) NOT NULL,
    system_qty      NUMERIC(14, 3) NOT NULL,
    reason          VARCHAR(200)   NOT NULL,
    created_by      UUID           NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_stock_adjustments_item FOREIGN KEY (stock_item_id) REFERENCES stock_items (id),
    CONSTRAINT ck_stock_adjustments_quantities CHECK (counted_qty >= 0 AND system_qty >= 0)
);

CREATE INDEX idx_stock_adjustments_item ON stock_adjustments (organization_id, stock_item_id, created_at DESC);

INSERT INTO stock_adjustments (id, organization_id, stock_item_id, counted_qty, system_qty, reason, created_by, created_at)
SELECT gen_random_uuid(), c.organization_id, i.id, l.counted_qty, l.system_qty,
       left(COALESCE(c.adjustment_reason, 'Conteo sin diferencias'), 200),
       COALESCE(c.adjusted_by, c.finished_by, c.started_by), COALESCE(c.adjusted_at, c.finished_at, c.started_at)
FROM stock_counts c
JOIN stock_count_lines l ON l.count_id = c.id AND l.organization_id = c.organization_id
JOIN stock_items i ON i.organization_id = c.organization_id AND i.location_id = c.warehouse_id
                  AND i.material_id = l.material_id
WHERE c.status IN ('MATCHED', 'ADJUSTED') AND l.counted_qty IS NOT NULL AND l.system_qty IS NOT NULL;

DROP TABLE stock_count_lines;
DROP TABLE stock_counts;

-- 2. Devoluciones de saldos (reemplazan a material_returns y material_return_lines), con los mismos identificadores.
CREATE TABLE balance_returns (
    id                 UUID PRIMARY KEY,
    organization_id    UUID        NOT NULL,
    worksite_id        UUID        NOT NULL,
    destination_id     UUID        NOT NULL,
    status             VARCHAR(15) NOT NULL,
    return_dispatch_id UUID,
    created_by         UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_balance_returns_status CHECK (status IN (
        'REGISTERED', 'REQUESTED', 'APPROVED', 'REJECTED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_balance_returns_worksite ON balance_returns (organization_id, worksite_id, created_at DESC);
CREATE INDEX idx_balance_returns_destination ON balance_returns (organization_id, destination_id, created_at DESC);

CREATE TABLE balance_return_lines (
    id                UUID PRIMARY KEY,
    organization_id   UUID           NOT NULL,
    balance_return_id UUID           NOT NULL,
    material_id       UUID           NOT NULL,
    declared_qty      NUMERIC(14, 3) NOT NULL,
    accepted_qty      NUMERIC(14, 3),
    CONSTRAINT fk_balance_return_lines_return FOREIGN KEY (balance_return_id) REFERENCES balance_returns (id),
    -- Nunca se acepta más de lo devuelto.
    CONSTRAINT ck_balance_return_lines_quantities CHECK (declared_qty > 0
        AND (accepted_qty IS NULL OR (accepted_qty >= 0 AND accepted_qty <= declared_qty)))
);

CREATE INDEX idx_balance_return_lines_return ON balance_return_lines (balance_return_id);

INSERT INTO balance_returns (id, organization_id, worksite_id, destination_id, status, created_by, created_at)
SELECT id, organization_id, worksite_id, warehouse_id, status, registered_by, registered_at FROM material_returns;

INSERT INTO balance_return_lines (id, organization_id, balance_return_id, material_id, declared_qty, accepted_qty)
SELECT id, organization_id, return_id, material_id, quantity, accepted_qty FROM material_return_lines;

DROP TABLE material_return_lines;
DROP TABLE material_returns;

-- 3. Movimientos: quién y cuándo se conservan con otro nombre; reference (texto) pasa a source_ref (identificador).
ALTER TABLE stock_movements RENAME COLUMN occurred_at TO created_at;
ALTER TABLE stock_movements RENAME COLUMN recorded_by TO created_by;
ALTER TABLE stock_movements ADD COLUMN source_ref UUID;

UPDATE stock_movements
SET source_ref = substring(reference from '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')::uuid
WHERE type IN ('DISPATCH', 'DEDUCTION', 'RESERVE', 'RELEASE')
  AND reference ~ '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';

-- El almacén de origen de lo recibido en obra ya no tiene tabla propia: sale de que la entrada en obra (ENTRY) y la
-- salida del almacén (DISPATCH o DEDUCTION) comparten el mismo source_ref, el despacho. worksite_supplies no guardaba
-- el despacho, así que cada fila enlaza la salida más reciente del almacén con una entrada libre de la obra.
DO $$
DECLARE
    supply   RECORD;
    exit_id  UUID;
    entry_id UUID;
    shared   UUID;
BEGIN
    FOR supply IN SELECT * FROM worksite_supplies LOOP
        exit_id := NULL;
        entry_id := NULL;
        SELECT m.id, m.source_ref INTO exit_id, shared
        FROM stock_movements m JOIN stock_items i ON i.id = m.stock_item_id
        WHERE i.organization_id = supply.organization_id AND i.location_id = supply.warehouse_id
          AND i.material_id = supply.material_id AND m.type IN ('DISPATCH', 'DEDUCTION')
        ORDER BY m.created_at DESC, m.seq DESC LIMIT 1;

        SELECT m.id INTO entry_id
        FROM stock_movements m JOIN stock_items i ON i.id = m.stock_item_id
        WHERE i.organization_id = supply.organization_id AND i.location_id = supply.worksite_id
          AND i.material_id = supply.material_id AND m.type = 'ENTRY' AND m.source_ref IS NULL
        ORDER BY m.created_at DESC, m.seq DESC LIMIT 1;

        IF exit_id IS NULL OR entry_id IS NULL THEN
            RAISE NOTICE 'Origen sin enlazar: obra %, material %, almacén %', supply.worksite_id, supply.material_id,
                supply.warehouse_id;
        ELSE
            shared := COALESCE(shared, gen_random_uuid());
            UPDATE stock_movements SET source_ref = shared WHERE id IN (exit_id, entry_id);
        END IF;
    END LOOP;
END $$;

DROP TABLE worksite_supplies;

ALTER TABLE stock_movements DROP CONSTRAINT fk_stock_movements_org_item;
ALTER TABLE stock_movements DROP CONSTRAINT ck_stock_movements_quantity;
ALTER TABLE stock_movements DROP COLUMN balance_after;
ALTER TABLE stock_movements DROP COLUMN reference;
ALTER TABLE stock_movements DROP COLUMN seq;
ALTER TABLE stock_movements ADD CONSTRAINT fk_stock_movements_item
    FOREIGN KEY (stock_item_id) REFERENCES stock_items (id);
ALTER TABLE stock_movements ADD CONSTRAINT ck_stock_movements_quantity CHECK (quantity > 0);

CREATE INDEX idx_stock_movements_item_created ON stock_movements (stock_item_id, created_at, id);
CREATE INDEX idx_stock_movements_org_created ON stock_movements (organization_id, created_at DESC, id DESC);
CREATE INDEX idx_stock_movements_source ON stock_movements (organization_id, source_ref) WHERE source_ref IS NOT NULL;

-- 4. Reservas: sin consumed_qty, un consumo parcial es una fila CONSUMED aparte y la fila RESERVED guarda lo que queda.
-- Lo reservado de la línea es la suma de sus filas. Las reservas parciales existentes se parten igual.
ALTER TABLE stock_reservations DROP CONSTRAINT uk_stock_reservations_order_line;
ALTER TABLE stock_reservations DROP CONSTRAINT ck_stock_reservations_quantities;
ALTER TABLE stock_reservations DROP CONSTRAINT fk_stock_reservations_org_item;

WITH partial AS (
    UPDATE stock_reservations SET quantity = quantity - consumed_qty
    WHERE status IN ('RESERVED', 'RELEASED') AND consumed_qty > 0
    RETURNING organization_id, stock_item_id, order_id, order_line_id, consumed_qty, reserved_at, reserved_by, updated_at
)
INSERT INTO stock_reservations (id, organization_id, stock_item_id, order_id, order_line_id, quantity, consumed_qty,
                                status, reserved_at, reserved_by, updated_at)
SELECT gen_random_uuid(), organization_id, stock_item_id, order_id, order_line_id, consumed_qty, consumed_qty,
       'CONSUMED', reserved_at, reserved_by, updated_at
FROM partial;

ALTER TABLE stock_reservations RENAME COLUMN reserved_at TO created_at;
ALTER TABLE stock_reservations DROP COLUMN consumed_qty;
ALTER TABLE stock_reservations DROP COLUMN reserved_by;
ALTER TABLE stock_reservations DROP COLUMN updated_at;
ALTER TABLE stock_reservations ALTER COLUMN status TYPE VARCHAR(15);
ALTER TABLE stock_reservations ADD CONSTRAINT fk_stock_reservations_item
    FOREIGN KEY (stock_item_id) REFERENCES stock_items (id);
ALTER TABLE stock_reservations ADD CONSTRAINT ck_stock_reservations_quantity CHECK (quantity > 0);

-- Una sola fila vigente por línea de pedido: repetir OrderApproved no reserva dos veces.
CREATE UNIQUE INDEX uk_stock_reservations_line_reserved ON stock_reservations (organization_id, order_line_id)
    WHERE status = 'RESERVED';

-- 5. stock_items: esta llave solo existía para las FK compuestas que ya no están. La única del ERD es lugar y material.
ALTER TABLE stock_items DROP CONSTRAINT uk_stock_items_org_id;

-- 6. Copias locales. Una asignación retirada ya no se guarda: la que no está no da acceso.
ALTER TABLE location_projection DROP COLUMN address;

DROP INDEX idx_assignment_projection_user;
DELETE FROM assignment_projection WHERE NOT active;
ALTER TABLE assignment_projection DROP COLUMN active;
CREATE INDEX idx_assignment_projection_user ON assignment_projection (organization_id, user_id);

-- 7. outbox_events: columnas del ERD. El sobre completo (con aggregateType y actorId) va en payload, como en iam.
UPDATE outbox_events SET payload = jsonb_build_object(
        'eventId', id, 'eventType', event_type,
        'occurredAt', to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'organizationId', organization_id, 'aggregateType', aggregate_type, 'aggregateId', aggregate_id,
        'actorId', actor_id, 'version', 1, 'payload', payload);
ALTER TABLE outbox_events DROP COLUMN aggregate_type;
ALTER TABLE outbox_events DROP COLUMN actor_id;
ALTER TABLE outbox_events ALTER COLUMN aggregate_id TYPE UUID USING aggregate_id::uuid;
ALTER TABLE outbox_events ALTER COLUMN event_type TYPE VARCHAR(60);

-- 8. processed_events: la llave es evento y consumidor. La tabla anterior no decía cuál lo procesó: un evento ya
-- procesado queda marcado para los dos, así ninguno lo repite.
ALTER TABLE processed_events ADD COLUMN consumer VARCHAR(60);
UPDATE processed_events SET consumer = 'OrderingEventConsumer';
ALTER TABLE processed_events ALTER COLUMN consumer SET NOT NULL;
ALTER TABLE processed_events DROP CONSTRAINT processed_events_pkey;
ALTER TABLE processed_events ADD PRIMARY KEY (event_id, consumer);
INSERT INTO processed_events (event_id, consumer, processed_at)
SELECT event_id, 'OrganizationEventConsumer', processed_at FROM processed_events;
