package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.domain.SelectionKind;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
    // Logo blanco, para el encabezado sobre fondo tinta. Va incrustado en el correo (cid), NO como
    // URL externa: el original del sitio es .webp, y WebP no lo soportan todos los clientes de
    // correo (Outlook no lo soporta; el proxy de imágenes de Gmail lo mostró corrupto en vez de
    // renderizarlo). PNG incrustado no depende del sitio ni del formato.
    private static final String LOGO_CID = "logo-webiados";
    private static final String LOGO_RESOURCE = "email/logo-webiados-white.png";
    // Pila de fuentes del sistema: NO se pueden usar fuentes web (Bricolage) en un correo.
    private static final String FUENTE =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
    private static final String MONO =
            "'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace";

    private final JavaMailSender mailSender;
    private final AppProperties props;

    public EmailService(JavaMailSender mailSender, AppProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    /**
     * Le manda la cotización al cliente: el link y la clave de acceso, con la identidad de
     * Webiados. Va como HTML (tablas + estilos en línea, para que Gmail no lo rompa) y con una
     * versión en texto plano en el mismo correo (sin ella, varios filtros lo mandan a spam).
     *
     * <p>A diferencia de {@link #notifySelection}, este método <strong>no es async y propaga la
     * excepción</strong>: si el correo no sale, la cotización no puede quedar marcada como enviada.
     */
    public void sendQuoteToClient(Quote quote, String url) {
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

        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(props.mail().from());
            helper.setTo(quote.getClientEmail());
            helper.setReplyTo(props.mail().notifyTo());
            helper.setSubject(subject);
            helper.setText(text, html); // (texto plano, html) → multipart/alternative
            helper.addInline(LOGO_CID, new ClassPathResource(LOGO_RESOURCE), "image/png");
            mailSender.send(mime);
        } catch (MessagingException ex) {
            // No debería pasar (armamos el mensaje nosotros), pero si pasa, que la cotización
            // NO quede marcada como enviada: se propaga.
            throw new IllegalStateException("No se pudo construir el correo de la cotización", ex);
        }

        log.info("Cotización {} enviada por correo a {}", codigo, quote.getClientEmail());
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

    @Async
    public void notifySelection(Quote quote, QuoteOption option, SelectionKind kind) {
        try {
            String subject = kind == SelectionKind.UPGRADE
                    ? "⬆️ Upgrade — Cotización %s — %s".formatted(quote.getCodigo(), quote.getClientName())
                    : "✅ Cotización %s — %s eligió %s".formatted(
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

            var message = new SimpleMailMessage();
            message.setFrom(props.mail().from());
            message.setTo(props.mail().notifyTo());
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            // Antes solo se registraba el fallo: "no hay error en el log" no es prueba de que se
            // mandó, es prueba de que no se lanzó una excepción — y un envío que falla sin lanzar
            // ninguna se vería exactamente igual. Con el código acá, "¿se avisó de esta?" se
            // responde buscando, no revisando el buzón de NOTIFY_TO.
            log.info("Notificación interna de selección enviada para cotización {}", quote.getCodigo());
        } catch (Exception ex) {
            log.error("Error enviando email de notificación para cotización {}", quote.getCodigo(), ex);
        }
    }

    /** Aviso interno de {@link StaleQuoteAlertJob} — cotización sin respuesta hace {@code dias}. */
    @Async
    public void notifyStale(Quote quote, long dias) {
        try {
            String subject = "⏳ Sin respuesta hace %d días — %s (%s)"
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

            var message = new SimpleMailMessage();
            message.setFrom(props.mail().from());
            message.setTo(props.mail().notifyTo());
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Aviso de cotización sin respuesta enviado para cotización {}", quote.getCodigo());
        } catch (Exception ex) {
            log.error("Error enviando aviso de cotización sin respuesta para {}", quote.getCodigo(), ex);
        }
    }
}
