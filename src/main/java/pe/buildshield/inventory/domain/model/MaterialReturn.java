package pe.buildshield.inventory.domain.model;

import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ErrorDetail;
import pe.buildshield.inventory.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Devolución del saldo de una obra a su almacén de origen (US43, T92). Agregado con este ciclo de vida:
 *
 * <pre>
 * REGISTERED --solicitar--> REQUESTED --aprobar--> APPROVED --iniciar retorno--> IN_TRANSIT --verificar--> RECEIVED
 *                                     \--rechazar--> REJECTED
 * REGISTERED, REQUESTED --anular--> CANCELLED
 * </pre>
 *
 * <ul>
 *   <li>El encargado de obra registra y detalla el saldo (lo que quedó sin usar) y después solicita la
 *       devolución.</li>
 *   <li>El almacén de origen la aprueba o la rechaza con un motivo; el rechazo deja el saldo en la obra.</li>
 *   <li>Al iniciar el retorno el material sale de la obra; al verificarlo, el almacén reincorpora solo lo aceptado
 *       y registra lo rechazado de cada línea con su motivo.</li>
 * </ul>
 */
public class MaterialReturn {

    public enum Status { REGISTERED, REQUESTED, APPROVED, REJECTED, IN_TRANSIT, RECEIVED, CANCELLED }

    /** Estados en los que el saldo declarado sigue comprometido en la obra (no se puede volver a devolver). */
    public static final Set<Status> PENDING = Set.of(Status.REGISTERED, Status.REQUESTED, Status.APPROVED);

    public static final int NOTE_MAX = 500;
    public static final int DETAIL_MAX = 200;

    private final UUID id;
    private final UUID worksiteId;
    private final UUID warehouseId;
    private final String note;
    private final UUID registeredBy;
    private final Instant registeredAt;
    private final List<MaterialReturnLine> lines;
    private final long version;
    private Status status;
    private UUID requestedBy;
    private Instant requestedAt;
    private UUID decidedBy;
    private Instant decidedAt;
    private String rejectionReason;
    private UUID dispatchedBy;
    private Instant dispatchedAt;
    private UUID receivedBy;
    private Instant receivedAt;
    private UUID cancelledBy;
    private Instant cancelledAt;

    /** Lo que declara la obra por material al registrar el saldo. */
    public record DeclaredLine(UUID materialId, BigDecimal quantity, String detail) {
    }

    /** Lo que el almacén acepta de un material al verificar; {@code reason} es obligatorio si no acepta todo. */
    public record Verification(BigDecimal acceptedQty, String reason) {
    }

    /** Reconstrucción desde la base (o tras crearla). */
    public MaterialReturn(UUID id, UUID worksiteId, UUID warehouseId, Status status, String note, UUID registeredBy,
            Instant registeredAt, List<MaterialReturnLine> lines, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.worksiteId = Objects.requireNonNull(worksiteId, "worksiteId");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.status = Objects.requireNonNull(status, "status");
        this.note = note;
        this.registeredBy = Objects.requireNonNull(registeredBy, "registeredBy");
        this.registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
        this.lines = new ArrayList<>(Objects.requireNonNull(lines, "lines"));
        this.version = version;
        if (this.lines.isEmpty()) {
            throw new IllegalStateException("Una devolución tiene al menos un material");
        }
    }

    /**
     * Registra el saldo que la obra devolverá al almacén de origen.
     *
     * @throws ValidationException {@code RETURN_WITHOUT_LINES}, {@code DUPLICATED_MATERIAL} o una cantidad inválida
     */
    public static MaterialReturn register(UUID worksiteId, UUID warehouseId, List<DeclaredLine> declared, String note,
            UUID by, Instant now) {
        if (declared == null || declared.isEmpty()) {
            throw new ValidationException("RETURN_WITHOUT_LINES", "Detalla al menos un material del saldo");
        }
        Set<UUID> seen = new HashSet<>();
        List<MaterialReturnLine> lines = new ArrayList<>();
        for (DeclaredLine line : declared) {
            Objects.requireNonNull(line.materialId(), "materialId");
            if (!seen.add(line.materialId())) {
                throw new ValidationException("DUPLICATED_MATERIAL", "Cada material va una sola vez en el saldo",
                        List.of(new ErrorDetail("materialId", line.materialId().toString())));
            }
            lines.add(new MaterialReturnLine(UUID.randomUUID(), line.materialId(), new Quantity(line.quantity()).value(),
                    StockCount.clean(line.detail(), DETAIL_MAX, "detail"), null, null, null));
        }
        return new MaterialReturn(UUID.randomUUID(), worksiteId, warehouseId, Status.REGISTERED,
                StockCount.clean(note, NOTE_MAX, "note"), by, now, lines, 0);
    }

    /** La obra solicita la devolución del saldo registrado. */
    public void request(UUID by, Instant now) {
        requireStatus(Status.REGISTERED, "solicitar");
        status = Status.REQUESTED;
        requestedBy = by;
        requestedAt = now;
    }

    /** El almacén de origen aprueba la devolución solicitada. */
    public void approve(UUID by, Instant now) {
        requireStatus(Status.REQUESTED, "aprobar");
        status = Status.APPROVED;
        decidedBy = by;
        decidedAt = now;
    }

    /** El almacén de origen rechaza la devolución solicitada; el saldo queda en la obra. */
    public void reject(String reason, UUID by, Instant now) {
        requireStatus(Status.REQUESTED, "rechazar");
        rejectionReason = StockCount.required(reason, NOTE_MAX, "reason", "REJECTION_REASON_REQUIRED",
                "El rechazo necesita un motivo");
        status = Status.REJECTED;
        decidedBy = by;
        decidedAt = now;
    }

