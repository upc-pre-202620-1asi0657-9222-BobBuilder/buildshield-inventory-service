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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.buildshield.inventory.application.StockCountCommandService;
import pe.buildshield.inventory.application.StockCountQueryService;
import pe.buildshield.inventory.application.StockCountQueryService.CountView;
import pe.buildshield.inventory.domain.model.StockCount;
import pe.buildshield.inventory.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conteo de validación y ajuste de inventario (US41, T91). Solo el administrador y el encargado de almacén (de sus
 * almacenes).
 */
@RestController
@RequestMapping("/api/v1/stock/counts")
@Tag(name = "Conteos de validación", description = "Reconteo de un almacén, comparación con las existencias y ajuste "
        + "de las diferencias revisadas")
@SecurityRequirement(name = "bearer")
class StockCountController {

    private static final String MANAGERS = "hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')";

    private final StockCountCommandService commands;
    private final StockCountQueryService queries;

    StockCountController(StockCountCommandService commands, StockCountQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    @PreAuthorize(MANAGERS)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Iniciar un conteo de validación",
            description = "Abre un reconteo del almacén para los materiales indicados (o, si no se indican, todos los "
                    + "que tienen stock registrado). Un solo conteo abierto por almacén.")
    @ApiResponse(responseCode = "201", description = "Conteo en curso (IN_PROGRESS)",
            content = @Content(schema = @Schema(implementation = CountView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT)))
    @ApiResponse(responseCode = "400", description = "Sin materiales que contar o nota demasiado larga",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Almacén o material inexistente, de otra organización o no asignado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Almacén desactivado (SITE_INACTIVE) o con un conteo abierto "
            + "(COUNT_ALREADY_OPEN)", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountView start(@Valid @RequestBody StartCountRequest request) {
        return queries.view(commands.start(request.warehouseId(), request.materialIds(), request.note()));
    }

    @GetMapping
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Consultar los conteos de validación",
            description = "Del más reciente al más antiguo. Administrador: todos los almacenes; encargado de almacén: "
                    + "los suyos.")
    @ApiResponse(responseCode = "200", description = "Conteos con sus líneas",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = CountView.class)),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNTS)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de almacén",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    List<CountView> list(
            @Parameter(description = "Id del almacén; si se omite, todos los visibles",
                    example = StockApiExamples.WAREHOUSE_ID) @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Estado: IN_PROGRESS, MATCHED, DIFFERENCES_FOUND, REVIEWED, ADJUSTED o CANCELLED",
                    example = "DIFFERENCES_FOUND") @RequestParam(required = false) StockCount.Status status) {
        return queries.list(warehouseId, status);
    }

    @GetMapping("/{countId}")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Ver un conteo de validación", description = "Detalle con lo contado, lo que decía el sistema "
            + "y la diferencia de cada material.")
    @ApiResponse(responseCode = "200", description = "Conteo",
            content = @Content(schema = @Schema(implementation = CountView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT)))
    @ApiResponse(responseCode = "404", description = "Conteo inexistente o de un almacén no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountView get(@Parameter(description = "Id del conteo", example = CountAndReturnApiExamples.COUNT_ID)
            @PathVariable UUID countId) {
        return queries.get(countId);
    }

    @PutMapping("/{countId}/lines")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Registrar lo contado",
            description = "Registra o corrige lo contado de uno o varios materiales mientras el conteo está en curso.")
    @ApiResponse(responseCode = "200", description = "Conteo con lo contado",
            content = @Content(schema = @Schema(implementation = CountView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT)))
    @ApiResponse(responseCode = "400", description = "Cantidad inválida o material que no es parte del conteo",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Conteo inexistente o de un almacén no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El conteo ya no está en curso",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountView registerCounts(@Parameter(description = "Id del conteo", example = CountAndReturnApiExamples.COUNT_ID)
            @PathVariable UUID countId, @Valid @RequestBody CountLinesRequest request) {
        Map<UUID, BigDecimal> counted = new LinkedHashMap<>();
        request.lines().forEach(line -> counted.put(line.materialId(), line.countedQty()));
        return queries.view(commands.registerCounts(countId, counted));
    }

    @PostMapping("/{countId}/finish")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Finalizar el conteo y comparar",
            description = "US41 AC01: el sistema compara lo contado con las existencias (disponible más reservado). "
                    + "Sin diferencias queda MATCHED (cerrado); con diferencias, DIFFERENCES_FOUND.")
    @ApiResponse(responseCode = "200", description = "Conteo comparado",
            content = @Content(schema = @Schema(implementation = CountView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT)))
    @ApiResponse(responseCode = "404", description = "Conteo inexistente o de un almacén no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Falta contar algún material (COUNT_INCOMPLETE) o el conteo ya no "
            + "está en curso", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountView finish(@Parameter(description = "Id del conteo", example = CountAndReturnApiExamples.COUNT_ID)
            @PathVariable UUID countId) {
        return queries.view(commands.finish(countId));
    }

    @PostMapping("/{countId}/review")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Revisar las diferencias",
            description = "Deja constancia de la revisión de las diferencias (recuento, guías, mermas). Es requisito "
                    + "para validar el ajuste.")
    @ApiResponse(responseCode = "200", description = "Conteo revisado (REVIEWED)",
            content = @Content(schema = @Schema(implementation = CountView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT)))
    @ApiResponse(responseCode = "400", description = "Falta la nota de la revisión",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Conteo inexistente o de un almacén no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El conteo no tiene diferencias por revisar",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountView review(@Parameter(description = "Id del conteo", example = CountAndReturnApiExamples.COUNT_ID)
            @PathVariable UUID countId, @Valid @RequestBody ReviewRequest request) {
        return queries.view(commands.review(countId, request.note()));
    }

    @PostMapping("/{countId}/adjustment")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Validar el ajuste del inventario",
            description = "US41 AC02: con la diferencia revisada, aplica a lo disponible la diferencia de cada material "
                    + "(movimientos ADJUSTMENT_IN y ADJUSTMENT_OUT) con el motivo, y publica StockAdjusted.")
    @ApiResponse(responseCode = "200", description = "Conteo ajustado (ADJUSTED)",
            content = @Content(schema = @Schema(implementation = CountView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT)))
    @ApiResponse(responseCode = "400", description = "Falta el motivo del ajuste",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Conteo inexistente o de un almacén no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Diferencias sin revisar (COUNT_NOT_REVIEWED), conteo en otro "
            + "estado o faltante mayor que lo disponible (INSUFFICIENT_STOCK)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountView adjust(@Parameter(description = "Id del conteo", example = CountAndReturnApiExamples.COUNT_ID)
            @PathVariable UUID countId, @Valid @RequestBody AdjustmentRequest request) {
        return queries.view(commands.adjust(countId, request.reason()));
    }

    @PostMapping("/{countId}/cancel")
    @PreAuthorize(MANAGERS)
    @Operation(summary = "Anular un conteo abierto", description = "Cierra el conteo sin tocar el inventario.")
    @ApiResponse(responseCode = "200", description = "Conteo anulado (CANCELLED)",
            content = @Content(schema = @Schema(implementation = CountView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.COUNT)))
    @ApiResponse(responseCode = "404", description = "Conteo inexistente o de un almacén no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "El conteo ya está cerrado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    CountView cancel(@Parameter(description = "Id del conteo", example = CountAndReturnApiExamples.COUNT_ID)
            @PathVariable UUID countId) {
        return queries.view(commands.cancel(countId));
    }

    @Schema(description = "Inicio de un conteo de validación")
    record StartCountRequest(
            @NotNull UUID warehouseId,
            @Schema(description = "Materiales a contar; si se omite, todos los que tienen stock en el almacén")
            List<@NotNull UUID> materialIds,
            @Schema(description = "Motivo u observación del conteo", example = "Conteo mensual de cemento")
            @Size(max = 200) String note) {
    }

    @Schema(description = "Lo contado de uno o varios materiales")
    record CountLinesRequest(@NotEmpty List<@Valid CountedLine> lines) {
    }

    @Schema(description = "Lo contado de un material")
    record CountedLine(
            @NotNull UUID materialId,
            @Schema(example = "445") @NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) BigDecimal countedQty) {
    }

    @Schema(description = "Revisión de las diferencias")
    record ReviewRequest(
            @Schema(description = "Qué se revisó", example = "Se recontó dos veces; faltan 5 bolsas por rotura")
            @NotBlank @Size(max = 500) String note) {
    }

    @Schema(description = "Validación del ajuste")
    record AdjustmentRequest(
            @Schema(description = "Motivo del ajuste", example = "Rotura de bolsas en el almacén")
            @NotBlank @Size(max = 500) String reason) {
    }
}
