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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.inventory.application.StockAdjustmentCommandService;
import pe.buildshield.inventory.application.StockAdjustmentCommandService.CountedLine;
import pe.buildshield.inventory.application.StockAdjustmentQueryService;
import pe.buildshield.inventory.application.StockAdjustmentQueryService.AdjustmentView;
import pe.buildshield.inventory.application.StockAdjustmentQueryService.CountResultView;
import pe.buildshield.inventory.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Conteo físico y ajuste de inventario (US41, T91), sobre {@code stock_adjustments}. Solo el administrador y el
 * encargado de almacén (de sus almacenes). El ERD no guarda un conteo abierto: comparar no deja rastro y aplicar es un
 * solo paso (ver DESVIACIONES.md).
 */
@RestController
@RequestMapping("/api/v1/stock/counts")
@Tag(name = "Conteos físicos", description = "Comparación de lo contado con las existencias y ajuste de las diferencias")
@SecurityRequirement(name = "bearer")
class StockAdjustmentController {

    private static final String MANAGERS = "hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')";

    private final StockAdjustmentCommandService commands;
    private final StockAdjustmentQueryService queries;

    StockAdjustmentController(StockAdjustmentCommandService commands, StockAdjustmentQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping("/comparison")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Comparar lo contado con las existencias",
            description = "US41 AC01: compara lo contado con disponible más reservado, sin guardar nada ni tocar el "
                    + "inventario. Sin diferencias responde MATCHED; con diferencias, DIFFERENCES_FOUND.")
    @ApiResponse(responseCode = "200", description = "Resultado de la comparación",
            content = @Content(schema = @Schema(implementation = CountResultView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT_COMPARED)))
    @ApiResponse(responseCode = "400", description = "Sin materiales, material repetido o cantidad inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Almacén o material inexistente, de otra organización o no asignado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Almacén desactivado (SITE_INACTIVE)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountResultView compare(@Valid @RequestBody CompareRequest request) {
        return queries.view(commands.compare(request.warehouseId(), lines(request.lines())));
    }

    @PostMapping
    @PreAuthorize(MANAGERS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar un conteo y ajustar el inventario",
            description = "US41 AC02: guarda un ajuste por material contado y aplica a lo disponible cada diferencia "
                    + "(movimientos ADJUSTMENT_IN y ADJUSTMENT_OUT), con su evento StockAdjusted. Si hay diferencias "
                    + "el motivo es obligatorio. Responde ADJUSTED, o MATCHED si no había diferencias.")
    @ApiResponse(responseCode = "201", description = "Conteo registrado",
            content = @Content(schema = @Schema(implementation = CountResultView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT_APPLIED)))
    @ApiResponse(responseCode = "400", description = "Falta el motivo (ADJUSTMENT_REASON_REQUIRED), sin materiales, "
            + "material repetido o cantidad inválida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Almacén o material inexistente, de otra organización o no asignado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Almacén desactivado (SITE_INACTIVE) o faltante mayor que lo "
            + "disponible (INSUFFICIENT_STOCK)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountResultView apply(@Valid @RequestBody ApplyRequest request) {
        return queries.view(commands.apply(request.warehouseId(), lines(request.lines()), request.reason()));
    }

    @GetMapping
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Consultar los ajustes por conteo",
            description = "Una fila por material contado, de la más reciente a la más antigua. Administrador: todos los "
                    + "almacenes; encargado de almacén: los suyos.")
    @ApiResponse(responseCode = "200", description = "Ajustes",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AdjustmentView.class)),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.ADJUSTMENTS)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    List<AdjustmentView> list(
            @Parameter(description = "Id del almacén; si se omite, todos los visibles",
                    example = StockApiExamples.WAREHOUSE_ID) @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Id del material; si se omite, todos", example = StockApiExamples.MATERIAL_ID)
            @RequestParam(required = false) UUID materialId) {
        return queries.list(warehouseId, materialId);
    }

    @GetMapping("/{adjustmentId}")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Ver un ajuste por conteo", description = "Lo contado, lo que decía el sistema, la diferencia "
            + "y el motivo de un material.")
    @ApiResponse(responseCode = "200", description = "Ajuste",
            content = @Content(schema = @Schema(implementation = AdjustmentView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.ADJUSTMENT)))
    @ApiResponse(responseCode = "404", description = "Ajuste inexistente o de un almacén no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    AdjustmentView get(@Parameter(description = "Id del ajuste", example = CountAndReturnApiExamples.ADJUSTMENT_ID)
            @PathVariable UUID adjustmentId) {
        return queries.get(adjustmentId);
    }

    private static List<CountedLine> lines(List<CountedLineRequest> lines) {
        return lines.stream().map(line -> new CountedLine(line.materialId(), line.countedQty())).toList();
    }

    @Schema(description = "Lo contado en un almacén, para comparar")
    record CompareRequest(@NotNull UUID warehouseId, @NotEmpty List<@Valid CountedLineRequest> lines) {
    }

    @Schema(description = "Lo contado en un almacén, para registrar y ajustar")
    record ApplyRequest(
            @NotNull UUID warehouseId,
            @NotEmpty List<@Valid CountedLineRequest> lines,
            @Schema(description = "Motivo del ajuste; obligatorio si hay diferencias",
                    example = "Rotura de bolsas en el almacén") @Size(max = 200) String reason) {
    }

    @Schema(description = "Lo contado de un material")
    record CountedLineRequest(
            @NotNull UUID materialId,
            @Schema(example = "445") @NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) BigDecimal countedQty) {
    }
}
