package com.webiados.cotizaciones.dto.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Un ítem cotizable del catálogo del Core. Sirve para las tres familias:
 * <ul>
 *   <li><b>kits</b> y <b>landings</b>: identificados por {@code nombre} ({@code slug} nulo);</li>
 *   <li><b>addons</b>: identificados por {@code slug} + {@code etiqueta} ({@code nombre} nulo).</li>
 * </ul>
 *
 * <p>{@code setup}/{@code mensual} son netos en CLP; los {@code *Monto} traen el desglose en las
 * cuatro monedas ya calculado. {@code primerAnioMonto} = instalación + 12 mensualidades: lo que un
 * cliente desembolsa el primer año, útil para la cotización. (Los addons no traen primerAnioMonto.)
 *
 * <p>{@code planSinPie} es la segunda forma de pagar el mismo ítem — la misma plata del primer
 * año, repartida en cuotas desde el primer mes, sin instalación al firmar. {@code null} si el
 * Core no lo publica para este ítem.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemPrecio(
        String slug,
        String nombre,
        String etiqueta,
        BigDecimal setup,
        BigDecimal mensual,
        Monto setupMonto,
        Monto mensualMonto,
        Monto primerAnioMonto,
        PlanSinPie planSinPie
) {
}
