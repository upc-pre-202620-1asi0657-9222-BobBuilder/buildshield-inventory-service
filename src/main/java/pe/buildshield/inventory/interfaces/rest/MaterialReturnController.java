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
import jakarta.validation.constraints.Positive;
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
import pe.buildshield.inventory.application.MaterialReturnCommandService;
import pe.buildshield.inventory.application.MaterialReturnQueryService;
import pe.buildshield.inventory.application.MaterialReturnQueryService.ReturnView;
import pe.buildshield.inventory.application.MaterialReturnQueryService.WorksiteBalanceView;
import pe.buildshield.inventory.domain.model.MaterialReturn;
import pe.buildshield.inventory.shared.error.ErrorResponse;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saldo restante de las obras y su devolución al almacén de origen (US43, T92). Lado de la obra: administrador y
 * encargado de obra; lado del almacén: administrador y encargado de almacén.
 */
@RestController
@RequestMapping("/api/v1/stock")
@Tag(name = "Devoluciones de saldos", description = "Saldo restante de la obra, solicitud de devolución, aprobación o "
        + "rechazo, retorno y verificación en el almacén de origen")
@SecurityRequirement(name = "bearer")
class MaterialReturnController {

    private static final String SITE_SIDE = "hasAnyRole('ADMINISTRATOR', 'SITE_MANAGER')";
    private static final String WAREHOUSE_SIDE = "hasAnyRole('ADMINISTRATOR', 'WAREHOUSE_MANAGER')";

    private final MaterialReturnCommandService commands;
    private final MaterialReturnQueryService queries;

    MaterialReturnController(MaterialReturnCommandService commands, MaterialReturnQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/worksites/{worksiteId}/balance")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar el saldo restante de una obra",
            description = "Por material: lo disponible en la obra, lo comprometido en devoluciones pendientes, lo que "
                    + "todavía se puede devolver y los almacenes que lo despacharon. Administrador y encargado de "
                    + "almacén: cualquier obra; encargado de obra: las suyas.")
    @ApiResponse(responseCode = "200", description = "Saldo de la obra",
            content = @Content(schema = @Schema(implementation = WorksiteBalanceView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.BALANCE)))
    @ApiResponse(responseCode = "404", description = "Obra inexistente, de otra organización o no asignada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    WorksiteBalanceView balance(@Parameter(description = "Id de la obra", example = StockApiExamples.WORKSITE_ID)
            @PathVariable UUID worksiteId) {
        return queries.balance(worksiteId);
    }

    @PostMapping("/returns")
    @PreAuthorize(SITE_SIDE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registrar el saldo a devolver",
            description = "US43 AC01: el encargado registra y detalla el material que quedó sin usar en la obra. Se "
                    + "devuelve al almacén que lo despachó; si se omite warehouseId y hay un solo almacén de origen, se "
                    + "usa ese. Lo declarado no puede superar lo que la obra tiene disponible menos lo comprometido en "
                    + "otras devoluciones pendientes. Queda REGISTERED hasta que se solicite.")
    @ApiResponse(responseCode = "201", description = "Saldo registrado (REGISTERED)",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "400", description = "Sin líneas, material repetido, cantidad inválida o varios "
            + "almacenes de origen posibles (ORIGIN_WAREHOUSE_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "Solo el administrador o el encargado de obra",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Obra, almacén o material inexistente, o obra no asignada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Saldo insuficiente (RETURN_EXCEEDS_BALANCE), almacén que no "
            + "despachó el material (NOT_SUPPLIED_BY_WAREHOUSE), origen desconocido o almacén desactivado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView register(@Valid @RequestBody RegisterReturnRequest request) {
        List<MaterialReturn.DeclaredLine> lines = request.lines().stream()
                .map(line -> new MaterialReturn.DeclaredLine(line.materialId(), line.quantity(), line.detail()))
                .toList();
        return queries.view(commands.register(request.worksiteId(), request.warehouseId(), lines, request.note()));
    }

