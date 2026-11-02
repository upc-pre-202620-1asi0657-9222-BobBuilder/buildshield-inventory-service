package pe.buildshield.inventory.interfaces.rest;

/** Ejemplos de respuesta y de parámetros de los conteos físicos y de las devoluciones de saldos. */
final class CountAndReturnApiExamples {

    static final String ADJUSTMENT_ID = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b";
    static final String RETURN_ID = "8b9c0d1e-2f3a-4b4c-9d5e-6f7a8b9c0d1e";

    static final String COUNT_COMPARED = """
            {
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "warehouseName": "Almacén Central",
              "status": "DIFFERENCES_FOUND",
              "reason": null,
              "differenceCount": 1,
              "lines": [
                {
                  "adjustmentId": null,
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

    static final String COUNT_APPLIED = """
            {
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "warehouseName": "Almacén Central",
              "status": "ADJUSTED",
              "reason": "Rotura de bolsas en el almacén",
              "differenceCount": 1,
              "lines": [
                {
                  "adjustmentId": "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b",
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

    static final String ADJUSTMENT = """
            {
              "id": "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b",
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "warehouseName": "Almacén Central",
              "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
              "sku": "CEM-001",
              "materialName": "Cemento Portland tipo I",
              "unit": "BAG",
              "countedQty": 445.000,
              "systemQty": 450.000,
              "difference": -5.000,
              "reason": "Rotura de bolsas en el almacén",
              "createdBy": "9c2d3e4f-5a6b-4c7d-8e9f-a0b1c2d3e4f5",
              "createdAt": "2026-11-10T09:30:00Z"
            }""";

    static final String ADJUSTMENTS = "[" + ADJUSTMENT + "]";

    static final String RETURN = """
            {
              "id": "8b9c0d1e-2f3a-4b4c-9d5e-6f7a8b9c0d1e",
              "worksiteId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
              "worksiteName": "Torre Norte",
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "warehouseName": "Almacén Central",
              "status": "RECEIVED",
              "hasRejections": true,
              "registeredBy": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
              "registeredAt": "2026-11-20T16:00:00Z",
              "lines": [
                {
                  "id": "a0b1c2d3-e4f5-4a6b-8c7d-8e9f0a1b2c3d",
                  "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                  "sku": "CEM-001",
                  "materialName": "Cemento Portland tipo I",
                  "unit": "BAG",
                  "quantity": 12.000,
                  "acceptedQty": 10.000,
                  "rejectedQty": 2.000
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
