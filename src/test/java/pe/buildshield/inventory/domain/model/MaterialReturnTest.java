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
    private static final UUID CEMENT = UUID.randomUUID();
    private static final UUID STEEL = UUID.randomUUID();

    private MaterialReturn registered() {
        return MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(new DeclaredLine(CEMENT, new BigDecimal("12")),
                new DeclaredLine(STEEL, new BigDecimal("3.5"))), JUAN, NOW);
    }

    private MaterialReturn inTransit() {
        MaterialReturn materialReturn = registered();
        materialReturn.request();
        materialReturn.approve();
        materialReturn.dispatch();
        return materialReturn;
    }

    @Test
    void the_site_manager_registers_and_details_the_balance() {
        MaterialReturn materialReturn = registered();

        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.REGISTERED);
        assertThat(materialReturn.persistedStatus()).isEqualTo(MaterialReturn.Status.REGISTERED);
        assertThat(materialReturn.worksiteId()).isEqualTo(WORKSITE);
        assertThat(materialReturn.warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(materialReturn.registeredBy()).isEqualTo(JUAN);
        assertThat(materialReturn.registeredAt()).isEqualTo(NOW);
        assertThat(materialReturn.lines()).hasSize(2);
        assertThat(materialReturn.lines().get(0).quantity()).isEqualByComparingTo("12");
        assertThat(materialReturn.lines().get(0).isVerified()).isFalse();
        assertThat(materialReturn.lines().get(0).rejectedQty()).isNull();
        assertThat(materialReturn.lines().get(0).id().toString())
                .isLessThan(materialReturn.lines().get(1).id().toString());
        assertThat(materialReturn.hasRejections()).isFalse();
        assertThat(MaterialReturn.PENDING).contains(MaterialReturn.Status.REGISTERED);
    }

    @Test
    void the_balance_needs_lines_without_repeated_materials_and_valid_quantities() {
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(), JUAN, NOW))
                .isInstanceOf(ValidationException.class).hasFieldOrPropertyWithValue("code", "RETURN_WITHOUT_LINES");
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, null, JUAN, NOW))
                .hasFieldOrPropertyWithValue("code", "RETURN_WITHOUT_LINES");
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(
                new DeclaredLine(CEMENT, BigDecimal.ONE), new DeclaredLine(CEMENT, BigDecimal.TEN)), JUAN, NOW))
                .hasFieldOrPropertyWithValue("code", "DUPLICATED_MATERIAL");
        assertThatThrownBy(() -> MaterialReturn.register(WORKSITE, WAREHOUSE, List.of(
                new DeclaredLine(CEMENT, BigDecimal.ZERO)), JUAN, NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_QUANTITY");
        assertThatThrownBy(() -> new MaterialReturn(UUID.randomUUID(), WORKSITE, WAREHOUSE,
                MaterialReturn.Status.REGISTERED, JUAN, NOW, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_requested_return_is_approved_and_dispatched_by_each_side() {
        MaterialReturn materialReturn = registered();
        materialReturn.request();
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.REQUESTED);
        materialReturn.approve();
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.APPROVED);
        materialReturn.dispatch();
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.IN_TRANSIT);
        assertThat(materialReturn.persistedStatus()).isEqualTo(MaterialReturn.Status.REGISTERED);
    }

    @Test
    void a_rejected_request_needs_a_reason_and_counts_as_a_rejection() {
        MaterialReturn materialReturn = registered();
        materialReturn.request();
        assertThatThrownBy(() -> materialReturn.reject(" ")).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "REJECTION_REASON_REQUIRED");
        assertThatThrownBy(() -> materialReturn.reject("x".repeat(501))).isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "TEXT_TOO_LONG");

        materialReturn.reject("El material es de otra obra");

        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.REJECTED);
        assertThat(materialReturn.rejectionReason()).isEqualTo("El material es de otra obra");
        assertThat(materialReturn.hasRejections()).isTrue();
        assertThatThrownBy(materialReturn::dispatch).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
    }

    @Test
    void the_warehouse_accepts_part_and_the_rest_is_the_difference_with_its_reason() {
        MaterialReturn materialReturn = inTransit();

        List<MaterialReturnLine> verified = materialReturn.receive(Map.of(
                CEMENT, new Verification(new BigDecimal("10"), " Bolsas húmedas "),
                STEEL, new Verification(new BigDecimal("3.5"), "ignorado")));

        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.RECEIVED);
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

        assertThatThrownBy(() -> materialReturn.receive(Map.of(STEEL, all)))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "RETURN_VERIFICATION_INCOMPLETE");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, all, STEEL, all, UUID.randomUUID(), all)))
                .hasFieldOrPropertyWithValue("code", "MATERIAL_NOT_IN_RETURN");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(new BigDecimal("13"), null),
                STEEL, all))).hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(new BigDecimal("-1"), null),
                STEEL, all))).hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(new BigDecimal("1.0001"),
                null), STEEL, all))).hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(null, null), STEEL, all)))
                .hasFieldOrPropertyWithValue("code", "INVALID_ACCEPTED_QUANTITY");
        assertThatThrownBy(() -> materialReturn.receive(Map.of(CEMENT, new Verification(BigDecimal.ONE, null),
                STEEL, all))).hasFieldOrPropertyWithValue("code", "REJECTION_REASON_REQUIRED");
        assertThat(materialReturn.status()).isEqualTo(MaterialReturn.Status.IN_TRANSIT);
    }

    @Test
    void only_a_return_not_yet_approved_can_be_cancelled() {
        MaterialReturn registered = registered();
        registered.cancel();
        assertThat(registered.status()).isEqualTo(MaterialReturn.Status.CANCELLED);

        MaterialReturn requested = registered();
        requested.request();
        requested.cancel();
        assertThat(requested.status()).isEqualTo(MaterialReturn.Status.CANCELLED);

        MaterialReturn moving = inTransit();
        assertThatThrownBy(moving::cancel).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
    }

    @Test
    void transitions_out_of_order_are_rejected() {
        MaterialReturn materialReturn = registered();
        assertThatThrownBy(materialReturn::approve).hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
        assertThatThrownBy(() -> materialReturn.reject("x")).hasFieldOrPropertyWithValue("code",
                "INVALID_RETURN_STATUS");
        assertThatThrownBy(() -> materialReturn.receive(new HashMap<>()))
                .hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
        materialReturn.request();
        assertThatThrownBy(materialReturn::request).hasFieldOrPropertyWithValue("code", "INVALID_RETURN_STATUS");
    }

    @Test
    void lines_never_accept_more_than_the_returned_quantity() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new MaterialReturnLine(id, CEMENT, BigDecimal.ZERO, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MaterialReturnLine(id, CEMENT, BigDecimal.ONE, BigDecimal.TEN, null))
                .isInstanceOf(IllegalStateException.class);
        MaterialReturnLine line = new MaterialReturnLine(id, CEMENT, BigDecimal.TEN, BigDecimal.ONE, "x");
        assertThat(line.hasRejection()).isTrue();
        assertThat(line.rejectedQty()).isEqualByComparingTo("9");
    }

    @Test
    void a_return_is_rebuilt_from_storage_and_rejections_come_from_the_quantities() {
        UUID id = UUID.randomUUID();
        MaterialReturn clean = new MaterialReturn(id, WORKSITE, WAREHOUSE, MaterialReturn.Status.RECEIVED, JUAN, NOW,
                List.of(new MaterialReturnLine(UUID.randomUUID(), CEMENT, BigDecimal.TEN, BigDecimal.TEN, null)));
        assertThat(clean.id()).isEqualTo(id);
        assertThat(clean.persistedStatus()).isEqualTo(MaterialReturn.Status.RECEIVED);
        assertThat(clean.hasRejections()).isFalse();
        assertThat(clean.rejectionReason()).isNull();

        MaterialReturn partial = new MaterialReturn(id, WORKSITE, WAREHOUSE, MaterialReturn.Status.RECEIVED, JUAN, NOW,
                List.of(new MaterialReturnLine(UUID.randomUUID(), CEMENT, BigDecimal.TEN, BigDecimal.ONE, null)));
        assertThat(partial.hasRejections()).isTrue();
    }
}