    @GetMapping("/returns")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consultar las devoluciones de saldos",
            description = "De la más reciente a la más antigua. Administrador: todas; encargado de almacén: las "
                    + "dirigidas a sus almacenes; encargado de obra: las de sus obras.")
    @ApiResponse(responseCode = "200", description = "Devoluciones con sus líneas",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ReturnView.class)),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURNS)))
    List<ReturnView> list(
            @Parameter(description = "Estado: REGISTERED, REQUESTED, APPROVED, REJECTED, IN_TRANSIT, RECEIVED o "
                    + "CANCELLED", example = "REQUESTED") @RequestParam(required = false) MaterialReturn.Status status,
            @Parameter(description = "Id de la obra", example = StockApiExamples.WORKSITE_ID)
            @RequestParam(required = false) UUID worksiteId,
            @Parameter(description = "Id del almacén de origen", example = StockApiExamples.WAREHOUSE_ID)
            @RequestParam(required = false) UUID warehouseId) {
        return queries.list(status, worksiteId, warehouseId);
    }

    @GetMapping("/returns/{returnId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Ver una devolución", description = "Detalle con lo declarado y, desde la verificación, lo "
            + "aceptado y lo rechazado de cada material.")
    @ApiResponse(responseCode = "200", description = "Devolución",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "404", description = "Devolución inexistente o no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView get(@Parameter(description = "Id de la devolución", example = CountAndReturnApiExamples.RETURN_ID)
            @PathVariable UUID returnId) {
        return queries.get(returnId);
    }

    @PostMapping("/returns/{returnId}/request")
    @PreAuthorize(SITE_SIDE)
    @Operation(summary = "Solicitar la devolución",
            description = "US43 AC01: con el saldo registrado, la obra solicita devolverlo. Vuelve a comprobar el saldo y "
                    + "publica MaterialReturnRequested.")
    @ApiResponse(responseCode = "200", description = "Devolución solicitada (REQUESTED)",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "403", description = "No es el encargado de la obra",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Devolución inexistente o no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Estado inválido o saldo insuficiente",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView request(@Parameter(description = "Id de la devolución", example = CountAndReturnApiExamples.RETURN_ID)
            @PathVariable UUID returnId) {
        return queries.view(commands.request(returnId));
    }

    @PostMapping("/returns/{returnId}/approve")
    @PreAuthorize(WAREHOUSE_SIDE)
    @Operation(summary = "Aprobar la devolución",
            description = "El almacén de origen aprueba la solicitud. Publica MaterialReturnApproved.")
    @ApiResponse(responseCode = "200", description = "Devolución aprobada (APPROVED)",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "403", description = "No es el encargado del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Devolución inexistente o no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La devolución no está solicitada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView approve(@Parameter(description = "Id de la devolución", example = CountAndReturnApiExamples.RETURN_ID)
            @PathVariable UUID returnId) {
        return queries.view(commands.approve(returnId));
    }

    @PostMapping("/returns/{returnId}/reject")
    @PreAuthorize(WAREHOUSE_SIDE)
    @Operation(summary = "Rechazar la devolución",
            description = "US43 AC03: el almacén de origen rechaza la solicitud con su motivo; el saldo sigue en la "
                    + "obra. Publica MaterialReturnRejected.")
    @ApiResponse(responseCode = "200", description = "Devolución rechazada (REJECTED)",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "400", description = "Falta el motivo",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "No es el encargado del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Devolución inexistente o no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La devolución no está solicitada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView reject(@Parameter(description = "Id de la devolución", example = CountAndReturnApiExamples.RETURN_ID)
            @PathVariable UUID returnId, @Valid @RequestBody RejectReturnRequest request) {
        return queries.view(commands.reject(returnId, request.reason()));
    }

    @PostMapping("/returns/{returnId}/dispatch")
    @PreAuthorize(SITE_SIDE)
    @Operation(summary = "Iniciar el retorno al almacén",
            description = "US43 AC02: la obra inicia el retorno de la devolución aprobada; el material sale del stock de "
                    + "la obra (RETURN_OUT). Publica MaterialReturnDispatched.")
    @ApiResponse(responseCode = "200", description = "Devolución en retorno (IN_TRANSIT)",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "403", description = "No es el encargado de la obra",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Devolución inexistente o no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La devolución no está aprobada o la obra ya no tiene ese saldo",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView dispatch(@Parameter(description = "Id de la devolución", example = CountAndReturnApiExamples.RETURN_ID)
            @PathVariable UUID returnId) {
        return queries.view(commands.dispatch(returnId));
    }

    @PostMapping("/returns/{returnId}/receive")
    @PreAuthorize(WAREHOUSE_SIDE)
    @Operation(summary = "Verificar lo recibido en el almacén",
            description = "US43 AC02 y AC03: por cada material, lo aceptado (de cero a lo devuelto) y el motivo de lo "
                    + "que no se acepta. Solo lo aceptado vuelve al stock del almacén (RETURN_IN); lo rechazado queda "
                    + "registrado con su motivo. Publica MaterialReturnReceived.")
    @ApiResponse(responseCode = "200", description = "Devolución recibida (RECEIVED)",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "400", description = "Falta verificar un material, cantidad aceptada inválida o falta "
            + "el motivo de lo rechazado", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "No es el encargado del almacén de origen",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Devolución inexistente o no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La devolución no está en retorno",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView receive(@Parameter(description = "Id de la devolución", example = CountAndReturnApiExamples.RETURN_ID)
            @PathVariable UUID returnId, @Valid @RequestBody ReceiveReturnRequest request) {
        Map<UUID, MaterialReturn.Verification> verifications = new LinkedHashMap<>();
        request.lines().forEach(line -> verifications.put(line.materialId(),
                new MaterialReturn.Verification(line.acceptedQty(), line.rejectionReason())));
        return queries.view(commands.receive(returnId, verifications));
    }

    @PostMapping("/returns/{returnId}/cancel")
    @PreAuthorize(SITE_SIDE)
    @Operation(summary = "Anular la devolución",
            description = "La obra anula una devolución registrada o solicitada (todavía sin aprobar). Publica "
                    + "MaterialReturnCancelled.")
    @ApiResponse(responseCode = "200", description = "Devolución anulada (CANCELLED)",
            content = @Content(schema = @Schema(implementation = ReturnView.class),
                    examples = @ExampleObject(value = CountAndReturnApiExamples.RETURN)))
    @ApiResponse(responseCode = "403", description = "No es el encargado de la obra",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Devolución inexistente o no visible",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "La devolución ya se aprobó, rechazó o cerró",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    ReturnView cancel(@Parameter(description = "Id de la devolución", example = CountAndReturnApiExamples.RETURN_ID)
            @PathVariable UUID returnId) {
        return queries.view(commands.cancel(returnId));
    }

    @Schema(description = "Saldo de la obra a devolver")
    record RegisterReturnRequest(
            @NotNull UUID worksiteId,
            @Schema(description = "Almacén de origen; opcional si la obra recibió esos materiales de un solo almacén")
            UUID warehouseId,
            @NotEmpty List<@Valid ReturnLineRequest> lines,
            @Schema(description = "Observación general", example = "Saldo al cierre del vaciado del piso 12")
            @Size(max = 500) String note) {
    }

    @Schema(description = "Material del saldo")
    record ReturnLineRequest(
            @NotNull UUID materialId,
            @Schema(example = "12") @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity,
            @Schema(description = "Estado o detalle del material", example = "Bolsas cerradas, 2 con humedad")
            @Size(max = 200) String detail) {
    }

    @Schema(description = "Rechazo de la solicitud")
    record RejectReturnRequest(
            @Schema(description = "Motivo", example = "El material es de otra obra") @NotBlank @Size(max = 500)
            String reason) {
    }

    @Schema(description = "Verificación de lo recibido")
    record ReceiveReturnRequest(@NotEmpty List<@Valid VerifiedLineRequest> lines) {
    }

    @Schema(description = "Lo aceptado de un material y el motivo de lo que no se acepta")
    record VerifiedLineRequest(
            @NotNull UUID materialId,
            @Schema(example = "10") @NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) BigDecimal acceptedQty,
            @Schema(description = "Obligatorio si no se acepta todo", example = "Bolsas húmedas, no aptas para uso")
            @Size(max = 200) String rejectionReason) {
    }
}
