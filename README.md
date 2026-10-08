# buildshield-inventory-service

Inventario de BuildShield (BobBuilder): existencias por almacén y obra, reservas de stock de los pedidos
aprobados, movimientos y participación en la saga de reserva. Contrato de la arquitectura:
`wiki/informe-tf/plan-microservicios.md` del proyecto; nombres exactos de los eventos en
`wiki/informe-tf/notas-etapa-b1.md`.

| Dato | Valor |
|---|---|
| Puerto | 8083 |
| Base | `inventory_db` (PostgreSQL 16+) |
| Paquete | `pe.buildshield.inventory` (kernel compartido copiado de iam en `pe.buildshield.inventory.shared`) |
| Publica | `buildshield.inventory`: `StockReserved`, `StockReservationRejected`, `StockDeducted`, `StockReceived`, `StockReleased`, `StockEntryRegistered` |
| Consume | `buildshield.organization`: `LocationRegistered/Updated`, `MaterialRegistered/Updated`, `StaffAssigned/Unassigned` (proyecciones); `buildshield.ordering`: `OrderApproved`, `DispatchDeparted`, `ReceptionPartiallyReceived`, `ReceptionConfirmed`, `OrderRejected`, `OrderClosed` (saga) |

## Endpoints

- `POST /api/v1/stock/entries`: carga de existencias (administrador o encargado del almacén), misma forma que el Core.
- `GET /api/v1/stock?warehouseId=`: saldos por almacén (administrador: todos; encargado de almacén: los suyos;
  encargado de obra: los activos), misma forma que el Core.
- `GET /api/v1/stock/worksites?worksiteId=`: lo recibido en cada obra.
- `GET /api/v1/stock/reservations?orderId=&warehouseId=`: reservas por línea de pedido (administrador y encargado de almacén).
- `GET /api/v1/stock/movements?locationId=&materialId=`: últimos 200 movimientos de los lugares visibles.
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

Fuera de alcance de este paso: devolución de saldos (US43 de la tabla 3.3).

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
documentación OpenAPI completa y los escenarios Cucumber (`features/inventory`). JaCoCo exige 80 % de líneas en
`domain` y `application`.
