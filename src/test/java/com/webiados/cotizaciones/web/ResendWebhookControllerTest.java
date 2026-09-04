package com.webiados.cotizaciones.web;

import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint PÚBLICO que marca cotizaciones como no entregadas — si cualquiera pudiera llamarlo,
 * cualquiera podría ensuciar el estado de las cotizaciones de un cliente real. Por eso la firma
 * se prueba en las dos direcciones: una firma real (calculada como lo haría Resend/Svix) se
 * acepta, y cualquier variación de esa misma firma se rechaza.
 */
class ResendWebhookControllerTest {

    private static final String SECRET = "whsec_" + Base64.getEncoder()
            .encodeToString("una-llave-de-prueba-de-32-bytes".getBytes(StandardCharsets.UTF_8));

    private final QuoteService quoteService = mock(QuoteService.class);

    private MockMvc mockMvcCon(String webhookSecret) {
        var props = new AppProperties(null, null,
                new AppProperties.Mail(null, null, null, webhookSecret, null),
                null, null, null, null, null);
        return MockMvcBuilders.standaloneSetup(new ResendWebhookController(quoteService, props)).build();
    }

    /** Firma un cuerpo exactamente como lo hace Svix — el mismo algoritmo que verifica el controller. */
    private static String firmar(String secret, String svixId, String svixTimestamp, String body) throws Exception {
        String secretSinPrefijo = secret.startsWith("whsec_") ? secret.substring(6) : secret;
        byte[] secretBytes = Base64.getDecoder().decode(secretSinPrefijo);
        String contenido = svixId + "." + svixTimestamp + "." + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
        byte[] firma = mac.doFinal(contenido.getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(firma);
    }

    @Test
    void firmaValidaConEmailBouncedQueCalzaMarcaLaCotizacion() throws Exception {
        when(quoteService.recordBounce(anyString(), anyString())).thenReturn(true);
        String svixId = "msg_test1";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = """
                {"type":"email.bounced","data":{"email_id":"re_abc123","to":["cliente@ejemplo.cl"],
                "bounce":{"type":"Permanent","message":"mailbox does not exist"}}}""";
        String firma = firmar(SECRET, svixId, timestamp, body);

        mockMvcCon(SECRET).perform(post("/api/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", svixId)
                        .header("svix-timestamp", timestamp)
                        .header("svix-signature", firma)
                        .content(body))
                .andExpect(status().isOk());

        verify(quoteService).recordBounce("re_abc123", "mailbox does not exist");
    }

    @Test
    void firmaInvalidaSeRechazaConCuatrocientosUnoYNoTocaNada() throws Exception {
        String svixId = "msg_test2";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"re_abc123\"}}";

        mockMvcCon(SECRET).perform(post("/api/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", svixId)
                        .header("svix-timestamp", timestamp)
                        .header("svix-signature", "v1,firma-inventada-que-no-calza")
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(quoteService, never()).recordBounce(anyString(), anyString());
    }

    @Test
    void cuerpoAlteradoDespuesDeFirmarloSeRechaza() throws Exception {
        String svixId = "msg_test3";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String bodyOriginal = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"re_abc123\"}}";
        String firma = firmar(SECRET, svixId, timestamp, bodyOriginal);
        // Mismo id, firma calculada sobre el original, pero el cuerpo que llega es otro —
        // como si alguien interceptara la petición y le cambiara el email_id.
        String bodyAlterado = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"re_OTRO_id\"}}";

        mockMvcCon(SECRET).perform(post("/api/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", svixId)
                        .header("svix-timestamp", timestamp)
                        .header("svix-signature", firma)
                        .content(bodyAlterado))
                .andExpect(status().isUnauthorized());

        verify(quoteService, never()).recordBounce(anyString(), anyString());
    }

    @Test
    void timestampMuyViejoSeRechazaAunqueLaFirmaCalce() throws Exception {
        String svixId = "msg_test4";
        String timestampViejo = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());
        String body = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"re_abc123\"}}";
        String firma = firmar(SECRET, svixId, timestampViejo, body);

        mockMvcCon(SECRET).perform(post("/api/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", svixId)
                        .header("svix-timestamp", timestampViejo)
                        .header("svix-signature", firma)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sinSecretoConfiguradoRechazaTodoEnVezDeProcesarPorSiAcaso() throws Exception {
        String svixId = "msg_test5";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"re_abc123\"}}";

        mockMvcCon(null).perform(post("/api/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", svixId)
                        .header("svix-timestamp", timestamp)
                        .header("svix-signature", "v1,cualquier-cosa")
                        .content(body))
                .andExpect(status().is5xxServerError());

        verify(quoteService, never()).recordBounce(anyString(), anyString());
    }

    @Test
    void eventoQueNoCalzaConNingunaCotizacionRespondeOkIgual() throws Exception {
        when(quoteService.recordBounce(anyString(), anyString())).thenReturn(false);
        String svixId = "msg_test6";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        // email_id de un correo interno (a NOTIFY_TO), sin cotización detrás.
        String body = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"re_interno\"}}";
        String firma = firmar(SECRET, svixId, timestamp, body);

        mockMvcCon(SECRET).perform(post("/api/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", svixId)
                        .header("svix-timestamp", timestamp)
                        .header("svix-signature", firma)
                        .content(body))
                .andExpect(status().isOk()); // no es un error del sistema que no calce
    }

    @Test
    void tipoDeEventoQueNoNosImportaSeIgnoraSinTocarNada() throws Exception {
        String svixId = "msg_test7";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"re_abc123\"}}";
        String firma = firmar(SECRET, svixId, timestamp, body);

        mockMvcCon(SECRET).perform(post("/api/webhooks/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("svix-id", svixId)
                        .header("svix-timestamp", timestamp)
                        .header("svix-signature", firma)
                        .content(body))
                .andExpect(status().isOk());

        verify(quoteService, never()).recordBounce(anyString(), anyString());
    }
}
