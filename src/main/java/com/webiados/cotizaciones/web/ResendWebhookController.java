package com.webiados.cotizaciones.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.service.QuoteService;
import com.webiados.cotizaciones.service.SelectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Recibe los avisos de Resend (rebote, queja) por webhook. Endpoint PÚBLICO — no hay sesión de
 * admin ni de cliente que lo llame — así que la firma se verifica siempre, sin excepción: esto
 * marca cotizaciones como no entregadas, y si cualquiera pudiera llamarlo, cualquiera podría
 * ensuciar el estado de las cotizaciones de un cliente real.
 *
 * <p>Firma Svix (el proveedor de webhooks que usa Resend), verificada a mano — no se agregó una
 * librería nueva para un HMAC-SHA256, que ya se usa en otras partes de este servicio.
 *
 * <p><strong>El latido de esto es distinto al de un cron.</strong> No hay "corrida sin
 * candidatas" que loguear: es un endpoint que espera tráfico. La forma de distinguir "no hubo
 * rebotes" de "el webhook dejó de recibir tráfico" es que TODA petición con firma válida se
 * loguea, calce o no con una cotización — el volumen de esas líneas en un mes es la señal de que
 * el endpoint sigue vivo, no solo los rebotes que sí calzaron.
 */
@RestController
@RequestMapping("/api/webhooks/resend")
public class ResendWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);

    // Tolerancia contra un replay de una petición vieja capturada — mismo criterio que usan las
    // librerías oficiales de Svix.
    private static final long TOLERANCIA_SEGUNDOS = 300;

    private final QuoteService quoteService;
    private final SelectionService selectionService;
    private final AppProperties props;
    private final ObjectMapper json = new ObjectMapper();

    public ResendWebhookController(QuoteService quoteService, SelectionService selectionService,
                                    AppProperties props) {
        this.quoteService = quoteService;
        this.selectionService = selectionService;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<Void> recibir(
            @RequestHeader("svix-id") String svixId,
            @RequestHeader("svix-timestamp") String svixTimestamp,
            @RequestHeader("svix-signature") String svixSignature,
            @RequestBody String rawBody) {

        String secret = props.mail().webhookSecret();
        if (secret == null || secret.isBlank()) {
            // Fail closed: sin secreto configurado, no hay forma de distinguir a Resend de
            // cualquiera — se rechaza todo, no se procesa "por si acaso".
            log.error("Webhook de Resend llamado pero RESEND_WEBHOOK_SECRET no está configurado — rechazado");
            return ResponseEntity.status(503).build();
        }

        if (!firmaValida(secret, svixId, svixTimestamp, svixSignature, rawBody)) {
            log.warn("Webhook de Resend con firma inválida (svix-id={})", svixId);
            return ResponseEntity.status(401).build();
        }

        JsonNode event;
        try {
            event = json.readTree(rawBody);
        } catch (Exception ex) {
            log.warn("Webhook de Resend con firma válida pero cuerpo no es JSON legible (svix-id={})", svixId);
            return ResponseEntity.status(400).build();
        }

        String tipo = event.path("type").asText("");
        JsonNode data = event.path("data");
        String emailId = data.path("email_id").asText(null);

        // Se loguea SIEMPRE, calce o no con una cotización, y para cualquier tipo de evento —
        // es lo que deja el rastro de que el endpoint sigue recibiendo tráfico real.
        log.info("Webhook de Resend recibido: type={} email_id={}", tipo, emailId);

        if (!"email.bounced".equals(tipo) && !"email.complained".equals(tipo)) {
            return ResponseEntity.ok().build();
        }
        if (emailId == null || emailId.isBlank()) {
            log.warn("Webhook de Resend tipo {} sin email_id — no se puede calzar con ninguna cotización", tipo);
            return ResponseEntity.ok().build();
        }

        String motivo = motivoDe(tipo, data);
        // Un email_id es de un solo envío — o es el correo al cliente, o es un aviso interno de
        // selección, nunca los dos. Se prueban los dos porque acá no hay forma de saber cuál es
        // antes de buscar; solo uno de los dos va a calzar.
        boolean calzo = quoteService.recordBounce(emailId, motivo)
                || selectionService.recordBounce(emailId, motivo);
        if (!calzo) {
            // No es un error: puede ser un correo interno de otro tipo (sin respuesta, por
            // ejemplo), que hoy no guarda su id. Se loguea igual, para que el volumen de "no
            // calzó" también quede visible.
            log.info("Webhook de Resend {} (email_id={}) no calzó con nada conocido", tipo, emailId);
        }
        return ResponseEntity.ok().build();
    }

    /** Extrae un motivo legible del evento, sin asumir un schema exacto que no está confirmado. */
    private static String motivoDe(String tipo, JsonNode data) {
        JsonNode bounce = data.path("bounce");
        if (bounce.has("message")) {
            return bounce.get("message").asText();
        }
        if (bounce.has("type")) {
            return "Rebote tipo " + bounce.get("type").asText();
        }
        return "email.complained".equals(tipo) ? "Marcado como spam por el destinatario" : "Rebotó";
    }

    private static boolean firmaValida(String secret, String svixId, String svixTimestamp,
                                        String svixSignature, String body) {
        try {
            long timestamp = Long.parseLong(svixTimestamp);
            long ahora = Instant.now().getEpochSecond();
            if (Math.abs(ahora - timestamp) > TOLERANCIA_SEGUNDOS) {
                return false;
            }

            String secretSinPrefijo = secret.startsWith("whsec_") ? secret.substring(6) : secret;
            byte[] secretBytes = Base64.getDecoder().decode(secretSinPrefijo);

            String contenidoFirmado = svixId + "." + svixTimestamp + "." + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] esperada = mac.doFinal(contenidoFirmado.getBytes(StandardCharsets.UTF_8));
            String esperadaB64 = Base64.getEncoder().encodeToString(esperada);

            // svix-signature trae una o más firmas separadas por espacio, cada una "v1,<base64>".
            List<String> firmas = List.of(svixSignature.trim().split("\\s+"));
            for (String firma : firmas) {
                String[] partes = firma.split(",", 2);
                if (partes.length == 2 && "v1".equals(partes[0]) && constantTimeEquals(partes[1], esperadaB64)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Comparación en tiempo constante — una firma no se valida con == ni equals(). */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < ba.length; i++) {
            diff |= ba[i] ^ bb[i];
        }
        return diff == 0;
    }
}
