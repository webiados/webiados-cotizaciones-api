package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.dto.pricing.ItemPrecio;
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
 */
class PricingWarningServiceTest {

    private static final PricingCatalog CATALOGO = new PricingCatalog(
            "CLP", false, new BigDecimal("0.19"), null, "2026-08-29",
            List.of(),
            null, null,
            List.of(new ItemPrecio("Tienda", null, null,
                    BigDecimal.valueOf(890000), BigDecimal.valueOf(49000), null, null, null)),
            List.of(new ItemPrecio(null, "agenda", "Módulo de reservas",
                    BigDecimal.valueOf(250000), BigDecimal.valueOf(15000), null, null, null)),
            List.of(),
            List.of(),
            List.of()
    );

    private static QuoteOption opcion(String titulo, long precio, Long mensual, String pricingRef) {
        return new QuoteOption(UUID.randomUUID(), 0, titulo, "descripción",
                BigDecimal.valueOf(precio), mensual == null ? null : BigDecimal.valueOf(mensual),
                "CLP", false, List.of(), pricingRef);
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
    void con_pricingRef_y_setup_distinto_avisa_con_ambos_montos() {
        var opciones = List.of(opcion("Kit Tienda", 850000, 49000L, "Tienda"));

        var warnings = new PricingWarningService(() -> CATALOGO).check(opciones);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("Kit Tienda")
                .contains("$890.000")
                .contains("$850.000");
    }

    @Test
    void con_pricingRef_y_mensual_distinto_avisa() {
        var opciones = List.of(opcion("Kit Tienda", 890000, 45000L, "Tienda"));

        var warnings = new PricingWarningService(() -> CATALOGO).check(opciones);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("Kit Tienda")
                .contains("$49.000")
                .contains("$45.000");
    }

    @Test
    void matchea_addons_por_slug() {
        var opciones = List.of(opcion("Reservas", 200000, 15000L, "agenda"));

        var warnings = new PricingWarningService(() -> CATALOGO).check(opciones);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("Reservas").contains("$250.000").contains("$200.000");
    }

    @Test
    void pricingRef_que_ya_no_existe_en_el_catalogo_avisa_sin_reventar() {
        var opciones = List.of(opcion("Kit descontinuado", 100000, null, "kit-que-no-existe"));

        var warnings = new PricingWarningService(() -> CATALOGO).check(opciones);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("Kit descontinuado").contains("no se encontró");
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
}
