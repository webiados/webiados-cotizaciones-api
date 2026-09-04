package com.webiados.cotizaciones.dto.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QuoteClientView(
        String clientName,
        boolean canSelect,
        boolean isExpired,
        Instant expiresAt,
        UUID selectedOptionId,
        String titulo,
        String mensaje,
        String imagenes,
        int ivaPct,
        List<OptionClientView> options,
        /**
         * Cuándo se emitió la cotización — el momento en que TODOS los precios de abajo
         * quedaron congelados ("el precio es una foto", no cambia después). Existe para que el
         * camino de upgrade sobre una cotización ya aceptada pueda decir de qué fecha es el
         * precio que está mostrando, en vez de dejarlo pasar por vigente sin decirlo — una
         * cotización aceptada hace meses no vence, y las opciones que el cliente no eligió
         * siguen con botón activo y precio de entonces.
         */
        Instant createdAt
) {
}
