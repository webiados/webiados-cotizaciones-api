package com.webiados.cotizaciones.service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * Presentación de datos para el cliente: nombres y fechas legibles.
 *
 * <p><strong>Regla 8 del repo:</strong> las horas se guardan en UTC y se <em>muestran</em> en
 * {@code America/Santiago}. Un {@link Instant} crudo ({@code 2026-08-18T02:43:01Z}) no le dice
 * nada a un cliente.
 */
public final class Formatos {

    private static final ZoneId ZONA_CHILE = ZoneId.of("America/Santiago");
    private static final Locale ES_CL = Locale.forLanguageTag("es-CL");

    /** "lunes 18 de agosto" — día de la semana + día + mes, sin hora, en horario de Chile. */
    private static final DateTimeFormatter VIGENCIA =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES_CL);

    /** Partículas que van en minúscula dentro de un nombre (menos si abren el nombre). */
    private static final Set<String> PARTICULAS =
            Set.of("de", "del", "la", "las", "los", "y", "e", "da", "van", "von");

    private Formatos() {
    }

    /**
     * Fecha de vigencia en lenguaje humano y en horario de Chile, sin hora.
     * Ej.: {@code 2026-08-18T02:43:01Z} → "domingo 17 de agosto" (ya es día 17 en Santiago).
     */
    public static String vigencia(Instant expiresAt) {
        // toLowerCase: el cliente quiere "lunes 18 de agosto", no "Lunes". Distintos JDK/proveedores
        // de locale capitalizan distinto; forzarlo garantiza el formato pedido.
        return expiresAt.atZone(ZONA_CHILE).format(VIGENCIA).toLowerCase(ES_CL);
    }

    /**
     * Normaliza un nombre para mostrarlo: recorta, colapsa espacios y capitaliza cada palabra,
     * dejando las partículas ("de", "del", "la"…) en minúscula salvo que abran el nombre.
     * Ej.: "felipe" → "Felipe"; "pastelería vientos del sur" → "Pastelería Vientos del Sur".
     */
    public static String nombre(String raw) {
        if (raw == null) {
            return null;
        }
        String limpio = raw.trim().replaceAll("\\s+", " ");
        if (limpio.isEmpty()) {
            return limpio;
        }
        String[] palabras = limpio.split(" ");
        StringBuilder sb = new StringBuilder(limpio.length());
        for (int i = 0; i < palabras.length; i++) {
            String lower = palabras[i].toLowerCase(ES_CL);
            if (i > 0 && PARTICULAS.contains(lower)) {
                sb.append(lower);
            } else {
                sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
            }
            if (i < palabras.length - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /** Monto en pesos, sin decimales, con separador de miles chileno: {@code 890000 → "$890.000"}. */
    public static String moneda(BigDecimal monto) {
        return "$" + NumberFormat.getIntegerInstance(ES_CL).format(monto);
    }
}
