# buildshield-inventory-service

Inventario de BuildShield (BobBuilder): existencias por almacén y obra, inventario inicial y reposiciones,
reservas de stock de los pedidos aprobados, historial de movimientos, conteos de validación con ajuste, devolución
de saldos de obra al almacén de origen y participación en la saga de reserva. Contrato de la arquitectura:
`wiki/informe-tf/plan-microservicios.md` del proyecto; nombres exactos de los eventos en
`wiki/informe-tf/notas-etapa-b1.md`.

| Dato | Valor |
|---|---|
| Puerto | 8083 |
| Base | `inventory_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.inventory` (kernel compartido copiado de iam en `pe.buildshield.inventory.shared`) |
| Publica | `buildshield.inventory`: `StockReserved`, `StockReservationRejected`, `StockDeducted`, `StockReceived`, `StockReleased`, `StockEntryRegistered` (con `entryType`), `StockAdjusted`, `MaterialReturnRequested`, `MaterialReturnApproved`, `MaterialReturnRejected`, `MaterialReturnDispatched`, `MaterialReturnReceived`, `MaterialReturnCancelled` |
| Consume | `buildshield.organization`: `LocationRegistered/Updated`, `MaterialRegistered/Updated`, `StaffAssigned/Unassigned` (proyecciones); `buildshield.ordering`: `OrderApproved`, `DispatchDeparted`, `ReceptionPartiallyReceived`, `ReceptionConfirmed`, `OrderRejected`, `OrderClosed` (saga) |

## Endpoints

- `POST /api/v1/stock/entries`: inventario inicial o reposición (administrador o encargado del almacén). Campo
  opcional `entryType` (`INITIAL` una sola vez por material y almacén, o `REPLENISHMENT`); sin él, inicial si el
  material aún no tiene stock en el almacén. El movimiento queda como `INITIAL` o `REPLENISHMENT`.
- `GET /api/v1/stock?warehouseId=`: saldos por almacén (administrador: todos; encargado de almacén: los suyos;
  encargado de obra: los activos), misma forma que el Core.
- `GET /api/v1/stock/worksites?worksiteId=`: lo recibido en cada obra.
- `GET /api/v1/stock/reservations?orderId=&warehouseId=`: reservas por línea de pedido (administrador y encargado de almacén).
- `GET /api/v1/stock/movements?locationId=&materialId=`: últimos 200 movimientos de los lugares visibles.
- `GET /api/v1/stock/movements/history?locationId=&materialId=&type=&from=&to=&page=0&size=20`: historial paginado
  (`{items, page, size, total}`, tamaño de 1 a 200), del más reciente al más antiguo.

### Conteo de validación y ajuste (US41)

Administrador o encargado del almacén. Un solo conteo abierto por almacén.

| Método y ruta | Qué hace |
|---|---|
| `POST /api/v1/stock/counts` | inicia el conteo (`IN_PROGRESS`) de los materiales indicados o de todos los que tienen stock |
| `GET /api/v1/stock/counts?warehouseId=&status=` y `GET /api/v1/stock/counts/{id}` | consulta |
| `PUT /api/v1/stock/counts/{id}/lines` | registra o corrige lo contado |
| `POST /api/v1/stock/counts/{id}/finish` | compara con disponible más reservado: `MATCHED` o `DIFFERENCES_FOUND` |
| `POST /api/v1/stock/counts/{id}/review` | nota de la revisión de las diferencias: `REVIEWED` |
| `POST /api/v1/stock/counts/{id}/adjustment` | con motivo, aplica la diferencia (`ADJUSTMENT_IN` u `ADJUSTMENT_OUT`) y publica `StockAdjusted`: `ADJUSTED` |
| `POST /api/v1/stock/counts/{id}/cancel` | anula un conteo abierto: `CANCELLED` |

### Saldos de obra y devolución al almacén de origen (US43)

Estados: `REGISTERED` → `REQUESTED` → `APPROVED` o `REJECTED` → `IN_TRANSIT` → `RECEIVED` (y `CANCELLED` antes de
aprobar). El almacén de origen es el que despachó el material a la obra: se anota en `worksite_supplies` con el
`warehouseId` de `ReceptionPartiallyReceived` y `ReceptionConfirmed`.

