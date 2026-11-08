-- Alineación de inventory_db con el ERD corregido (wiki/informe-avance2/er-corregido.md, Figura 34.3), ampliado por el
-- equipo para conservar conteos, devoluciones y movimientos: DESVIACIONES.md dice qué se amplió y qué cambió de forma.
-- Ningún dato de negocio se pierde: lo que cambia de tabla se copia.

-- 1. Conteos de validación: stock_counts y stock_count_lines quedan como en V2, con sus datos. Solo la FK de las líneas
-- pasa a ser de una columna (la compuesta exigía uk_stock_counts_org_id).
ALTER TABLE stock_count_lines DROP CONSTRAINT fk_stock_count_lines_count;
ALTER TABLE stock_counts DROP CONSTRAINT uk_stock_counts_org_id;
ALTER TABLE stock_count_lines ADD CONSTRAINT fk_stock_count_lines_count
    FOREIGN KEY (count_id) REFERENCES stock_counts (id);

-- 2. Devoluciones de saldos (reemplazan a material_returns y material_return_lines), con los mismos identificadores.
CREATE TABLE balance_returns (
    id                 UUID PRIMARY KEY,
    organization_id    UUID        NOT NULL,
    worksite_id        UUID        NOT NULL,
    destination_id     UUID        NOT NULL,
    status             VARCHAR(15) NOT NULL,
    return_dispatch_id UUID,
    note               VARCHAR(500),
    created_by         UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    requested_by       UUID,
    requested_at       TIMESTAMPTZ,
    decided_by         UUID,
    decided_at         TIMESTAMPTZ,
    rejection_reason   VARCHAR(500),
    dispatched_by      UUID,
    dispatched_at      TIMESTAMPTZ,
    received_by        UUID,
    received_at        TIMESTAMPTZ,
    cancelled_by       UUID,
    cancelled_at       TIMESTAMPTZ,
    version            BIGINT      NOT NULL,
    CONSTRAINT ck_balance_returns_status CHECK (status IN (
        'REGISTERED', 'REQUESTED', 'APPROVED', 'REJECTED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_balance_returns_worksite ON balance_returns (organization_id, worksite_id, created_at DESC);
CREATE INDEX idx_balance_returns_destination ON balance_returns (organization_id, destination_id, created_at DESC);

-- Lo rechazado no se guarda: es lo declarado menos lo aceptado.
CREATE TABLE balance_return_lines (
    id                UUID PRIMARY KEY,
    organization_id   UUID           NOT NULL,
    balance_return_id UUID           NOT NULL,
    material_id       UUID           NOT NULL,
    position          INT            NOT NULL,
    declared_qty      NUMERIC(14, 3) NOT NULL,
    detail            VARCHAR(200),
    accepted_qty      NUMERIC(14, 3),
    rejection_reason  VARCHAR(200),
    CONSTRAINT uk_balance_return_lines_material UNIQUE (balance_return_id, material_id),
    CONSTRAINT fk_balance_return_lines_return FOREIGN KEY (balance_return_id) REFERENCES balance_returns (id),
    -- Nunca se acepta más de lo devuelto.
    CONSTRAINT ck_balance_return_lines_quantities CHECK (declared_qty > 0
        AND (accepted_qty IS NULL OR (accepted_qty >= 0 AND accepted_qty <= declared_qty)))
);

INSERT INTO balance_returns (id, organization_id, worksite_id, destination_id, status, note, created_by, created_at,
                             requested_by, requested_at, decided_by, decided_at, rejection_reason, dispatched_by,
                             dispatched_at, received_by, received_at, cancelled_by, cancelled_at, version)
SELECT id, organization_id, worksite_id, warehouse_id, status, note, registered_by, registered_at,
       requested_by, requested_at, decided_by, decided_at, rejection_reason, dispatched_by,
       dispatched_at, received_by, received_at, cancelled_by, cancelled_at, version
FROM material_returns;

INSERT INTO balance_return_lines (id, organization_id, balance_return_id, material_id, position, declared_qty, detail,
                                  accepted_qty, rejection_reason)
SELECT id, organization_id, return_id, material_id, position, quantity, detail, accepted_qty, rejection_reason
FROM material_return_lines;

DROP TABLE material_return_lines;
DROP TABLE material_returns;

-- 3. Movimientos: quién y cuándo se conservan con otro nombre; reference (texto) y balance_after se quedan; source_ref
-- agrega el identificador de lo que originó cada movimiento.
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
ALTER TABLE stock_movements DROP COLUMN seq;
ALTER TABLE stock_movements ADD CONSTRAINT fk_stock_movements_item
    FOREIGN KEY (stock_item_id) REFERENCES stock_items (id);

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
