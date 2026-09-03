package com.webiados.cotizaciones.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

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

    @Autowired
    QuoteService quoteService;

    @Autowired
    QuoteRepository quoteRepo;

    @Autowired
    EmailService emailService;

    @MockBean
    JavaMailSender mailSender;

    @MockBean
    LeadClient leadClient;

    @MockBean
    PricingClient pricingClient;

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

    @Test
    @DisplayName("apagada por defecto: no toca ninguna cotización aunque haya candidatas")
    void apagadaNoHaceNada() {
        var creada = quoteService.create(cotizacionDe("Cliente Apagado"));
        quoteService.markSentManually(creada.id(), Instant.now().minus(30, ChronoUnit.DAYS));

        jobCon(false, 7).check(Instant.now());

        assertThat(quoteRepo.findByCodigo(creada.codigo()).orElseThrow().getStaleAlertedAt()).isNull();
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("primera activación: siembra las viejas en silencio, sin mandar ningún correo")
    void primeraActivacionSiembraEnSilencio() {
        var vieja1 = quoteService.create(cotizacionDe("Cliente Viejo Uno"));
        quoteService.markSentManually(vieja1.id(), Instant.now().minus(30, ChronoUnit.DAYS));
        var vieja2 = quoteService.create(cotizacionDe("Cliente Viejo Dos"));
        quoteService.markSentManually(vieja2.id(), Instant.now().minus(20, ChronoUnit.DAYS));

        jobCon(true, 7).check(Instant.now());

        assertThat(quoteRepo.findByCodigo(vieja1.codigo()).orElseThrow().getStaleAlertedAt()).isNotNull();
        assertThat(quoteRepo.findByCodigo(vieja2.codigo()).orElseThrow().getStaleAlertedAt()).isNotNull();
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("después de sembrar, una que se vuelve vieja de verdad sí avisa — una sola vez")
    void despuesDeLaSiembraAvisaDeVerdadYUnaSolaVez() {
        var vieja = quoteService.create(cotizacionDe("Cliente Ya Sembrado"));
        quoteService.markSentManually(vieja.id(), Instant.now().minus(30, ChronoUnit.DAYS));
        var job = jobCon(true, 7);
        job.check(Instant.now()); // siembra, sin avisar

        var nueva = quoteService.create(cotizacionDe("Cliente Nuevo Sin Respuesta"));
        quoteService.markSentManually(nueva.id(), Instant.now().minus(10, ChronoUnit.DAYS));

        job.check(Instant.now());
        job.check(Instant.now()); // corre otra vez el mismo día — no debe reavisar

        assertThat(quoteRepo.findByCodigo(nueva.codigo()).orElseThrow().getStaleAlertedAt()).isNotNull();
        verify(mailSender, timeout(3000).times(1)).send(any(SimpleMailMessage.class));
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
    void recienEnviadaNoSeToca() {
        var reciente = quoteService.create(cotizacionDe("Cliente Recién Enviado"));
        quoteService.markSentManually(reciente.id(), Instant.now().minus(2, ChronoUnit.DAYS));

        jobCon(true, 7).check(Instant.now());

        assertThat(quoteRepo.findByCodigo(reciente.codigo()).orElseThrow().getStaleAlertedAt()).isNull();
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }
}