| Método y ruta | Rol | Qué hace |
|---|---|---|
| `GET /api/v1/stock/worksites/{worksiteId}/balance` | todos (encargado de obra: sus obras) | saldo por material: disponible, comprometido, devolvible y almacenes de origen |
| `POST /api/v1/stock/returns` | administrador, encargado de obra | registra y detalla el saldo (no más de lo devolvible) |
| `GET /api/v1/stock/returns?status=&worksiteId=&warehouseId=` y `GET /api/v1/stock/returns/{id}` | todos (según visibilidad) | consulta |
| `POST /api/v1/stock/returns/{id}/request` | administrador, encargado de obra | solicita la devolución |
| `POST /api/v1/stock/returns/{id}/approve` | administrador, encargado de almacén | aprueba |
| `POST /api/v1/stock/returns/{id}/reject` | administrador, encargado de almacén | rechaza con motivo; el saldo queda en la obra |
| `POST /api/v1/stock/returns/{id}/dispatch` | administrador, encargado de obra | inicia el retorno: sale de la obra (`RETURN_OUT`) |
| `POST /api/v1/stock/returns/{id}/receive` | administrador, encargado de almacén | verifica: solo lo aceptado entra al almacén (`RETURN_IN`); lo rechazado queda con su motivo |
| `POST /api/v1/stock/returns/{id}/cancel` | administrador, encargado de obra | anula antes de aprobar |

Todas las rutas están bajo `/api/v1/stock/**`, que el gateway ya enruta a inventory.
- OpenAPI en `/api/v1/api-docs` y `/swagger-ui.html` (perfiles local y test).

## Saga de reserva (plan §6)

1. `OrderApproved`: reserva todo o nada en el almacén de origen, en una transacción local con bloqueo optimista
   (QAS02: UPDATE condicionado por versión y cantidad, hasta 3 reintentos). Responde `StockReserved` o
   `StockReservationRejected` con `missing[{materialId, requested, available}]` y un `reason` listo para mostrar.
   Si el stock cambia entre la revisión y la reserva, la transacción se revierte y Kafka reintenta el evento.
2. `DispatchDeparted`: consume la reserva de cada línea (reservado a salida) y publica `StockDeducted`.
3. `ReceptionPartiallyReceived` y `ReceptionConfirmed`: suma a la obra solo el `deltaQuantity` (`StockReceived`).
4. `OrderRejected` y `OrderClosed`: devuelve a disponible lo reservado sin consumir (`StockReleased`).

Cada evento se procesa una sola vez (`processed_events`) en la misma transacción que su efecto y su respuesta
(`outbox_events`, enviada cada 500 ms por `InventoryOutboxPublisher`). Ningún servicio se llama por REST: obras,
almacenes, materiales y asignaciones son copias locales (`*_projection`) alimentadas por eventos.


## Variables de entorno

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` (por defecto `localhost:9092`) y
`BUILDSHIELD_JWT_PUBLIC_KEY`. Perfiles `local`, `test` y `prod`.

## Ejecutar en local (clúster PostgreSQL 127.0.0.1:5433 con autenticación trust)

```bash
mvn -B package -DskipTests
BUILDSHIELD_JWT_PUBLIC_KEY="$(cat ~/.buildshield/jwt-public.pem)" \
java -jar target/buildshield-inventory-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

O todo junto desde `buildshield-platform`: `powershell -File scripts\run-all.ps1`.

## Pruebas

```bash
mvn -B verify -Dbuildshield.test.external-database=true
```

Las `*IT` usan la base temporal `inventory_it` en `127.0.0.1:5433` con el usuario `buildshield_core` y Kafka embebido
(`@EmbeddedKafka`). Sin la propiedad usan Testcontainers (requiere Docker). Incluyen la saga de punta a punta dentro
del servicio (`InventoryMessagingIT`), la concurrencia de reservas (QAS02), el aislamiento entre organizaciones, la
documentación OpenAPI completa y los escenarios Cucumber (`features/inventory`: saga, carga de existencias,
inventario inicial y reposición, conteo y ajuste, saldos y devolución). JaCoCo exige 80 % de líneas en `domain` y
`application`. Antes de cada escenario y de cada prueba de mensajería se espera a que los consumidores tengan sus
particiones asignadas (`KafkaConsumersReady`).

En Windows, Kafka embebido reserva segmentos de 1 GB en la carpeta temporal y no siempre los borra: si el disco del
sistema está justo, apunta `TMP` y `TEMP` a otra unidad antes de `mvn verify`.
