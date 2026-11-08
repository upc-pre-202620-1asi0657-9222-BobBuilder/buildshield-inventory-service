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
import org.springframework.format.annotation.DateTimeFormat;
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
import pe.buildshield.inventory.application.StockQueryService.MovementHistoryView;
import pe.buildshield.inventory.application.StockQueryService.MovementView;
import pe.buildshield.inventory.application.StockQueryService.ReservationView;
import pe.buildshield.inventory.application.StockQueryService.StockView;
import pe.buildshield.inventory.application.StockQueryService.WorksiteStockView;
import pe.buildshield.inventory.domain.model.EntryType;
import pe.buildshield.inventory.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock")
@Tag(name = "Stock", description = "Existencias por almacén y obra, inventario inicial y reposiciones, reservas "
        + "de los pedidos y movimientos")
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
    @Operation(summary = "Registrar el inventario inicial o una reposición",
            description = "US40: después de contar lo recibido, el administrador o un encargado asignado al almacén "
                    + "registra el inventario inicial (una sola vez por material y almacén) o una reposición. Suma a "
                    + "lo disponible, deja el movimiento INITIAL o REPLENISHMENT en el historial y publica "
                    + "StockEntryRegistered con entryType. Sin entryType: inicial si el material aún no tiene stock "
                    + "en el almacén; si no, reposición.")
    @ApiResponse(responseCode = "201", description = "Entrada registrada; devuelve el saldo",
            content = @Content(schema = @Schema(implementation = StockLevelResource.class),
                    examples = @ExampleObject(value = StockApiExamples.STOCK_LEVEL)))
    @ApiResponse(responseCode = "400", description = "Cantidad inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Almacén o material inexistente, de otra organización o no asignado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Almacén desactivado, material retirado o inventario inicial ya "
            + "registrado (INITIAL_STOCK_ALREADY_REGISTERED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    StockLevelResource registerEntry(@Valid @RequestBody StockEntryRequest request) {
        return StockLevelResource.of(commands.registerEntry(request.warehouseId(), request.materialId(),
                request.quantity(), request.note(), request.entryType()));
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

    @GetMapping("/movements/history")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar el historial de movimientos (paginado)",
            description = "US40: historial de los almacenes y obras que el usuario ve (inventario inicial, reposiciones, "
                    + "reservas, salidas por despacho, recepciones en obra, ajustes por conteo y devoluciones), del más "
                    + "reciente al más antiguo, con filtros opcionales.")
    @ApiResponse(responseCode = "200", description = "Página de movimientos",
            content = @Content(schema = @Schema(implementation = MovementHistoryView.class),
                    examples = @ExampleObject(value = StockApiExamples.MOVEMENT_HISTORY)))
    @ApiResponse(responseCode = "400", description = "Página, tipo o rango de fechas inválidos",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    MovementHistoryView movementHistory(
            @Parameter(description = "Id del almacén o la obra; si se omite, todos los visibles",
                    example = StockApiExamples.WAREHOUSE_ID) @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Id del material; si se omite, todos", example = StockApiExamples.MATERIAL_ID)
            @RequestParam(required = false) UUID materialId,
            @Parameter(description = "Tipo de movimiento: ENTRY, DEDUCTION, RESERVE, RELEASE, DISPATCH, INITIAL, "
                    + "REPLENISHMENT, ADJUSTMENT_IN, ADJUSTMENT_OUT, RETURN_OUT o RETURN_IN", example = "REPLENISHMENT")
            @RequestParam(required = false) String type,
            @Parameter(description = "Desde (incluido), ISO-8601", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Hasta (excluido), ISO-8601", example = "2026-12-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Página desde 0", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (de 1 a 200)", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        return queries.movementHistory(locationId, materialId, type, from, to, page, size);
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

    @Schema(description = "Entrada de material a un almacén (inventario inicial o reposición)")
    record StockEntryRequest(
            @NotNull UUID warehouseId,
            @NotNull UUID materialId,
            @Schema(description = "Cantidad contada al recibir", example = "100")
            @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @Schema(description = "Guía, proveedor u observación", example = "Guía 001-2345")
            @Size(max = 200) String note,
            @Schema(description = "INITIAL (inventario inicial) o REPLENISHMENT (reposición); opcional",
                    example = "REPLENISHMENT") EntryType entryType) {
    }

    @Schema(description = "Saldo del material en el almacén")
    record StockLevelResource(UUID warehouseId, UUID materialId, BigDecimal availableQty, BigDecimal reservedQty) {

        static StockLevelResource of(StockLevel level) {
            return new StockLevelResource(level.locationId(), level.materialId(), level.availableQty(),
                    level.reservedQty());
        }
    }
}
