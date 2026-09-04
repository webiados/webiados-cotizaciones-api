package com.webiados.cotizaciones.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.db.TestPostgres;
import com.webiados.cotizaciones.dto.admin.CreateQuoteRequest;
import com.webiados.cotizaciones.dto.admin.OptionRequest;
import com.webiados.cotizaciones.repo.QuoteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Apagada por defecto (regla 13). Y la primera vez que se activa, siembra el backlog viejo en
 * silencio — sin eso, activarla un día con cotizaciones de meses acumuladas manda veinte avisos
 * de golpe, y una alerta que nace así nace ignorada.
 */
@SpringBootTest
@Import(StaleQuoteAlertJobIT.PostgresConfig.class)
@TestPropertySource(properties = {
        "app.admin.bootstrap-email=",
        "app.admin.bootstrap-password=",
        "app.quote.public-base-url=https://webiados.com/cotizacion",
        "app.mail.api-key=llave-de-prueba",
})
// "primera vez" depende de si ALGUNA cotización en toda la tabla ya tiene staleAlertedAt — cada
// test necesita su propia base, no la que dejaron los anteriores en el mismo contexto cacheado.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StaleQuoteAlertJobIT {

    @TestConfiguration
    static class PostgresConfig {
        @Bean
        DataSource dataSource() {
            return TestPostgres.freshDatabase();
        }
    }

    /** Resend simulado, arriba antes de que el contexto de Spring construya EmailService. */
    private static HttpServer resend;
    private static AtomicInteger correosEnviados;

    // Un solo server para toda la clase, no uno por contexto: @DirtiesContext reconstruye el
    // ApplicationContext después de cada test (por el estado global de "primera vez"), y
    // @DynamicPropertySource se invoca en cada reconstrucción — sin este guard, cada test
    // dejaría un HttpServer más corriendo sin nadie que lo pare.
    @DynamicPropertySource
    static synchronized void resendSimulado(DynamicPropertyRegistry registry) throws IOException {
        if (resend == null) {
            correosEnviados = new AtomicInteger();
            resend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            resend.createContext("/emails", exchange -> {
                correosEnviados.incrementAndGet();
                byte[] body = "{\"id\":\"re_test_stale\"}".getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            resend.start();
        }
        registry.add("app.mail.api-url",
                () -> "http://127.0.0.1:" + resend.getAddress().getPort() + "/emails");
    }

    @Autowired
    QuoteService quoteService;

    @Autowired
    QuoteRepository quoteRepo;

    @Autowired
    EmailService emailService;

    @MockBean
    LeadClient leadClient;

    @MockBean
    PricingClient pricingClient;

    @BeforeEach
    void resetearContador() {
        correosEnviados.set(0);
    }

    /** {@code creada} nace con {@code createdAt} bien atrás, para poder fechar el envío en el pasado. */
    private static CreateQuoteRequest cotizacionDe(String cliente) {
        return new CreateQuoteRequest(cliente, "cliente@ejemplo.cl", null, null, null, null,
                Instant.now().minus(90, ChronoUnit.DAYS),
                List.of(new OptionRequest("Opción", "descripción", BigDecimal.valueOf(100000), null,
                        "CLP", false, List.of(), null, null)));
    }

    private StaleQuoteAlertJob jobCon(boolean enabled, int days) {
        return new StaleQuoteAlertJob(quoteRepo, emailService,
                new AppProperties(null, null, null, null, null, null, null,
                        new AppProperties.StaleAlert(enabled, days)));
    }

    /** {@code notifyStale} es {@code @Async} en el bean real de Spring: espera a que el correo
     *  simulado reciba la petición, sondeando en vez de asumir un {@code Thread.sleep} fijo. */
    private void esperarCorreos(int esperados) throws InterruptedException {
        long limite = System.currentTimeMillis() + 3000;
        while (correosEnviados.get() < esperados && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
        }
        assertThat(correosEnviados.get()).isEqualTo(esperados);
    }

    @Test
    @DisplayName("apagada por defecto: no toca ninguna cotización aunque haya candidatas")
    void apagadaNoHaceNada() throws InterruptedException {
        var creada = quoteService.create(cotizacionDe("Cliente Apagado"));
        quoteService.markSentManually(creada.id(), Instant.now().minus(30, ChronoUnit.DAYS));

        jobCon(false, 7).check(Instant.now());

        assertThat(quoteRepo.findByCodigo(creada.codigo()).orElseThrow().getStaleAlertedAt()).isNull();
        Thread.sleep(500);
        assertThat(correosEnviados.get()).isZero();
    }

    @Test
    @DisplayName("primera activación: siembra las viejas en silencio, sin mandar ningún correo")
    void primeraActivacionSiembraEnSilencio() throws InterruptedException {
        var vieja1 = quoteService.create(cotizacionDe("Cliente Viejo Uno"));
        quoteService.markSentManually(vieja1.id(), Instant.now().minus(30, ChronoUnit.DAYS));
        var vieja2 = quoteService.create(cotizacionDe("Cliente Viejo Dos"));
        quoteService.markSentManually(vieja2.id(), Instant.now().minus(20, ChronoUnit.DAYS));

        jobCon(true, 7).check(Instant.now());

        assertThat(quoteRepo.findByCodigo(vieja1.codigo()).orElseThrow().getStaleAlertedAt()).isNotNull();
        assertThat(quoteRepo.findByCodigo(vieja2.codigo()).orElseThrow().getStaleAlertedAt()).isNotNull();
        Thread.sleep(500);
        assertThat(correosEnviados.get()).isZero();
    }

    @Test
    @DisplayName("después de sembrar, una que se vuelve vieja de verdad sí avisa — una sola vez")
    void despuesDeLaSiembraAvisaDeVerdadYUnaSolaVez() throws InterruptedException {
        var vieja = quoteService.create(cotizacionDe("Cliente Ya Sembrado"));
        quoteService.markSentManually(vieja.id(), Instant.now().minus(30, ChronoUnit.DAYS));
        var job = jobCon(true, 7);
        job.check(Instant.now()); // siembra, sin avisar

        var nueva = quoteService.create(cotizacionDe("Cliente Nuevo Sin Respuesta"));
        quoteService.markSentManually(nueva.id(), Instant.now().minus(10, ChronoUnit.DAYS));

        job.check(Instant.now());
        job.check(Instant.now()); // corre otra vez el mismo día — no debe reavisar

        assertThat(quoteRepo.findByCodigo(nueva.codigo()).orElseThrow().getStaleAlertedAt()).isNotNull();
        esperarCorreos(1);
    }

    /**
     * Un vigilante que solo habla cuando encuentra algo no se distingue de uno muerto: si pasan
     * semanas sin correo, esto tenía que poder responder si es que no hubo vencidas o si el job
     * dejó de correr. Antes de este test, la corrida sin candidatas no dejaba ningún rastro.
     */
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void enganchaElLog() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(StaleQuoteAlertJob.class)).addAppender(logAppender);
    }

    @AfterEach
    void desenganchaElLog() {
        ((Logger) LoggerFactory.getLogger(StaleQuoteAlertJob.class)).detachAppender(logAppender);
    }

    @Test
    @DisplayName("corrida sin candidatas deja constancia en el log, no queda en silencio")
    void corridaSinCandidatasDejaConstancia() {
        jobCon(true, 7).check(Instant.now());

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("0 candidatas"));
    }

    @Test
    @DisplayName("una cotización enviada hace menos días que el plazo no se toca todavía")
    void recienEnviadaNoSeToca() throws InterruptedException {
        var reciente = quoteService.create(cotizacionDe("Cliente Recién Enviado"));
        quoteService.markSentManually(reciente.id(), Instant.now().minus(2, ChronoUnit.DAYS));

        jobCon(true, 7).check(Instant.now());

        assertThat(quoteRepo.findByCodigo(reciente.codigo()).orElseThrow().getStaleAlertedAt()).isNull();
        Thread.sleep(500);
        assertThat(correosEnviados.get()).isZero();
    }
}
