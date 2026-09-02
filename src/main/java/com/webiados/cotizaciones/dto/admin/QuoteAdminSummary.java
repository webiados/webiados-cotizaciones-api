package com.webiados.cotizaciones.dto.admin;

import com.webiados.cotizaciones.domain.QuoteStatus;

import java.time.Instant;
import java.util.UUID;

public record QuoteAdminSummary(
        UUID id,
        String codigo,
        String clientName,
        String clientEmail,
        QuoteStatus status,
        String selectedOptionTitulo,
        Instant createdAt,
        Instant expiresAt,
        Instant sentAt,
        /**
         * Primera vez que el cliente puso la clave correcta, o {@code null} si nunca la abrió.
         * Con {@code status == SENT} y esto no-nulo: "vista, sin elegir" — antes invisible,
         * indistinguible de "nunca la abrió".
         */
        Instant unlockedAt,
        Instant selectedAt,
        Instant rejectedAt,
        /**
         * Cuándo falló el último intento real de enviar el correo, o {@code null} si nunca
         * falló (o el último intento sí funcionó). Con {@code status == PENDING} y esto
         * no-nulo: se intentó y no llegó — sin esto, se ve igual que una que nunca se
         * intentó enviar. Se puede reintentar sin recrear la cotización.
         */
        Instant sendFailedAt,
        String sendFailureReason
) {
}
