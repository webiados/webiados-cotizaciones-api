package com.webiados.cotizaciones.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Admin admin,
        Quote quote,
        Mail mail,
        Cors cors,
        Ratelimit ratelimit,
        Pricing pricing,
        Leads leads,
        StaleAlert staleAlert
) {
    /**
     * Lectura de leads del CRM del Core. El formulario público postea al Core (ahí vive el CRM);
     * este servicio <strong>lee</strong> un lead cuando el admin lo convierte en cotización.
     *
     * @param url     endpoint del Core, p.ej. https://core.webiados.com/api/v1/leads
     * @param apiKey  llave de tenant del Core ({@code Authorization: Bearer wcore_live_…}). El
     *                tenant sale de la llave. Puede venir vacía: la conversión de leads es opcional
     *                y el servicio arranca igual; sin llave, convertir un lead falla con un mensaje
     *                claro (no impide el resto).
     * @param timeoutSeconds tope de espera contra el Core
     */
    public record Leads(String url, String apiKey, int timeoutSeconds) {
    }

    /**
     * Catálogo de precios del Core. La fuente de verdad es `pricing.md`; el Core la publica ya
     * calculada (UF, neto, con IVA y USD del día). Este servicio la <strong>consume verbatim</strong>:
     * no convierte nada, para no diferir en un peso con lo que muestra el sitio.
     *
     * @param url      endpoint del Core, p.ej. https://core.webiados.com/api/v1/pricing
     * @param timeoutSeconds tope de espera; si el Core no responde se sirve el último cacheado
     */
    public record Pricing(String url, int timeoutSeconds) {
    }

    public record Admin(String bootstrapEmail, String bootstrapPassword) {
    }

    /**
     * @param publicBaseUrl base de la URL que ve el cliente, sin barra final. La landing
     *                      la sirve el frontend Angular, no este servicio. Antes estaba
     *                      escrita a mano dentro de QuoteService.
     */
    public record Quote(int validityDays, String publicBaseUrl) {
    }

    public record Mail(String from, String notifyTo) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Ratelimit(int unlockMaxAttempts, int unlockWindowMinutes) {
    }

    /**
     * Aviso interno de "cotización sin respuesta". Apagado por defecto (regla 13): nada se
     * enciende porque exista el código, se enciende con su propia variable, apagada, que
     * alguien prende a propósito.
     *
     * @param enabled apagado por defecto
     * @param days    días desde el envío (o desde que se abrió, si nunca se marcó enviada)
     *                sin selección ni rechazo, antes de avisar — 7 por defecto: la mitad de
     *                {@code QUOTE_VALIDITY_DAYS} (15), deja una semana entera todavía para
     *                actuar antes de que expire sola.
     */
    public record StaleAlert(boolean enabled, int days) {
    }
}
