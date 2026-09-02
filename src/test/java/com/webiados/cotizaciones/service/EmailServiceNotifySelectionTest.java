package com.webiados.cotizaciones.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.domain.SelectionKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * "No hay errores en los logs" no es prueba de que la notificación interna se mandó — es prueba
 * de que no se registró un error, y si el envío fallara sin lanzar excepción se vería idéntico.
 * Por eso el éxito también se registra, con el código de la cotización, para poder responder
 * "¿se avisó de esta?" sin adivinar ni tener que revisar el buzón de NOTIFY_TO.
 */
class EmailServiceNotifySelectionTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger emailServiceLogger;

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

    @Test
    void si_el_envio_funciona_queda_una_linea_de_exito_con_el_codigo() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        var service = new EmailService(mailSender, propsCon("contacto@webiados.com"));
        var quote = quote();

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
    void si_el_envio_falla_sigue_registrando_el_error_como_antes() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("SMTP caído")).when(mailSender).send(any(org.springframework.mail.SimpleMailMessage.class));
        var service = new EmailService(mailSender, propsCon("contacto@webiados.com"));
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
                new AppProperties.Mail("cotizaciones@webiados.com", notifyTo),
                null, null, null, null, null);
    }
}
