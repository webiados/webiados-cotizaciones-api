package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.dto.admin.OptionWarning;
import com.webiados.cotizaciones.dto.pricing.ItemPrecio;
import com.webiados.cotizaciones.dto.pricing.PlanSinPie;
import com.webiados.cotizaciones.dto.pricing.PricingCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Candado suave: avisa cuando una opción con {@code pricingRef} no calza con el catálogo del
 * Core, sin bloquear el guardado. Nunca compara una opción armada a mano (sin {@code pricingRef}
 * — la mitad de los casos, y es exactamente para eso que sirve una cotización).
 *
 * <p>{@code optionId} viaja como dato, no se deduce del texto: el panel lo usó al principio para
 * matchear la tarjeta parseando el título entre «comillas», y eso se rompe apenas un título trae
 * una comilla o el texto cambia de forma — y se rompe en silencio: "no matcheó" se ve igual que
 * "no hay avisos".
 */
class PricingWarningServiceTest {

    private static final ItemPrecio KIT_TIENDA_SIN_PLAN_SIN_PIE = new ItemPrecio(
            "Tienda", null, null, BigDecimal.valueOf(890000), BigDecimal.valueOf(49000),
            null, null, null, null);

    private static final ItemPrecio KIT_AGENDA_CON_PLAN_SIN_PIE = new ItemPrecio(
            "Agenda", null, null, BigDecimal.valueOf(790000), BigDecimal.valueOf(45000),
            null, null, null,
            new PlanSinPie(BigDecimal.valueOf(111000), 12, null));

    private static final PricingCatalog CATALOGO = new PricingCatalog(
            "CLP", false, new BigDecimal("0.19"), null, "2026-08-29",
            List.of(),
            null, null,
            List.of(KIT_TIENDA_SIN_PLAN_SIN_PIE, KIT_AGENDA_CON_PLAN_SIN_PIE),
            List.of(new ItemPrecio(null, "agenda", "Módulo de reservas",
                    BigDecimal.valueOf(250000), BigDecimal.valueOf(15000), null, null, null, null)),
            List.of(),
            List.of(),
            List.of()
    );

    private static QuoteOption opcion(String titulo, long precio, Long mensual, String pricingRef) {
        return opcion(titulo, precio, mensual, pricingRef, null);
    }

    private static QuoteOption opcion(String titulo, long precio, Long mensual, String pricingRef,
                                       Integer planSinPieMeses) {
        return new QuoteOption(UUID.randomUUID(), 0, titulo, "descripción",
                BigDecimal.valueOf(precio), mensual == null ? null : BigDecimal.valueOf(mensual),
                "CLP", false, List.of(), pricingRef, planSinPieMeses);
    }

    @Test
    void sin_pricingRef_no_compara_nada() {
        var opciones = List.of(opcion("Armada a mano", 999999, null, null));

        var warnings = new PricingWarningService(() -> CATALOGO).check(opciones);

        assertThat(warnings).isEmpty();
    }

    @Test
    void con_pricingRef_y_precio_igual_al_catalogo_no_avisa() {
        var opciones = List.of(opcion("Kit Tienda", 890000, 49000L, "Tienda"));

        var warnings = new PricingWarningService(() -> CATALOGO).check(opciones);

        assertThat(warnings).isEmpty();
    }

    @Test
    void con_pricingRef_y_setup_distinto_avisa_con_el_optionId_y_ambos_montos() {
        var opcion = opcion("Kit Tienda", 850000, 49000L, "Tienda");

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        OptionWarning w = warnings.get(0);
        assertThat(w.optionId())
                .as("el panel matchea por este id, no parseando el texto")
                .isEqualTo(opcion.getId());
        assertThat(w.message()).contains("Kit Tienda").contains("$890.000").contains("$850.000");
    }

