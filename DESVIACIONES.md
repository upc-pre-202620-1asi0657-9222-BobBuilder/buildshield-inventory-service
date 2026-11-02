# Desviaciones frente al ERD corregido

Fuente de verdad del esquema: sección `inventory_db` de `wiki/informe-avance2/er-corregido.md` (Figura 34.3). La migración `V3__align_with_erd.sql` deja las tablas, las columnas y las FK como en ese catálogo. La prueba `ErdAlignmentMigrationIT` lo comprueba contra PostgreSQL. Este archivo lista lo que cambió de función por no caber en el ERD. Ninguna tabla se agregó fuera del catálogo.

## Funciones que se pierden o cambian

### US41 (T91) Conteo de validación: cambia

El ERD solo tiene `stock_adjustments`, una fila por existencia ajustada, sin cabecera de conteo ni estado. Por eso:

| Antes | Ahora |
|---|---|
| Conteo guardado con estados `IN_PROGRESS`, `MATCHED`, `DIFFERENCES_FOUND`, `REVIEWED`, `ADJUSTED`, `CANCELLED` | No hay conteo guardado. `POST /api/v1/stock/counts/comparison` compara sin guardar nada y `POST /api/v1/stock/counts` registra y ajusta en un solo paso |
| Guardar el avance del conteo y corregirlo (`PUT /counts/{id}/lines`) | Se pierde. La web debe conservar el avance hasta enviarlo |
| Revisión obligatoria antes de ajustar, con nota, responsable y fecha (`POST /counts/{id}/review`, código `COUNT_NOT_REVIEWED`) | Se pierde. Queda como paso fuera del sistema: comparar, revisar y enviar el motivo. El criterio AC02 de «diferencia revisada» ya no lo garantiza el sistema; solo exige un motivo (`ADJUSTMENT_REASON_REQUIRED`) |
| Anular un conteo (`POST /counts/{id}/cancel`) | Se pierde: no hay nada abierto que anular |
| Un solo conteo abierto por almacén (`COUNT_ALREADY_OPEN`) | Se pierde: no hay conteos abiertos |
| No finalizar con materiales sin contar (`COUNT_INCOMPLETE`), y contar por defecto todo lo que tiene stock | Se pierde: se ajusta solo lo que llega en la petición |
| La diferencia se calculaba al finalizar y se aplicaba después | Se calcula y se aplica en la misma transacción, contra las existencias de ese momento |
| Nota del conteo, responsable de cada paso | Solo quedan `reason`, `created_by` y `created_at` por material |
| `GET /counts?status=` y `GET /counts/{id}` devolvían el conteo con sus líneas | Devuelven filas de ajuste por material, filtrables por almacén y material; sin filtro de estado |
| Evento `StockAdjusted` único por conteo, con la agregación `STOCK_COUNT` | Un evento por material con diferencia, con la agregación `STOCK_ITEM`. `countId` lleva el id del ajuste y `reviewNote` siempre va nulo |

Se conserva: la comparación contra disponible más reservado (AC01), el ajuste con motivo y sus movimientos `ADJUSTMENT_IN` y `ADJUSTMENT_OUT` (AC02), el rechazo de un faltante mayor que lo disponible y los permisos por rol.

### US43 (T92) Devolución de saldos: se conserva el flujo, se pierde detalle

El ciclo de estados, los endpoints y los eventos siguen iguales. El origen del material también (ver abajo). Lo que `balance_returns` y `balance_return_lines` no guardan:

- `note` de la devolución y `detail` de cada línea: la API ya no los guarda ni los devuelve. Si la web los envía, se ignoran.
- Quién y cuándo solicitó, decidió, despachó, recibió o anuló: las vistas solo traen `registeredBy` y `registeredAt`. Esos datos quedan en los eventos, que registra auditoría.
- `rejectionReason` de la devolución y de cada línea (AC03): se sigue exigiendo y viaja en `MaterialReturnRejected` y `MaterialReturnReceived`, pero inventory no lo devuelve al consultar. Quien lo necesite debe leerlo de auditoría.
- `rejectedQty` ya no se guarda: es lo declarado menos lo aceptado, y la API lo sigue devolviendo.
- `version`: el bloqueo optimista ahora es por estado (`UPDATE ... WHERE status = <leído>`); sigue respondiendo 409 `RETURN_MODIFIED`.
- `return_dispatch_id` existe en la tabla y queda nulo: el retorno sigue siendo un paso local de inventory, no crea un despacho en ordering.

