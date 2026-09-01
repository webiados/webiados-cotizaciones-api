package com.webiados.cotizaciones.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * Montos <strong>netos</strong>, sin IVA. El IVA lo calcula el servicio a partir del
 * porcentaje de la cotización; no se manda ni se guarda ya sumado.
 *
 * @param precio           pago único de instalación / desarrollo
 * @param precioMensual    mensualidad recurrente, o {@code null} si la opción no tiene
 * @param pricingRef       slug/nombre del ítem del catálogo del Core del que salió el precio, o
 *                         {@code null} si la opción se armó a mano (combinada, negociada, a
 *                         medida). Lo llena el panel al elegir del catálogo, no se teclea — sirve
 *                         solo para el aviso no bloqueante de {@code PricingWarningService} cuando
 *                         el precio guardado deja de calzar con lo que el Core publica hoy. Con
 *                         el sufijo {@code ":sin-pie"} (ej. {@code "Agenda:sin-pie"}) marca la
 *                         opción como plan sin pie del kit base.
 * @param planSinPieMeses  plazo del plan sin pie, o {@code null} si la opción no es un plan sin
 *                         pie. Dato, no texto libre — para que el plazo se pueda mostrar, contar
 *                         y comparar contra el catálogo, no solo leerse dentro de un párrafo.
 */
public record OptionRequest(
        @NotBlank String titulo,
        String descripcion,
        @NotNull @PositiveOrZero BigDecimal precio,
        @PositiveOrZero BigDecimal precioMensual,
        String currency,
        boolean recomendado,
        List<String> features,
        String pricingRef,
        @Positive Integer planSinPieMeses
) {
}
