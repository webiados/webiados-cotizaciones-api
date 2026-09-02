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
        Instant rejectedAt
) {
}