### US40 (T90) Historial de movimientos: cambia

Los endpoints y los filtros siguen iguales.

- `reference` era un texto («Guía 001-2345», «Despacho MAN-...») y ahora es el identificador de `source_ref`: el despacho, el pedido, la devolución o el ajuste. **La guía u observación de una entrada no se guarda en el historial**: solo viaja en `StockEntryRegistered.note`.
- `balanceAfter` ya no se guarda: se calcula sumando los movimientos del ítem. Si los movimientos de un ítem no explican su saldo (por ejemplo, datos cargados a mano), el valor calculado difiere del real.
- `occurredAt` y `recordedBy` conservan su nombre en la API y salen de `created_at` y `created_by`.
- `seq` desaparece: el `id` de los movimientos nuevos es un UUID versión 7 creciente, que desempata los del mismo instante. Entre filas anteriores a V3 con la misma hora el orden no está garantizado.

## Lo que se conserva por otro camino

- **Saldos de obra y almacén de origen (`worksite_supplies`)**: `GET /worksites/{id}/balance` y la validación del origen siguen iguales. El origen sale de `stock_movements`: la entrada en obra (`ENTRY`) y la salida del almacén (`DISPATCH` o `DEDUCTION`) comparten el despacho en `source_ref`. Lo que se pierde es `received_qty` y `last_received_at`, que ninguna API exponía.
- **Consumo parcial de reservas (`consumed_qty`)**: cada consumo parcial es una fila `CONSUMED` y la fila `RESERVED` guarda lo que queda. `GET /reservations` devuelve lo mismo que antes, sumando las filas por línea de pedido. Una línea ya no tiene una sola fila, así que `(organization_id, order_line_id)` pasa a ser único solo entre las filas `RESERVED`.
- **Asignaciones (`active`)**: `StaffUnassigned` borra la fila de `assignment_projection`.
- **Sobre de eventos**: como en iam, `outbox_events.payload` guarda el sobre completo con `aggregateType` y `actorId`. Lo que llega a Kafka no cambia.
- **`outbox_events.aggregate_id`**: el catálogo nombra `stock_items` y `balance_returns`, pero los eventos de la saga siguen usando el pedido, el despacho o la recepción, porque es la clave de partición que esperan los demás servicios. La columna no tiene FK y es `uuid`, como pide el ERD.
- **`stock_movements.source_ref`**: el catálogo nombra `ordering_db.dispatches`; además guarda el pedido en `RESERVE` y `RELEASE`, la devolución en `RETURN_*` y el ajuste en `ADJUSTMENT_*`. La columna no tiene FK.

## Datos en la migración V3

| Dato anterior | Qué pasa |
|---|---|
| Conteos `MATCHED` y `ADJUSTED` | Una fila en `stock_adjustments` por material con existencia. El motivo se recorta a 200 caracteres |
| Conteos `IN_PROGRESS`, `DIFFERENCES_FOUND`, `REVIEWED`, `CANCELLED` | **Se pierden**: no habían cambiado el inventario |
| Notas de conteo y de revisión | **Se pierden** |
| `material_returns` y sus líneas | Pasan a `balance_returns` y `balance_return_lines` con los mismos ids. **Se pierden** notas, detalles, motivos y fechas intermedias |
| `reference` de movimientos | Si tenía un UUID (despacho o pedido), pasa a `source_ref`. El resto del texto **se pierde** |
| `balance_after`, `seq` | **Se pierden** (el saldo se calcula) |
| `worksite_supplies` | Cada fila enlaza la salida más reciente del almacén con una entrada libre de la obra. Es una aproximación, porque la tabla no guardaba el despacho. Lo que no se pueda enlazar queda en un `RAISE NOTICE` y esa obra responde `ORIGIN_WAREHOUSE_UNKNOWN` |
| `consumed_qty` | Se parte en filas, sin perder cantidades |
| Asignaciones inactivas, `address` | **Se pierden** |
| `processed_events` | Cada evento queda marcado para los dos consumidores, porque la tabla no decía cuál lo procesó |

## Pendiente fuera de este repositorio

`buildshield-web` (rama `feature/sprint-4-web`) todavía usa el contrato anterior:

- Las pantallas de conteo (`count-list-page`, `count-detail-page`) llaman a endpoints que ya no existen y deben pasar a comparar y registrar.
- Las pantallas de devolución muestran fechas, notas y motivos que ahora llegan vacíos; no se rompen, pero conviene quitarlos.
- El historial muestra `reference` como identificador.
