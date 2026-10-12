package pe.buildshield.inventory.domain.model;

import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ErrorDetail;
import pe.buildshield.inventory.shared.error.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Conteo de validación de un almacén (US41, T91). Agregado con este ciclo de vida:
 *
 * <pre>
 * IN_PROGRESS --finalizar--> MATCHED (sin diferencias, cerrado)
 *             \-finalizar--> DIFFERENCES_FOUND --revisar--> REVIEWED --ajustar--> ADJUSTED
 * IN_PROGRESS, DIFFERENCES_FOUND, REVIEWED --anular--> CANCELLED
 * </pre>
 *
 * <p>Mientras está en curso se registra lo contado de cada material. Al finalizar se compara con lo que dice el
 * sistema en ese momento (disponible más reservado, porque lo reservado sigue físicamente en el almacén). Solo una
 * diferencia revisada se ajusta, y el ajuste exige un motivo. El ajuste aplica la diferencia calculada al
 * finalizar, no un saldo absoluto, para no pisar las salidas o entradas posteriores al conteo.
 */
public class StockCount {

    public enum Status { IN_PROGRESS, MATCHED, DIFFERENCES_FOUND, REVIEWED, ADJUSTED, CANCELLED }

    /** Estados en los que el conteo sigue abierto (un solo conteo abierto por almacén). */
    public static final Set<Status> OPEN = Set.of(Status.IN_PROGRESS, Status.DIFFERENCES_FOUND, Status.REVIEWED);

    public static final int NOTE_MAX = 200;
    public static final int REASON_MAX = 500;

    private final UUID id;
    private final UUID warehouseId;
    private final String note;
    private final UUID startedBy;
    private final Instant startedAt;
    private final List<StockCountLine> lines;
    private final long version;
    private Status status;
    private UUID finishedBy;
    private Instant finishedAt;
    private String reviewNote;
    private UUID reviewedBy;
    private Instant reviewedAt;
    private String adjustmentReason;
    private UUID adjustedBy;
    private Instant adjustedAt;
    private UUID cancelledBy;
    private Instant cancelledAt;

    /** Reconstrucción desde la base (o tras crearlo). */
    public StockCount(UUID id, UUID warehouseId, Status status, String note, UUID startedBy, Instant startedAt,
            List<StockCountLine> lines, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.status = Objects.requireNonNull(status, "status");
        this.note = note;
        this.startedBy = Objects.requireNonNull(startedBy, "startedBy");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.lines = new ArrayList<>(Objects.requireNonNull(lines, "lines"));
        this.version = version;
        if (this.lines.isEmpty()) {
            throw new IllegalStateException("Un conteo tiene al menos un material");
        }
    }

    /**
     * Inicia un conteo de los materiales indicados (sin repetir).
     *
     * @throws ValidationException {@code COUNT_WITHOUT_MATERIALS} si no hay materiales que contar
     */
    public static StockCount start(UUID warehouseId, Collection<UUID> materialIds, String note, UUID startedBy,
            Instant now) {
        Set<UUID> unique = new LinkedHashSet<>(materialIds);
        if (unique.isEmpty()) {
            throw new ValidationException("COUNT_WITHOUT_MATERIALS",
                    "El conteo necesita al menos un material: el almacén no tiene stock registrado y no se indicaron "
                            + "materiales");
        }
        List<StockCountLine> lines = new ArrayList<>();
        for (UUID materialId : unique) {
            lines.add(new StockCountLine(materialId, null, null));
        }
        return new StockCount(UUID.randomUUID(), warehouseId, Status.IN_PROGRESS, clean(note, NOTE_MAX, "note"),
                startedBy, now, lines, 0);
    }

    /**
     * Registra (o corrige) lo contado de los materiales indicados. Solo con el conteo en curso.
     *
     * @throws ValidationException {@code MATERIAL_NOT_IN_COUNT} si un material no es parte del conteo, o
     *                             {@code INVALID_COUNTED_QUANTITY} si la cantidad es negativa o tiene más de 3
     *                             decimales
     */
    public void registerCounts(Map<UUID, BigDecimal> counted) {
        requireStatus(Status.IN_PROGRESS, "registrar lo contado");
        for (Map.Entry<UUID, BigDecimal> entry : counted.entrySet()) {
            int index = indexOf(entry.getKey()).orElseThrow(() -> new ValidationException("MATERIAL_NOT_IN_COUNT",
                    "El material no es parte del conteo",
                    List.of(new ErrorDetail("materialId", String.valueOf(entry.getKey())))));
            lines.set(index, new StockCountLine(entry.getKey(), countedQuantity(entry.getValue()), null));
        }
    }

