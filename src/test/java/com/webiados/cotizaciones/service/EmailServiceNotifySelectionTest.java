package com.webiados.cotizaciones.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.domain.SelectionKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "No hay errores en los logs" no es prueba de que la notificación interna se mandó — es prueba
 * de que no se registró un error, y si el envío fallara sin lanzar excepción se vería idéntico.
 * Por eso el éxito también se registra, con el código de la cotización, para poder responder
 * "¿se avisó de esta?" sin adivinar ni tener que revisar el buzón de NOTIFY_TO.
 */
class EmailServiceNotifySelectionTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger emailServiceLogger;
    private HttpServer resend;

    @BeforeEach
    void attachAppender() {
        logs = new ListAppender<>();
        logs.start();
        emailServiceLogger = (Logger) LoggerFactory.getLogger(EmailService.class);
        emailServiceLogger.addAppender(logs);
    }

    @AfterEach
    void detachAppender() {
        emailServiceLogger.detachAppender(logs);
        if (resend != null) {
            resend.stop(0);
        }
    }

    private static Quote quote() {
        return new Quote(UUID.randomUUID(), "ab12cd34ef", "hash", "clave",
                "Cliente de prueba", "cliente@ejemplo.cl", null, Instant.now(),
                Instant.now().plusSeconds(86400), null, null, null);
    }

    private static QuoteOption opcion() {
        return new QuoteOption(UUID.randomUUID(), 0, "Kit Agenda", null,
                BigDecimal.valueOf(790000), BigDecimal.valueOf(45000), "CLP", false, List.of());
    }

    /** Resend simulado: acepta todo con un id, sin mirar el cuerpo. */
    private HttpServer resendQueAcepta() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            byte[] body = "{\"id\":\"re_test_123\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    /** Resend simulado: rechaza todo, como un SMTP caído en la versión anterior de este test. */
    private HttpServer resendQueRechaza() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            byte[] body = "{\"message\":\"invalid api key\"}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private EmailService servicioApuntandoA(HttpServer server, String notifyTo) {
        Duration t = Duration.ofSeconds(5);
        var settings = ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(t).withReadTimeout(t);
        var http = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/emails";
        // Constructor de paquete: este test vive en el mismo paquete que EmailService a propósito.
        return new EmailService(propsCon(notifyTo), http, url);
    }

    @Test
    void si_el_envio_funciona_queda_una_linea_de_exito_con_el_codigo() throws IOException {
        resend = resendQueAcepta();
        var service = servicioApuntandoA(resend, "contacto@webiados.com");
        var quote = quote();

        // notifySelection es @Async en el bean real; llamado directo sobre el objeto (sin proxy
        // de Spring en este test unitario) corre síncrono, así que no hace falta esperar.
        service.notifySelection(quote, opcion(), SelectionKind.INITIAL);

        assertThat(logs.list)
                .as("sin esto, un envío que falla sin lanzar excepción se ve igual que uno exitoso")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains(quote.getCodigo())
                            .containsIgnoringCase("notifica");
                });
    }

    @Test
    void si_el_envio_falla_sigue_registrando_el_error_como_antes() throws IOException {
        resend = resendQueRechaza();
        var service = servicioApuntandoA(resend, "contacto@webiados.com");
        var quote = quote();

        service.notifySelection(quote, opcion(), SelectionKind.INITIAL);

        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains(quote.getCodigo());
        });
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.INFO);
    }

    private static AppProperties propsCon(String notifyTo) {
        return new AppProperties(null, null,
                new AppProperties.Mail("cotizaciones@webiados.com", notifyTo, "llave-de-prueba", null,
                        "http://ignorado-en-este-test"), // sobrescrito por resendUrl en el constructor de EmailService
                null, null, null, null, null);
    }
}
