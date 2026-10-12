package pe.buildshield.inventory.domain.model;

import org.junit.jupiter.api.Test;
import pe.buildshield.inventory.domain.model.MaterialReturn.DeclaredLine;
import pe.buildshield.inventory.domain.model.MaterialReturn.Verification;
import pe.buildshield.inventory.shared.error.ConflictException;
import pe.buildshield.inventory.shared.error.ValidationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo de vida de la devolución de saldos (US43): solicitud, aprobación o rechazo, retorno y verificación. */
class MaterialReturnTest {

    private static final Instant NOW = Instant.parse("2026-11-20T16:00:00Z");
    private static final UUID WORKSITE = UUID.randomUUID();
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID JUAN = UUID.randomUUID();
    private static final UUID ROSA = UUID.randomUUID();
    private static final UUID CEMENT = UUID.randomUUID();
    private static final UUID STEEL = UUID.randomUUID();

    private MaterialReturn registered() {
        return MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(
                new DeclaredLine(CEMENT, new BigDecimal("12"), "  2 con humedad "),
                new DeclaredLine(STEEL, new BigDecimal("3.5"), null)), " Saldo del piso 12 ", JUAN, NOW);
    }

    private MaterialReturn inTransit() {
        MaterialReturn materialReturn = registered();
        materialReturn.request(JUAN, NOW);
        materialReturn.approve(ROSA, NOW);
        materialReturn.dispatch(JUAN, NOW);
        return materialReturn;
    }

    @Test
    void the_site_manager_registers_and_details_the_balance() {
        MaterialReturn materialReturn = registered();

        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.REGISTERED);
        assertThat(materialReturn.note()).isEqualTo("Saldo del piso 12");
        assertThat(materialReturn.worksiteId()).isEqualTo(WORKSITE);
        assertThat(materialReturn.warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(materialReturn.registeredBy()).isEqualTo(JUAN);
        assertThat(materialReturn.registeredAt()).isEqualTo(NOW);
        assertThat(materialReturn.version()).isZero();
        assertThat(materialReturn.lines()).hasSize(2);
        assertThat(materialReturn.lines().get(0).detail()).isEqualTo("2 con humedad");
        assertThat(materialReturn.lines().get(0).quantity()).isEqualByComparingTo("12");
        assertThat(materialReturn.lines().get(0).isVerified()).isFalse();
        assertThat(materialReturn.hasRejections()).isFalse();
        assertThat(MaterialReturn.PENDING).contains(MaterialReturn.Status.REGISTERED);
    }

    @Test
    void the_balance_needs_lines_without_repeated_materials_and_valid_quantities() {
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(), null, JUAN, NOW))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "RETURN_WITHOUT_LINES");
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, null, null, JUAN, NOW))
                .hasFieldOrPropertyWithValue("code", "RETURN_WITHOUT_LINES");
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(
                new DeclaredLine(CEMENT, BigDecimal.ONE, null), new DeclaredLine(CEMENT, BigDecimal.TEN, null)),
                null, JUAN, NOW)).hasFieldOrPropertyWithValue("code", "DUPLICATED_MATERIAL");
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(
                new DeclaredLine(CEMENT, BigDecimal.ZERO, null)), null, JUAN, NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_QUANTITY");
        assertThatThrownBy(() -> new MaterialReturn(UUID.randomUUID(), WORKSITE, WAREHOUSE,
                MaterialReturn.Status.REGISTERED, null, JUAN, NOW, List.of(), 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_requested_return_is_approved_and_dispatched_by_each_side() {
        MaterialReturn materialReturn = registered();
        materialReturn.request(JUAN, NOW.plusSeconds(1));
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.REQUESTED);
        assertThat(materialReturn.requestedBy()).isEqualTo(JUAN);
        assertThat(materialReturn.requestedAt()).isEqualTo(NOW.plusSeconds(1));

        materialReturn.approve(ROSA, NOW.plusSeconds(2));
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.APPROVED);
        assertThat(materialReturn.decidedBy()).isEqualTo(ROSA);
        assertThat(materialReturn.decidedAt()).isEqualTo(NOW.plusSeconds(2));

        materialReturn.dispatch(JUAN, NOW.plusSeconds(3));
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.IN_TRANSIT);
        assertThat(materialReturn.dispatchedBy()).isEqualTo(JUAN);
        assertThat(materialReturn.dispatchedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void a_rejected_request_keeps_the_reason_and_counts_as_a_rejection() {
        MaterialReturn materialReturn = registered();
        materialReturn.request(JUAN, NOW);
        assertThatThrownBy(() -> materialReturn.reject(" ", ROSA, NOW)).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "REJECTION_REASON_REQUIRED");

        materialReturn.reject("El material es de otra obra", ROSA, NOW);

        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.REJECTED);
        assertThat(materialReturn.rejectionReason()).isEqualTo("El material es de otra obra");
        assertThat(materialReturn.hasRejections()).isTrue();
        assertThatThrownBy(() -> materialReturn.dispatch(JUAN, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
    }

    @Test
    void the_warehouse_accepts_part_and_records_the_reason_of_the_rest() {
        MaterialReturn materialReturn = inTransit();

        List<MaterialReturnLine> verified = materialReturn.receive(Map.of(
                CEMENT, new Verification(new BigDecimal("10"), " Bolsas húmedas "),
                STEEL, new Verification(new BigDecimal("3.5"), "ignorado")), ROSA, NOW.plusSeconds(9));

        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.RECEIVED);
        assertThat(materialReturn.receivedBy()).isEqualTo(ROSA);
        assertThat(materialReturn.receivedAt()).isEqualTo(NOW.plusSeconds(9));
        assertThat(verified.get(0).acceptedQty()).isEqualByComparingTo("10");
        assertThat(verified.get(0).rejectedQty()).isEqualByComparingTo("2");
        assertThat(verified.get(0).rejectionReason()).isEqualTo("Bolsas húmedas");
        assertThat(verified.get(0).hasRejection()).isTrue();
        assertThat(verified.get(1).rejectedQty()).isEqualByComparingTo("0");
        assertThat(verified.get(1).rejectionReason()).isNull();
        assertThat(verified.get(1).hasRejection()).isFalse();
        assertThat(materialReturn.lines()).allMatch(MaterialReturnLine::isVerified);
        assertThat(materialReturn.hasRejections()).isTrue();
    }

    @Test
    void the_verification_covers_every_material_within_the_returned_quantity() {
        MaterialReturn materialReturn = inTransit();
        Verification all = new Verification(new BigDecimal("3.5"), null);

        assertThatThrownBy(() -> materialReturn.receive(Map.of(STEEL, all), ROSA, NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "RETURN_VERIFICATION_INCOMPLETE");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, all, STEEL, all, UUID.randomUUID(), all),
                ROSA, NOW)).hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_IN_RETURN");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(new BigDecimal("13"), null),
                STEEL, all), ROSA, NOW)).hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(new BigDecimal("-1"), null),
                STEEL, all), ROSA, NOW)).hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(new BigDecimal("1.0001"),
                null), STEEL, all), ROSA, NOW)).hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(null, null), STEEL, all),
                ROSA, NOW)).hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(BigDecimal.ONE, null),
                STEEL, all), ROSA, NOW)).hasFieldOrPropertyWithValue("code", "REJECTION_REASON_REQUIRED");
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.IN_TRANSIT);
    }

    @Test
    void only_a_return_not_yet_approved_can_be_cancelled() {
        MaterialReturn registered = registered();
        registered.cancel(JUAN, NOW);
        assertThat(registered.status()).isEqualTo(MaterialReturn.Status.CANCELLED);
        assertThat(registered.cancelledBy()).isEqualTo(JUAN);
        assertThat(registered.cancelledAt()).isEqualTo(NOW);

        MaterialReturn requested = registered();
        requested.request(JUAN, NOW);
        requested.cancel(JUAN, NOW);
        assertThat(requested.status()).isEqualTo(MaterialReturn.Status.CANCELLED);

        MaterialReturn moving = inTransit();
        assertThatThrownBy(() -> moving.cancel(JUAN, NOW)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
    }

    @Test
    void transitions_out_of_order_are_rejected() {
        MaterialReturn materialReturn = registered();
        assertThatThrownBy(() -> materialReturn.approve(ROSA, NOW)).hasFieldOrPropertyWithValue("code",
                "INVALID_RETURN_STATUS");
        assertThatThrownBy(() -> materialReturn.reject("x", ROSA, NOW)).hasFieldOrPropertyWithValue("code",
                "INVALID_RETURN_STATUS");
        assertThatThrownBy(() -> materialReturn.receive(new HashMap<>(), ROSA, NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
        materialReturn.request(JUAN, NOW);
        assertThatThrownBy(() -> materialReturn.request(JUAN, NOW)).hasFieldOrPropertyWithValue("code",
                "INVALID_RETURN_STATUS");
    }

    @Test
    void lines_keep_accepted_plus_rejected_equal_to_the_returned_quantity() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new MaterialReturnLine(id, CEMENT, BigDecimal.ZERO, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MaterialReturnLine(id, CEMENT, BigDecimal.TEN, null, BigDecimal.ONE, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MaterialReturnLine(id, CEMENT, BigDecimal.TEN, null, BigDecimal.ONE,
                BigDecimal.ONE, "x")).isInstanceOf(IllegalStateException.class);
        assertThat(new MaterialReturnLine(id, CEMENT, BigDecimal.TEN, null, BigDecimal.ONE, new BigDecimal("9"), "x")
                .hasRejection()).isTrue();
    }

    @Test
    void a_return_is_rebuilt_from_storage() {
        UUID id = UUID.randomUUID();
        MaterialReturn materialReturn = new MaterialReturn(id, WORKSITE, WAREHOUSE, MaterialReturn.Status.RECEIVED,
                null, JUAN, NOW, List.of(new MaterialReturnLine(UUID.randomUUID(), CEMENT, BigDecimal.TEN, null,
                BigDecimal.TEN, BigDecimal.ZERO, null)), 5);
        materialReturn.restore(JUAN, NOW, ROSA, NOW, null, JUAN, NOW, ROSA, NOW, null, null);

        assertThat(materialReturn.id()).isEqualTo(id);
        assertThat(materialReturn.version()).isEqualTo(5);
        assertThat(materialReturn.receivedBy()).isEqualTo(ROSA);
        assertThat(materialReturn.hasRejections()).isFalse();
        assertThat(materialReturn.cancelledAt()).isNull();
    }
}
