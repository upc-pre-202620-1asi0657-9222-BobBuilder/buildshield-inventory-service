package pe.buildshield.inventory.interfaces.rest;

/** Ejemplos de respuesta y de parámetros de los conteos de validación y de las devoluciones de saldos. */
final class CountAndReturnApiExamples {

    static final String COUNT_ID = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b";
    static final String RETURN_ID = "8b9c0d1e-2f3a-4b4c-9d5e-6f7a8b9c0d1e";

    static final String COUNT = """
            {
              "id": "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b",
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "warehouseName": "Almacén Central",
              "status": "DIFFERENCES_FOUND",
              "note": "Conteo mensual de cemento",
              "startedBy": "9c2d3e4f-5a6b-4c7d-8e9f-a0b1c2d3e4f5",
              "startedAt": "2026-11-10T08:00:00Z",
              "finishedAt": "2026-11-10T09:30:00Z",
              "differenceCount": 1,
              "reviewNote": null,
              "reviewedBy": null,
              "reviewedAt": null,
              "adjustmentReason": null,
              "adjustedBy": null,
              "adjustedAt": null,
              "cancelledAt": null,
              "lines": [
                {
                  "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                  "sku": "CEM-001",
                  "materialName": "Cemento Portland tipo I",
                  "unit": "BAG",
                  "countedQty": 445.000,
                  "systemQty": 450.000,
                  "difference": -5.000
                }
              ]
            }""";

    static final String COUNTS = "[" + COUNT + "]";

    static final String RETURN = """
            {
              "id": "8b9c0d1e-2f3a-4b4c-9d5e-6f7a8b9c0d1e",
              "worksiteId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
              "worksiteName": "Torre Norte",
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "warehouseName": "Almacén Central",
              "status": "RECEIVED",
              "note": "Saldo al cierre del vaciado del piso 12",
              "hasRejections": true,
              "registeredBy": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
              "registeredAt": "2026-11-20T16:00:00Z",
              "requestedBy": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
              "requestedAt": "2026-11-20T16:05:00Z",
              "decidedBy": "9c2d3e4f-5a6b-4c7d-8e9f-a0b1c2d3e4f5",
              "decidedAt": "2026-11-21T08:00:00Z",
              "rejectionReason": null,
              "dispatchedBy": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
              "dispatchedAt": "2026-11-21T10:00:00Z",
              "receivedBy": "9c2d3e4f-5a6b-4c7d-8e9f-a0b1c2d3e4f5",
              "receivedAt": "2026-11-21T14:00:00Z",
              "cancelledAt": null,
              "lines": [
                {
                  "id": "a0b1c2d3-e4f5-4a6b-8c7d-8e9f0a1b2c3d",
                  "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                  "sku": "CEM-001",
                  "materialName": "Cemento Portland tipo I",
                  "unit": "BAG",
                  "quantity": 12.000,
                  "detail": "Bolsas cerradas, 2 con humedad",
                  "acceptedQty": 10.000,
                  "rejectedQty": 2.000,
                  "rejectionReason": "Bolsas húmedas, no aptas para uso"
                }
              ]
            }""";

    static final String RETURNS = "[" + RETURN + "]";

    static final String BALANCE = """
            {
              "worksiteId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
              "worksiteName": "Torre Norte",
              "lines": [
                {
                  "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                  "sku": "CEM-001",
                  "materialName": "Cemento Portland tipo I",
                  "unit": "BAG",
                  "availableQty": 29.500,
                  "pendingReturnQty": 12.000,
                  "returnableQty": 17.500,
                  "originWarehouses": [
                    {
                      "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
                      "warehouseName": "Almacén Central"
                    }
                  ]
                }
              ]
            }""";

    private CountAndReturnApiExamples() {
    }
}
