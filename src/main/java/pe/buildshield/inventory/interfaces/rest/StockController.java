package pe.buildshield.inventory.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.inventory.application.StockCommandService;
import pe.buildshield.inventory.application.StockLevel;
import pe.buildshield.inventory.application.StockQueryService;
import pe.buildshield.inventory.application.StockQueryService.MovementView;
import pe.buildshield.inventory.application.StockQueryService.ReservationView;
import pe.buildshield.inventory.application.StockQueryService.StockView;
import pe.buildshield.inventory.application.StockQueryService.WorksiteStockView;
import pe.buildshield.inventory.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock")
@Tag(name = "Stock", description = "Existencias por almacén y obra, reservas de los pedidos y movimientos")
@SecurityRequirement(name = "bearer")
class StockController {

    private final StockCommandService commands;
    private final StockQueryService queries;

    StockController(StockCommandService commands, StockQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping("/entries")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar una entrada de material (carga de existencias)",
            description = "El administrador o un encargado asignado al almacén. Suma a lo disponible y publica "
                    + "StockEntryRegistered.")
    @ApiResponse(responseCode = "201", description = "Entrada registrada; devuelve el saldo",
            content = @Content(schema = @Schema(implementation = StockLevelResource.class),
                    examples = @ExampleObject(value = StockApiExamples.STOCK_LEVEL)))
    @ApiResponse(responseCode = "400", description = "Cantidad inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Almacén o material inexistente, de otra organización o no asignado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Almacén desactivado o material retirado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    StockLevelResource registerEntry(@Valid @RequestBody StockEntryRequest request) {
        return StockLevelResource.of(commands.registerEntry(request.warehouseId(), request.materialId(),
                request.quantity(), request.note()));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar el stock de los almacenes",
            description = "Administrador: todos los almacenes; encargado de almacén: los suyos; encargado de obra: los activos.")
    @ApiResponse(responseCode = "200", description = "Saldos por almacén y material",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = StockView.class)),
                    examples = @ExampleObject(value = StockApiExamples.STOCK)))
    List<StockView> list(
            @Parameter(description = "Id del almacén; si se omite, todos los almacenes visibles",
                    example = StockApiExamples.WAREHOUSE_ID) @RequestParam(required = false) UUID warehouseId) {
        return queries.list(warehouseId);
    }

    @GetMapping("/worksites")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar el stock recibido en las obras",
            description = "Lo que la obra recibió (recepciones parciales y conformidades). Administrador y encargado "
                    + "de almacén: todas las obras; encargado de obra: las suyas.")
    @ApiResponse(responseCode = "200", description = "Saldos por obra y material",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = WorksiteStockView.class)),
                    examples = @ExampleObject(value = StockApiExamples.WORKSITE_STOCK)))
    List<WorksiteStockView> listWorksites(
            @Parameter(description = "Id de la obra; si se omite, todas las obras visibles",
                    example = StockApiExamples.WORKSITE_ID) @RequestParam(required = false) UUID worksiteId) {
        return queries.listWorksites(worksiteId);
    }

    @GetMapping("/reservations")
    @PreAuthorize("hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')")
    @Operation(summary = "Consultar las reservas de stock",
            description = "Reservas por línea de pedido aprobado (saga de reserva): lo reservado, lo consumido por los "
                    + "despachos y lo que queda. Administrador: todas; encargado de almacén: las de sus almacenes.")
    @ApiResponse(responseCode = "200", description = "Reservas, de la más reciente a la más antigua",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ReservationView.class)),
                    examples = @ExampleObject(value = StockApiExamples.RESERVATIONS)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    List<ReservationView> reservations(
            @Parameter(description = "Id del pedido; si se omite, todos", example = StockApiExamples.ORDER_ID)
            @RequestParam(required = false) UUID orderId,
            @Parameter(description = "Id del almacén; si se omite, todos los visibles", example = StockApiExamples.WAREHOUSE_ID)
            @RequestParam(required = false) UUID warehouseId) {
        return queries.reservations(orderId, warehouseId);
    }

    @GetMapping("/movements")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar los movimientos de stock",
            description = "Últimos " + StockQueryService.MOVEMENTS_LIMIT + " movimientos (entradas, reservas, "
                    + "liberaciones, salidas por despacho y recepciones en obra) de los almacenes y obras que el "
                    + "usuario ve, del más reciente al más antiguo.")
    @ApiResponse(responseCode = "200", description = "Movimientos",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = MovementView.class)),
                    examples = @ExampleObject(value = StockApiExamples.MOVEMENTS)))
    List<MovementView> movements(
            @Parameter(description = "Id del almacén o la obra; si se omite, todos los visibles",
                    example = StockApiExamples.WAREHOUSE_ID) @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Id del material; si se omite, todos", example = StockApiExamples.MATERIAL_ID)
            @RequestParam(required = false) UUID materialId) {
        return queries.movements(locationId, materialId);
    }

    @Schema(description = "Entrada de material a un almacén")
    record StockEntryRequest(
            @NotNull UUID warehouseId,
            @NotNull UUID materialId,
            @Schema(example = "100") @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @Schema(description = "Guía, proveedor u observación", example = "Guía 001-2345")
            @Size(max = 200) String note) {
    }

    @Schema(description = "Saldo del material en el almacén")
    record StockLevelResource(UUID warehouseId, UUID materialId, BigDecimal availableQty, BigDecimal reservedQty) {

        static StockLevelResource of(StockLevel level) {
            return new StockLevelResource(level.locationId(), level.materialId(), level.availableQty(),
                    level.reservedQty());
        }
    }
}
