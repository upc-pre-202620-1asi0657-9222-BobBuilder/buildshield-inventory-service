# Desviaciones frente al ERD

Base del esquema: sección `inventory_db` de `wiki/informe-avance2/er-corregido.md` (Figura 34.3). El equipo decidió ampliar ese ERD para que las funciones de conteos, devoluciones y movimientos sigan iguales y la API conserve su contrato. La migración `V3__align_with_erd.sql` deja el esquema ampliado y `ErdAlignmentMigrationIT` lo comprueba contra PostgreSQL.

## Ampliación frente al catálogo de `er-corregido.md`

Ese catálogo todavía no tiene esta ampliación y debe actualizarse.

- `stock_adjustments` no existe. En su lugar están `stock_counts` y `stock_count_lines`, como en V2, con la FK simple `stock_count_lines.count_id -> stock_counts(id)`.
- `balance_returns` agrega `note`, `requested_by`, `requested_at`, `decided_by`, `decided_at`, `rejection_reason`, `dispatched_by`, `dispatched_at`, `received_by`, `received_at`, `cancelled_by`, `cancelled_at` y `version`.
- `balance_return_lines` agrega `detail`, `rejection_reason` y `position`, y un material va una sola vez por devolución.
- `stock_movements` agrega `reference` y `balance_after`.

## Lo que cambia de forma, sin cambiar la función

- **Saldos de obra y almacén de origen (`worksite_supplies`)**: `GET /worksites/{id}/balance` y la validación del origen siguen iguales. El origen sale de `stock_movements`: la entrada en obra (`ENTRY`) y la salida del almacén (`DISPATCH` o `DEDUCTION`) comparten el despacho en `source_ref`. Lo que se pierde es `received_qty` y `last_received_at`, que ninguna API exponía.
- **Consumo parcial de reservas (`consumed_qty`)**: cada consumo parcial es una fila `CONSUMED` y la fila `RESERVED` guarda lo que queda. `GET /reservations` devuelve lo mismo que antes, sumando las filas por línea de pedido. Una línea ya no tiene una sola fila, así que `(organization_id, order_line_id)` pasa a ser único solo entre las filas `RESERVED`.
- **Asignaciones (`active`)**: `StaffUnassigned` borra la fila de `assignment_projection`.
- **Sobre de eventos**: como en iam, `outbox_events.payload` guarda el sobre completo con `aggregateType` y `actorId`. Lo que llega a Kafka no cambia.
- **`outbox_events.aggregate_id`**: el catálogo nombra `stock_items` y `balance_returns`, pero los eventos de la saga siguen usando el pedido, el despacho o la recepción, porque es la clave de partición que esperan los demás servicios, y `StockAdjusted` usa el conteo. La columna no tiene FK y es `uuid`, como pide el ERD.
- **`stock_movements.source_ref`**: el catálogo nombra `ordering_db.dispatches`; además guarda el pedido en `RESERVE` y `RELEASE`, la devolución en `RETURN_*` y el conteo en `ADJUSTMENT_*`. La columna no tiene FK. La API no la expone: `reference` sigue siendo el texto.
- **`stock_movements.seq`**: desaparece. El `id` de los movimientos nuevos es un UUID versión 7 creciente, que desempata los del mismo instante. Entre filas anteriores a V3 con la misma hora el orden no está garantizado.
- **`occurredAt` y `recordedBy`** conservan su nombre en la API y salen de `created_at` y `created_by`. **`registeredBy` y `registeredAt`** salen igual de `created_by` y `created_at`, y **`warehouseId`** de la devolución, de `destination_id`.
- **`rejectedQty`** no se guarda: es lo declarado menos lo aceptado, y la API lo sigue devolviendo.
- **`return_dispatch_id`** se llena al consumir `ReturnDispatchCreated` de ordering. Queda nulo en las devoluciones que todavía no iniciaron el retorno y en las que salieron antes de este cambio. El retorno y su stock siguen siendo de inventory: guardar el id no cambia la versión ni el estado.

## Aclaraciones de la API

- **`reference` de los ajustes**: el motivo de un ajuste admite 500 caracteres y `stock_movements.reference`, 200. El texto del movimiento se corta en 200; el motivo completo queda en `stock_counts.adjustment_reason` y en `StockAdjusted`.
- **`cancelledBy`** se agrega a la vista de la devolución. Es un campo nuevo: el contrato anterior solo traía `cancelledAt`.

## Datos en la migración V3

| Dato anterior | Qué pasa |
|---|---|
| `stock_counts` y `stock_count_lines` | Se quedan como están, en cualquier estado. Solo cambia la FK de las líneas, ahora de una columna |
| `material_returns` y sus líneas | Pasan a `balance_returns` y `balance_return_lines` con los mismos ids y todas sus columnas |
| `rejected_qty` | No se copia: V2 ya exigía que fuera lo declarado menos lo aceptado |
| `reference` y `balance_after` de movimientos | Se quedan. Si `reference` tenía un UUID en un despacho, descuento, reserva o liberación, también pasa a `source_ref` |
| `seq` | **Se pierde** |
| `worksite_supplies` | Cada fila enlaza la salida más reciente del almacén con una entrada libre de la obra. Es una aproximación, porque la tabla no guardaba el despacho. Lo que no se pueda enlazar queda en un `RAISE NOTICE` y esa obra responde `ORIGIN_WAREHOUSE_UNKNOWN` |
| `consumed_qty` | Se parte en filas, sin perder cantidades |
| Asignaciones inactivas, `address` | **Se pierden** |
| `processed_events` | Cada evento queda marcado para los dos consumidores, porque la tabla no decía cuál lo procesó |
