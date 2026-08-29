package com.webiados.cotizaciones.dto.admin;

import java.util.UUID;

/**
 * Un aviso no bloqueante de {@code PricingWarningService}, con su destino como dato.
 *
 * <p>Antes viajaba como texto plano y el panel deducía a qué opción pertenecía parseando el
 * título entre «comillas» al principio del mensaje. Funcionaba solo porque, en el diseño de
 * hoy, ese título es siempre {@code option.getTitulo()} de la misma llamada — pero el título de
 * una opción <strong>no es único dentro de una cotización</strong> (dos formas de pagar el mismo
 * kit, tituladas igual a propósito, es un caso real). Con dos opciones de igual título el match
 * por texto no sabe a cuál de las dos pertenece, y el fallo es silencioso: el aviso se pinta en
 * la tarjeta equivocada, no desaparece.
 *
 * @param optionId a qué opción pertenece. {@code null} solo para un aviso que de verdad no
 *                 pertenece a ninguna opción — hoy no existe ese caso: incluso el aviso de "no
 *                 se encontró en el catálogo" pertenece a la opción cuyo {@code pricingRef} no
 *                 resolvió, así que trae su id.
 * @param message  el texto legible, sin cambios respecto de antes — el título entre «comillas»
 *                 se queda porque ayuda a leerlo, solo deja de ser el mecanismo de match.
 */
public record OptionWarning(UUID optionId, String message) {
}