    /**
     * Finaliza el conteo y compara lo contado con lo que dice el sistema en este momento.
     *
     * @param systemQuantities disponible más reservado de cada material en el almacén (sin ítem: cero)
     * @throws ConflictException {@code COUNT_INCOMPLETE} si falta contar algún material
     */
    public void finish(Map<UUID, BigDecimal> systemQuantities, UUID by, Instant now) {
        requireStatus(Status.IN_PROGRESS, "finalizar");
        List<String> missing = lines.stream().filter(line -> line.countedQty() == null)
                .map(line -> line.materialId().toString()).toList();
        if (!missing.isEmpty()) {
            throw new ConflictException("COUNT_INCOMPLETE", "Falta registrar lo contado de " + missing.size()
                    + " material(es)", missing.stream().map(id -> new ErrorDetail("materialId", id)).toList());
        }
        boolean differences = false;
        for (int i = 0; i < lines.size(); i++) {
            StockCountLine line = lines.get(i);
            BigDecimal system = scale(systemQuantities.getOrDefault(line.materialId(), BigDecimal.ZERO));
            StockCountLine compared = new StockCountLine(line.materialId(), line.countedQty(), system);
            differences |= compared.hasDifference();
            lines.set(i, compared);
        }
        status = differences ? Status.DIFFERENCES_FOUND : Status.MATCHED;
        finishedBy = by;
        finishedAt = now;
    }

    /** Deja constancia de que las diferencias se revisaron (recuento, guías, mermas) antes de ajustar. */
    public void review(String note, UUID by, Instant now) {
        requireStatus(Status.DIFFERENCES_FOUND, "revisar");
        reviewNote = required(note, REASON_MAX, "note", "REVIEW_NOTE_REQUIRED",
                "Describe la revisión de las diferencias");
        status = Status.REVIEWED;
        reviewedBy = by;
        reviewedAt = now;
    }

    /**
     * Valida el ajuste de una diferencia revisada.
     *
     * @return las líneas con diferencia, que el servicio aplica al stock
     * @throws ConflictException {@code COUNT_NOT_REVIEWED} si las diferencias no se revisaron
     */
    public List<StockCountLine> adjust(String reason, UUID by, Instant now) {
        if (status == Status.DIFFERENCES_FOUND) {
            throw new ConflictException("COUNT_NOT_REVIEWED",
                    "Las diferencias del conteo deben revisarse antes de ajustar el inventario");
        }
        requireStatus(Status.REVIEWED, "ajustar");
        adjustmentReason = required(reason, REASON_MAX, "reason", "ADJUSTMENT_REASON_REQUIRED",
                "El ajuste necesita un motivo");
        adjustedBy = by;
        adjustedAt = now;
        status = Status.ADJUSTED;
        return differences();
    }

    /** Anula un conteo abierto sin tocar el inventario. */
    public void cancel(UUID by, Instant now) {
        if (!OPEN.contains(status)) {
            throw new ConflictException("COUNT_NOT_OPEN", "El conteo ya está cerrado (" + status + ")");
        }
        status = Status.CANCELLED;
        cancelledBy = by;
        cancelledAt = now;
    }

    /** Líneas cuyo conteo no coincide con el sistema (vacía antes de finalizar). */
    public List<StockCountLine> differences() {
        return lines.stream().filter(StockCountLine::hasDifference).toList();
    }

    /** Reconstrucción de los datos de cierre desde la base. */
    public void restore(UUID finishedBy, Instant finishedAt, String reviewNote, UUID reviewedBy, Instant reviewedAt,
            String adjustmentReason, UUID adjustedBy, Instant adjustedAt, UUID cancelledBy, Instant cancelledAt) {
        this.finishedBy = finishedBy;
        this.finishedAt = finishedAt;
        this.reviewNote = reviewNote;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.adjustmentReason = adjustmentReason;
        this.adjustedBy = adjustedBy;
        this.adjustedAt = adjustedAt;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = cancelledAt;
    }

    private void requireStatus(Status expected, String action) {
        if (status != expected) {
            throw new ConflictException("INVALID_COUNT_STATUS", "No se puede " + action + " un conteo en estado "
                    + status, List.of(new ErrorDetail("status", status.name())));
        }
    }

    private Optional<Integer> indexOf(UUID materialId) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).materialId().equals(materialId)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private static BigDecimal countedQuantity(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > Quantity.SCALE) {
            throw new ValidationException("INVALID_COUNTED_QUANTITY",
                    "Lo contado debe ser cero o mayor, con hasta 3 decimales",
                    List.of(new ErrorDetail("countedQty", "cero o mayor, máximo 3 decimales")));
        }
        return scale(value);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(Quantity.SCALE, RoundingMode.UNNECESSARY);
    }

    static String clean(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ValidationException("TEXT_TOO_LONG", "El texto supera los " + max + " caracteres",
                    List.of(new ErrorDetail(field, "máximo " + max + " caracteres")));
        }
        return trimmed;
    }

    static String required(String value, int max, String field, String code, String message) {
        String cleaned = clean(value, max, field);
        if (cleaned == null) {
            throw new ValidationException(code, message, List.of(new ErrorDetail(field, "obligatorio")));
        }
        return cleaned;
    }

    public UUID id() {
        return id;
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

    public UUID startedBy() {
        return startedBy;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public List<StockCountLine> lines() {
        return List.copyOf(lines);
    }

    public long version() {
        return version;
    }

    public UUID finishedBy() {
        return finishedBy;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String reviewNote() {
        return reviewNote;
    }

    public UUID reviewedBy() {
        return reviewedBy;
    }

    public Instant reviewedAt() {
        return reviewedAt;
    }

    public String adjustmentReason() {
        return adjustmentReason;
    }

    public UUID adjustedBy() {
        return adjustedBy;
    }

    public Instant adjustedAt() {
        return adjustedAt;
    }

    public UUID cancelledBy() {
        return cancelledBy;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }
}