    /** La obra inicia el retorno de una devolución aprobada: el material sale de la obra. */
    public void dispatch(UUID by, Instant now) {
        requireStatus(Status.APPROVED, "iniciar el retorno de");
        status = Status.IN_TRANSIT;
        dispatchedBy = by;
        dispatchedAt = now;
    }

    /**
     * El almacén verifica lo recibido: por cada material, lo aceptado (de cero a lo devuelto) y el motivo de lo
     * que no acepta.
     *
     * @return las líneas verificadas
     * @throws ValidationException {@code RETURN_VERIFICATION_INCOMPLETE} si falta verificar un material,
     *                             {@code MATERIAL_NOT_IN_RETURN}, {@code INVALID_ACCEPTED_QUANTITY} o
     *                             {@code REJECTION_REASON_REQUIRED}
     */
    public List<MaterialReturnLine> receive(Map<UUID, Verification> verifications, UUID by, Instant now) {
        requireStatus(Status.IN_TRANSIT, "verificar");
        for (UUID materialId : verifications.keySet()) {
            if (lines.stream().noneMatch(line -> line.materialId().equals(materialId))) {
                throw new ValidationException("MATERIAL_NOT_IN_RETURN", "El material no es parte de la devolución",
                        List.of(new ErrorDetail("materialId", String.valueOf(materialId))));
            }
        }
        List<MaterialReturnLine> verified = new ArrayList<>();
        for (MaterialReturnLine line : lines) {
            Verification verification = verifications.get(line.materialId());
            if (verification == null) {
                throw new ValidationException("RETURN_VERIFICATION_INCOMPLETE",
                        "Verifica todos los materiales de la devolución",
                        List.of(new ErrorDetail("materialId", line.materialId().toString())));
            }
            BigDecimal accepted = accepted(verification.acceptedQty(), line);
            BigDecimal rejected = line.quantity().subtract(accepted);
            String reason = rejected.signum() > 0
                    ? StockCount.required(verification.reason(), DETAIL_MAX, "reason", "REJECTION_REASON_REQUIRED",
                    "Indica el motivo de lo que no se acepta")
                    : null;
            verified.add(new MaterialReturnLine(line.id(), line.materialId(), line.quantity(), line.detail(), accepted,
                    rejected, reason));
        }
        lines.clear();
        lines.addAll(verified);
        status = Status.RECEIVED;
        receivedBy = by;
        receivedAt = now;
        return List.copyOf(verified);
    }

    /** La obra anula una devolución que todavía no se aprobó. */
    public void cancel(UUID by, Instant now) {
        if (status != Status.REGISTERED && status != Status.REQUESTED) {
            throw invalidStatus("anular");
        }
        status = Status.CANCELLED;
        cancelledBy = by;
        cancelledAt = now;
    }

    /** ¿Se rechazó la devolución o parte del material verificado? */
    public boolean hasRejections() {
        return status == Status.REJECTED || lines.stream().anyMatch(MaterialReturnLine::hasRejection);
    }

    /** Reconstrucción de las decisiones y fechas desde la base. */
    public void restore(UUID requestedBy, Instant requestedAt, UUID decidedBy, Instant decidedAt,
            String rejectionReason, UUID dispatchedBy, Instant dispatchedAt, UUID receivedBy, Instant receivedAt,
            UUID cancelledBy, Instant cancelledAt) {
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.rejectionReason = rejectionReason;
        this.dispatchedBy = dispatchedBy;
        this.dispatchedAt = dispatchedAt;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = cancelledAt;
    }

    private static BigDecimal accepted(BigDecimal value, MaterialReturnLine line) {
        if (value == null || value.signum() < 0 || value.compareTo(line.quantity()) > 0
                || value.stripTrailingZeros().scale() > Quantity.SCALE) {
            throw new ValidationException("INVALID_ACCEPTED_QUANTITY",
                    "Lo aceptado va de cero a lo devuelto, con hasta 3 decimales",
                    List.of(new ErrorDetail("acceptedQty", "de 0 a " + line.quantity().toPlainString())));
        }
        return value.setScale(Quantity.SCALE, RoundingMode.UNNECESSARY);
    }

    private void requireStatus(Status expected, String action) {
        if (status != expected) {
            throw invalidStatus(action);
        }
    }

    private ConflictException invalidStatus(String action) {
        return new ConflictException("INVALID_RETURN_STATUS", "No se puede " + action + " una devolución en estado "
                + status, List.of(new ErrorDetail("status", status.name())));
    }

    public UUID id() {
        return id;
    }

    public UUID worksiteId() {
        return worksiteId;
    }

    public UUID warehouseId() {
        return warehouseId;
    }

    public Status status() {
        return status;
    }

    public String note() {
        return note;
    }

    public UUID registeredBy() {
        return registeredBy;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public List<MaterialReturnLine> lines() {
        return List.copyOf(lines);
    }

    public long version() {
        return version;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public UUID decidedBy() {
        return decidedBy;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public String rejectionReason() {
        return rejectionReason;
    }

    public UUID dispatchedBy() {
        return dispatchedBy;
    }

    public Instant dispatchedAt() {
        return dispatchedAt;
    }

    public UUID receivedBy() {
        return receivedBy;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public UUID cancelledBy() {
        return cancelledBy;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }
}
