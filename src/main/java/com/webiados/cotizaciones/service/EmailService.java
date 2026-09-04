package com.webiados.cotizaciones.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.domain.SelectionKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Único camino de envío de correo: la API HTTP de Resend. No SMTP, no Gmail de respaldo — dos
 * caminos para lo mismo se desalinean, y el que no se usa se pudre sin que nadie lo note
 * (decisión 2026-09-04, ver {@code docs/correo-resend.md}).
 *
 * <p>Se manda por HTTP y no por el SMTP de Resend a propósito: la API devuelve un {@code id}
 * por cada correo aceptado, y ese id es la única forma de calzar el webhook de rebote contra
 * la cotización exacta — emparejar por el correo del cliente + una ventana de tiempo es un
 * supuesto, emparejar por este id no lo es.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    // Identidad de Webiados (fuente de verdad: bloque @theme de webiados/src/styles.css).
    private static final String LIMA = "#d3e600";
    private static final String TINTA = "#0a0a0a";
    private static final String MUTED = "#4b5563";
    private static final String SOFT = "#f5f5f5";
    private static final String PAPER = "#e5e8ef";
    private static final String SITIO = "https://webiados.com";
    // Logo blanco, para el encabezado sobre fondo tinta. Va incrustado en el correo (content_id),
    // NO como URL externa: el original del sitio es .webp, y WebP no lo soportan todos los
    // clientes de correo (Outlook no lo soporta; el proxy de imágenes de Gmail lo mostró corrupto
    // en vez de renderizarlo). PNG incrustado no depende del sitio ni del formato.
    private static final String LOGO_CID = "logo-webiados";
    private static final String LOGO_RESOURCE = "email/logo-webiados-white.png";
    // Pila de fuentes del sistema: NO se pueden usar fuentes web (Bricolage) en un correo.
    private static final String FUENTE =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO =
            "'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace";

    private final RestClient http;
    private final AppProperties props;
    private final String resendUrl;

    // Cargado una sola vez: el logo no cambia entre requests.
    private volatile String logoBase64;

    @Autowired
    public EmailService(AppProperties props) {
        this(props, buildClient(), props.mail().apiUrl());
    }

    /** Para pruebas: inyectar un RestClient apuntando a un Resend simulado. */
    EmailService(AppProperties props, RestClient http, String resendUrl) {
        this.props = props;
        this.http = http;
        this.resendUrl = resendUrl;
    }

    private static RestClient buildClient() {
        Duration t = Duration.ofSeconds(10);
        var settings = ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(t).withReadTimeout(t);
        return RestClient.builder().requestFactory(ClientHttpRequestFactories.get(settings)).build();
    }

    /**
     * Le manda la cotización al cliente: el link y la clave de acceso, con la identidad de
     * Webiados. HTML (tablas + estilos en línea, para que Gmail no lo rompa) con una versión en
     * texto plano en el mismo correo (sin ella, varios filtros lo mandan a spam).
     *
     * <p>Síncrono y propaga la excepción a propósito: si el correo no sale, la cotización no
     * puede quedar marcada como enviada.
     *
     * @return el id que Resend asignó al envío — se guarda en la cotización para poder calzar
     *         un rebote futuro contra ella exactamente, sin adivinar.
     */
    public String sendQuoteToClient(Quote quote, String url) {
        if (quote.getClientEmail() == null || quote.getClientEmail().isBlank()) {
            throw new IllegalStateException(
                    "La cotización no tiene correo del cliente: no hay a quién enviarla");
        }
        if (quote.getClaveTexto() == null || quote.getClaveTexto().isBlank()) {
            throw new IllegalStateException(
                    "No se conserva la clave en texto de esta cotización; no se puede enviar. "
                            + "Crea una cotización nueva o entrégala manualmente.");
        }

        String nombre = quote.getClientName();
        String codigo = quote.getCodigo();
        String clave = quote.getClaveTexto();
        String vigencia = Formatos.vigencia(quote.getExpiresAt());

        String subject = "Tu cotización de Webiados — %s".formatted(
                quote.getTitulo() != null && !quote.getTitulo().isBlank()
                        ? quote.getTitulo()
                        : nombre);

        String html = buildHtml(nombre, url, codigo, clave, vigencia);
        String text = buildText(nombre, url, codigo, clave, vigencia);

        var attachment = Map.of(
                "filename", "logo-webiados.png",
                "content", loadLogoBase64(),
                "content_id", LOGO_CID);

        String resendId = sendViaResend(quote.getClientEmail(), props.mail().notifyTo(), subject,
                html, text, List.of(attachment));

        log.info("Cotización {} enviada por correo a {} (resend id {})",
                codigo, quote.getClientEmail(), resendId);
        return resendId;
    }

    /** HTML del correo: tablas para el layout, estilos en línea, 600px, fuentes del sistema. */
    static String buildHtml(String nombre, String url, String codigo, String clave, String vigencia) {
        String n = htmlEscape(nombre);
        String u = htmlEscape(url);
        return """
                <!DOCTYPE html>
                <html lang="es">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <meta name="x-apple-disable-message-reformatting">
                  <title>Tu cotización de Webiados</title>
                </head>
                <body style="margin:0; padding:0; background-color:%1$s;">
                  <div style="display:none; max-height:0; overflow:hidden; opacity:0;">Tu cotización está lista. Ábrela con tu clave de acceso.</div>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%1$s;">
                    <tr>
                      <td align="center" style="padding:24px 12px;">
                        <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="width:100%%; max-width:600px; background-color:#ffffff; border-radius:16px; overflow:hidden; font-family:%2$s;">
                          <tr>
                            <td align="center" style="background-color:%3$s; padding:28px 24px;">
                              <img src="cid:%4$s" alt="Webiados" width="150" style="display:block; border:0; height:auto; color:%5$s; font-size:22px; font-weight:bold;">
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:32px 32px 8px 32px;">
                              <p style="margin:0 0 12px 0; font-size:20px; font-weight:700; color:%3$s;">Hola %6$s,</p>
                              <p style="margin:0 0 24px 0; font-size:16px; line-height:1.6; color:%7$s;">Preparamos tu cotización. Adentro están las opciones con su detalle y sus valores; puedes revisarlas y elegir la que prefieras desde la misma página.</p>
                            </td>
                          </tr>
                          <tr>
                            <td align="center" style="padding:0 32px 28px 32px;">
                              <table role="presentation" cellpadding="0" cellspacing="0">
                                <tr>
                                  <td align="center" bgcolor="%5$s" style="border-radius:9999px;">
                                    <a href="%8$s" target="_blank" style="display:inline-block; padding:16px 44px; font-size:17px; font-weight:700; color:%3$s; text-decoration:none; border-radius:9999px;">Ver mi cotización</a>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px 28px 32px;">
                              <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%1$s; border:1px solid %9$s; border-radius:12px;">
                                <tr>
                                  <td style="padding:20px 24px;">
                                    <p style="margin:0 0 4px 0; font-size:12px; font-weight:700; letter-spacing:0.08em; text-transform:uppercase; color:%7$s;">Código</p>
                                    <p style="margin:0 0 16px 0; font-size:18px; font-weight:700; color:%3$s; font-family:%10$s;">%11$s</p>
                                    <p style="margin:0 0 4px 0; font-size:12px; font-weight:700; letter-spacing:0.08em; text-transform:uppercase; color:%7$s;">Clave de acceso</p>
                                    <p style="margin:0; font-size:24px; font-weight:700; color:%3$s; letter-spacing:0.14em; font-family:%10$s;">%12$s</p>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px 32px 32px;">
                              <p style="margin:0; font-size:14px; line-height:1.6; color:%7$s;">Esta cotización está vigente hasta el <strong style="color:%3$s;">%13$s</strong>. Cualquier duda, responde este correo.</p>
                            </td>
                          </tr>
                          <tr>
                            <td align="center" style="background-color:%1$s; padding:20px 24px; border-top:1px solid %9$s;">
                              <a href="%14$s" target="_blank" style="font-size:13px; color:%7$s; text-decoration:none;">webiados.com</a>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                SOFT, FUENTE, TINTA, LOGO_CID, LIMA, n, MUTED, u, PAPER, MONO, codigo, clave, vigencia, SITIO);
    }

    /** Versión en texto plano del mismo correo (obligatoria: sin ella varios filtros = spam). */
    static String buildText(String nombre, String url, String codigo, String clave, String vigencia) {
        return """
                Hola %s:

                Preparamos tu cotización. Puedes verla acá:
                %s

                Código: %s
                Clave de acceso: %s

                Adentro están las opciones con su detalle y sus valores. Puedes elegir la que
                prefieras desde la misma página.

                Esta cotización está vigente hasta el %s.

                Cualquier duda, responde este correo.

                Webiados
                https://webiados.com
                """.formatted(nombre, url, codigo, clave, vigencia);
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * @return un futuro con el id que Resend devolvió (o {@code null} si el envío falló) — para
     *         que {@code SelectionService} lo guarde en la {@code Selection} exacta sin bloquear
     *         la respuesta al cliente. {@code @Async} con {@link CompletableFuture} sigue
     *         corriendo en otro hilo igual que antes; la diferencia es que esta vez el llamador
     *         se puede enganchar al resultado sin tener que esperarlo.
     */
    @Async
    public CompletableFuture<String> notifySelection(Quote quote, QuoteOption option, SelectionKind kind) {
        try {
            String subject = kind == SelectionKind.UPGRADE
                    ? "Upgrade — Cotización %s — %s".formatted(quote.getCodigo(), quote.getClientName())
                    : "Cotización %s — %s eligió %s".formatted(
                            quote.getCodigo(), quote.getClientName(), option.getTitulo());

            String body = """
                    Cliente: %s
                    Email: %s
                    Código: %s
                    Opción elegida: %s
                    Precio: $%s %s
                    Tipo: %s
                    """.formatted(
                    quote.getClientName(),
                    quote.getClientEmail() != null ? quote.getClientEmail() : "—",
                    quote.getCodigo(),
                    option.getTitulo(),
                    option.getPrecio().toPlainString(),
                    option.getCurrency(),
                    kind == SelectionKind.UPGRADE ? "UPGRADE" : "SELECCIÓN INICIAL");

            String resendId = sendViaResend(props.mail().notifyTo(), null, subject, null, body, List.of());
            // Antes solo se registraba el fallo: "no hay error en el log" no es prueba de que se
            // mandó, es prueba de que no se lanzó una excepción — y un envío que falla sin lanzar
            // ninguna se vería exactamente igual. Con el código acá, "¿se avisó de esta?" se
            // responde buscando, no revisando el buzón de NOTIFY_TO.
            log.info("Notificación interna de selección enviada para cotización {} (resend id {})",
                    quote.getCodigo(), resendId);
            return CompletableFuture.completedFuture(resendId);
        } catch (Exception ex) {
            log.error("Error enviando email de notificación para cotización {}", quote.getCodigo(), ex);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Aviso interno de {@link StaleQuoteAlertJob} — cotización sin respuesta hace {@code dias}. */
    @Async
    public void notifyStale(Quote quote, long dias) {
        try {
            String subject = "Sin respuesta hace %d días — %s (%s)"
                    .formatted(dias, quote.getClientName(), quote.getCodigo());
            String body = """
                    Cliente: %s
                    Email: %s
                    Código: %s
                    Sin respuesta hace: %d días
                    """.formatted(
                    quote.getClientName(),
                    quote.getClientEmail() != null ? quote.getClientEmail() : "—",
                    quote.getCodigo(),
                    dias);

            sendViaResend(props.mail().notifyTo(), null, subject, null, body, List.of());
            log.info("Aviso de cotización sin respuesta enviado para cotización {}", quote.getCodigo());
        } catch (Exception ex) {
            log.error("Error enviando aviso de cotización sin respuesta para {}", quote.getCodigo(), ex);
        }
    }

    /**
     * Aviso interno de {@link com.webiados.cotizaciones.web.ResendWebhookController} — un
     * correo que YA se había aceptado rebotó de verdad. Mismo mecanismo que las otras dos
     * alertas internas, no una construida desde cero: le llega a quien puede llamar por
     * teléfono, con lo que necesita para hacerlo.
     */
    @Async
    public void notifyBounce(Quote quote, String motivo) {
        try {
            String subject = "Rebotó el correo — %s (%s)".formatted(quote.getClientName(), quote.getCodigo());
            String body = """
                    Cliente: %s
                    Email: %s
                    Código: %s
                    Motivo del rebote: %s

                    El correo se había aceptado al enviarlo, pero Resend avisó que no llegó de
                    verdad. Esta cotización probablemente nunca la vio el cliente — vale la pena
                    llamarlo en vez de esperar una respuesta que no va a llegar por este canal.
                    """.formatted(
                    quote.getClientName(),
                    quote.getClientEmail() != null ? quote.getClientEmail() : "—",
                    quote.getCodigo(),
                    motivo != null ? motivo : "—");

            sendViaResend(props.mail().notifyTo(), null, subject, null, body, List.of());
            log.info("Aviso de rebote enviado para cotización {}", quote.getCodigo());
        } catch (Exception ex) {
            log.error("Error enviando aviso de rebote para {}", quote.getCodigo(), ex);
        }
    }

    /**
     * Aviso interno de que el aviso de una SELECCIÓN rebotó — no de que la cotización nunca
     * llegó. Mensaje deliberadamente distinto de {@link #notifyBounce}: la aceptación del
     * cliente NO se perdió, ya está guardada; lo que se perdió es que alguien se enterara a
     * tiempo. La acción acá no es reintentar el correo — es que una persona llame. Con eso, el
     * que reciba el aviso sabe qué hacer sin tener que interpretarlo.
     */
    @Async
    public void notifySelectionBounce(Quote quote, QuoteOption option, String motivo) {
        try {
            String subject = "Un cliente aceptó y no pudimos avisar — %s (%s)"
                    .formatted(quote.getClientName(), quote.getCodigo());
            String body = """
                    Cliente: %s
                    Email: %s
                    Código: %s
                    Opción aceptada: %s
                    Motivo del rebote del aviso: %s

                    El cliente SÍ aceptó — está guardado en el sistema, no se perdió nada de eso.
                    Lo que rebotó fue el aviso interno de que había pasado. Nadie se enteró a
                    tiempo por este canal: hay que entrar al panel y llamarlo.
                    """.formatted(
                    quote.getClientName(),
                    quote.getClientEmail() != null ? quote.getClientEmail() : "—",
                    quote.getCodigo(),
                    option.getTitulo(),
                    motivo != null ? motivo : "—");

            sendViaResend(props.mail().notifyTo(), null, subject, null, body, List.of());
            log.info("Aviso de rebote de selección enviado para cotización {}", quote.getCodigo());
        } catch (Exception ex) {
            log.error("Error enviando aviso de rebote de selección para {}", quote.getCodigo(), ex);
        }
    }

    // --- Resend ---------------------------------------------------------------------------

    private String sendViaResend(String to, String replyTo, String subject, String html, String text,
                                  List<Map<String, String>> attachments) {
        requireApiKey();

        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("from", props.mail().from());
        payload.put("to", List.of(to));
        if (replyTo != null && !replyTo.isBlank()) {
            payload.put("reply_to", replyTo);
        }
        payload.put("subject", subject);
        if (html != null) {
            payload.put("html", html);
        }
        if (text != null) {
            payload.put("text", text);
        }
        if (!attachments.isEmpty()) {
            payload.put("attachments", attachments);
        }

        JsonNode response;
        try {
            response = http.post()
                    .uri(resendUrl)
                    .header("Authorization", "Bearer " + props.mail().apiKey())
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo enviar el correo por Resend: " + ex.getMessage(), ex);
        }
        if (response == null || !response.hasNonNull("id")) {
            throw new IllegalStateException(
                    "Resend aceptó la petición pero no devolvió un id de envío — respuesta: " + response);
        }
        return response.get("id").asText();
    }

    private void requireApiKey() {
        String apiKey = props.mail().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "El envío de correo requiere la llave de Resend (RESEND_API_KEY) y no está "
                            + "configurada. Pídesela a Felipe y ponla en Railway.");
        }
    }

    private String loadLogoBase64() {
        String cached = logoBase64;
        if (cached != null) {
            return cached;
        }
        try {
            byte[] bytes = new ClassPathResource(LOGO_RESOURCE).getContentAsByteArray();
            String encoded = Base64.getEncoder().encodeToString(bytes);
            logoBase64 = encoded;
            return encoded;
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo leer el logo del correo (" + LOGO_RESOURCE + ")", ex);
        }
    }
}
