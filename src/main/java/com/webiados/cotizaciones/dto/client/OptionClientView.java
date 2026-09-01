package com.webiados.cotizaciones.dto.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Una opción tal como la ve el cliente.
 *
 * <p>Los montos van desglosados —neto, IVA y total, tanto del pago único como de la
 * mensualidad— para que el frontend no tenga que calcular impuestos y para que el IVA
 * deje de escribirse a mano dentro del texto de la descripción.
 *
 * <p>Los campos {@code precioMensual*} son {@code null} cuando la opción no tiene
 * mensualidad.
 *
 * <p>{@code planSinPieMeses} va <strong>antes</strong> de los montos a propósito: si alguien ve
 * el precio mensual y decide ahí, tiene que saber que es "durante N meses" antes de leer el
 * número, no después de un párrafo de texto. {@code null} si la opción no es un plan sin pie.
 */
public record OptionClientView(
        UUID id,
        int orderIndex,
        String titulo,
        String descripcion,
        Integer planSinPieMeses,
        BigDecimal precio,
        BigDecimal precioIva,
        BigDecimal precioTotal,
        BigDecimal precioMensual,
        BigDecimal precioMensualIva,
        BigDecimal precioMensualTotal,
        String currency,
        int ivaPct,
        boolean recomendado,
        List<String> features
) {
}
