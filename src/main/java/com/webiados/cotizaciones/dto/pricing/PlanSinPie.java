package com.webiados.cotizaciones.dto.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * La segunda forma de pagar un kit: la misma plata del primer año, repartida en cuotas
 * mensuales iguales desde el primer mes, sin instalación al firmar. {@code meses} es el plazo
 * — hoy 12 para todos los kits, pero es dato del Core, no una constante de este servicio.
 *
 * <p>{@code null} en {@link ItemPrecio#planSinPie()} significa que ese ítem no tiene plan sin
 * pie publicado todavía (el Core lo expone detrás de {@code EXPONER_PLAN_SIN_PIE}) — no que
 * cueste cero.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanSinPie(BigDecimal mensual, Integer meses, Monto mensualMonto) {
}