    @Test
    void con_pricingRef_y_mensual_distinto_avisa() {
        var opcion = opcion("Kit Tienda", 890000, 45000L, "Tienda");

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).optionId()).isEqualTo(opcion.getId());
        assertThat(warnings.get(0).message()).contains("Kit Tienda")
                .contains("$45.000").contains("$49.000");
    }

    @Test
    void matchea_addons_por_slug() {
        var opcion = opcion("Reservas", 200000, 15000L, "agenda");

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message())
                .contains("Reservas").contains("$250.000").contains("$200.000");
    }

    @Test
    void pricingRef_que_ya_no_existe_en_el_catalogo_avisa_con_el_optionId_de_la_opcion_afectada() {
        var opcion = opcion("Kit descontinuado", 100000, null, "kit-que-no-existe");

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        OptionWarning w = warnings.get(0);
        assertThat(w.optionId())
                .as("este aviso también pertenece a una opción real — la que tiene el ref roto — "
                        + "no es un caso huérfano")
                .isEqualTo(opcion.getId());
        assertThat(w.message()).contains("Kit descontinuado").contains("no se encontró");
    }

    @Test
    void si_el_core_no_responde_no_revienta_la_lectura_de_la_cotizacion() {
        var opciones = List.of(opcion("Kit Tienda", 850000, 49000L, "Tienda"));
        var service = new PricingWarningService(() -> {
            throw new IllegalStateException("Core caído y sin cache");
        });

        var warnings = service.check(opciones);

        assertThat(warnings).isEmpty();
    }

    // --- Plan sin pie: la misma opción, la segunda forma de pagarla ---

    @Test
    void pricingRef_con_sufijo_sin_pie_y_meses_correctos_compara_contra_el_plan_sin_pie_no_contra_el_normal() {
        var opcion = opcion("Kit Agenda — sin pie", 0, 111000L, "Agenda:sin-pie", 12);

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings)
                .as("111.000/12 meses es el plan sin pie de Agenda, no debe compararse contra el normal")
                .isEmpty();
    }

    @Test
    void plan_sin_pie_con_mensual_distinto_avisa() {
        var opcion = opcion("Kit Agenda — sin pie", 0, 110000L, "Agenda:sin-pie", 12);

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message())
                .contains("Kit Agenda — sin pie").contains("$111.000").contains("$110.000");
    }

    @Test
    void plan_sin_pie_con_instalacion_distinta_de_cero_avisa() {
        var opcion = opcion("Kit Agenda — sin pie", 50000, 111000L, "Agenda:sin-pie", 12);

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message())
                .as("el plan sin pie no lleva instalación al firmar; $0 es el valor correcto")
                .contains("Kit Agenda — sin pie").contains("$0").contains("$50.000");
    }

    @Test
    void pricingRef_sin_pie_para_un_kit_sin_plan_sin_pie_publicado_avisa_sin_reventar() {
        var opcion = opcion("Kit Tienda — sin pie", 0, 124000L, "Tienda:sin-pie", 12);

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).optionId()).isEqualTo(opcion.getId());
        assertThat(warnings.get(0).message())
                .contains("Kit Tienda — sin pie")
                .contains("no tiene plan sin pie publicado");
    }

    @Test
    void plan_sin_pie_sin_indicar_los_meses_avisa_en_vez_de_quedar_muda() {
        var opcion = opcion("Kit Agenda — sin pie", 0, 111000L, "Agenda:sin-pie", null);

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).optionId()).isEqualTo(opcion.getId());
        assertThat(warnings.get(0).message())
                .as("\"dura los meses que se indican en esta cotización\" sin indicarlos en ningún "
                        + "campo es justo lo que este aviso existe para atrapar")
                .contains("Kit Agenda — sin pie")
                .contains("no indica los meses")
                .contains("12");
    }

    @Test
    void plan_sin_pie_con_meses_distintos_del_catalogo_avisa() {
        var opcion = opcion("Kit Agenda — sin pie", 0, 111000L, "Agenda:sin-pie", 24);

        var warnings = new PricingWarningService(() -> CATALOGO).check(List.of(opcion));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).message())
                .contains("Kit Agenda — sin pie").contains("12").contains("24");
    }
}
