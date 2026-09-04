package com.webiados.cotizaciones.dto.admin;

import com.webiados.cotizaciones.domain.SelectionKind;

import java.time.Instant;
import java.util.UUID;

public record SelectionHistoryEntry(
        UUID selectionId,
        UUID optionId,
        String optionTitulo,
        SelectionKind kind,
        Instant createdAt,
        /** Si Resend avisó, por webhook, que el aviso interno de ESTA selección rebotó de
         *  verdad — {@code null} si nunca rebotó (o si el aviso todavía no se manda/responde).
         *  Distinto de un fallo de envío de la cotización: acá el cliente SÍ aceptó, lo que
         *  falló fue que alguien se enterara a tiempo. */
        Instant bounceDetectedAt,
        String bounceReason
) {
}
