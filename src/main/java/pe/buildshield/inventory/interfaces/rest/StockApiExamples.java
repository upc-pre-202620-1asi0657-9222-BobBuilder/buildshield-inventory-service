package pe.buildshield.inventory.interfaces.rest;

/** Ejemplos de respuesta y de parámetros para la documentación OpenAPI de inventory. */
final class StockApiExamples {

    static final String WAREHOUSE_ID = "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e";
    static final String WORKSITE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
    static final String MATERIAL_ID = "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a";
    static final String ORDER_ID = "6f7a8b9c-0d1e-4f2a-9b3c-4d5e6f7a8b9c";

    static final String STOCK_LEVEL = """
            {
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
              "availableQty": 500.000,
              "reservedQty": 0.000
            }""";

    static final String STOCK = """
            [
              {
                "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
                "warehouseName": "Almacén Central",
                "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                "sku": "CEM-001",
                "materialName": "Cemento Portland tipo I",
                "unit": "BAG",
                "availableQty": 450.000,
                "reservedQty": 50.000
              }
            ]""";

    static final String WORKSITE_STOCK = """
            [
              {
                "worksiteId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                "worksiteName": "Torre Norte",
                "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                "sku": "CEM-001",
                "materialName": "Cemento Portland tipo I",
                "unit": "BAG",
                "availableQty": 29.500
              }
            ]""";

    static final String RESERVATIONS = """
            [
              {
                "id": "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d",
                "orderId": "6f7a8b9c-0d1e-4f2a-9b3c-4d5e6f7a8b9c",
                "orderLineId": "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d",
                "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
                "warehouseName": "Almacén Central",
                "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                "sku": "CEM-001",
                "materialName": "Cemento Portland tipo I",
                "quantity": 50.000,
                "consumedQty": 30.000,
                "remainingQty": 20.000,
                "status": "RESERVED",
                "reservedAt": "2026-11-02T16:20:01Z"
              }
            ]""";

    static final String MOVEMENTS = """
            [
              {
                "id": "0b1c2d3e-4f5a-4b6c-8d7e-9f0a1b2c3d4e",
                "locationId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
                "locationType": "WAREHOUSE",
                "locationName": "Almacén Central",
                "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
                "sku": "CEM-001",
                "type": "DISPATCH",
                "quantity": 30.000,
                "balanceAfter": 450.000,
                "reference": "Despacho MAN-20261103-7KQ2M9XA",
                "occurredAt": "2026-11-03T08:10:00Z",
                "recordedBy": "9c2d3e4f-5a6b-4c7d-8e9f-a0b1c2d3e4f5"
              }
            ]""";

    private StockApiExamples() {
    }
}
