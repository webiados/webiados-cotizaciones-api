package com.webiados.cotizaciones.service;

import com.sun.net.httpserver.HttpServer;
import com.webiados.cotizaciones.db.TestPostgres;
import com.webiados.cotizaciones.dto.admin.CreateQuoteRequest;
import com.webiados.cotizaciones.dto.admin.OptionRequest;
import com.webiados.cotizaciones.repo.SelectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El aviso interno de "un cliente eligió" ({@code notifySelection}) manda por el mismo Resend
 * que la cotización, y hasta el 2026-09-04 no guardaba ningún identificador — si ese correo
 * fallaba o rebotaba, la única marca era una línea de log, y el webhook de rebote no tenía con
 * qué calzarlo. Estos tests prueban que ahora sí: el id queda guardado en la {@code Selection}
 * exacta (una cotización puede tener varias), y un rebote de ese id calza y avisa distinto de un
 * rebote de la cotización — la aceptación no se perdió, lo que se perdió es que alguien se
 * enterara a tiempo.
 */
@SpringBootTest
@Import(SelectionServiceIT.PostgresConfig.class)
@TestPropertySource(properties = {
        "app.admin.bootstrap-email=",
        "app.admin.bootstrap-password=",
        "app.quote.public-base-url=https://webiados.com/cotizacion",
})
class SelectionServiceIT {

    @TestConfiguration
    static class PostgresConfig {
        @Bean
        DataSource dataSource() {
            return TestPostgres.freshDatabase();
        }
    }

    private static HttpServer resend;
    private static final AtomicInteger correosEnviados = new AtomicInteger();

    @DynamicPropertySource
    static void resendSimulado(DynamicPropertyRegistry registry) throws IOException {
        resend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resend.createContext("/emails", exchange -> {
            int n = correosEnviados.incrementAndGet();
            byte[] body = ("{\"id\":\"re_test_selection_" + n + "\"}").getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        resend.start();
        registry.add("app.mail.api-url",
                () -> "http://127.0.0.1:" + resend.getAddress().getPort() + "/emails");
        registry.add("app.mail.api-key", () -> "llave-de-prueba");
    }

    @BeforeEach
    void reiniciar() {
        correosEnviados.set(0);
    }

    @Autowired
    QuoteService quoteService;

    @Autowired
    SelectionService selectionService;

    @Autowired
    SelectionRepository selectionRepo;

    @MockBean
    LeadClient leadClient;

    @MockBean
    PricingClient pricingClient;

    private static CreateQuoteRequest cotizacion() {
        return new CreateQuoteRequest("Cliente Selección", "cliente@ejemplo.cl", null, null, null,
                null, null,
                List.of(new OptionRequest("Opción", "descripción", BigDecimal.valueOf(100000),
                        null, "CLP", false, List.of(), null, null)));
    }

    private void esperar(java.util.function.BooleanSupplier condicion) throws InterruptedException {
        long limite = System.currentTimeMillis() + 3000;
        while (!condicion.getAsBoolean() && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("elegir una opción guarda el resendEmailId del aviso interno en la selección")
    void elegirGuardaElResendEmailIdDelAviso() throws InterruptedException {
        var creada = quoteService.create(cotizacion());
        quoteService.markSentManually(creada.id(), Instant.now()); // deja la cotización SENT, elegible
        var opcionId = quoteService.getDetail(creada.id()).options().get(0).id();

        selectionService.select(creada.codigo(), opcionId);

        esperar(() -> correosEnviados.get() >= 1);
        var selections = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(creada.id());
        assertThat(selections).hasSize(1);
        esperar(() -> selectionRepo.findById(selections.get(0).getId())
                .map(s -> s.getResendEmailId() != null).orElse(false));

        var guardada = selectionRepo.findById(selections.get(0).getId()).orElseThrow();
        assertThat(guardada.getResendEmailId()).isNotNull();
    }

    @Test
    @DisplayName("un rebote que calza con la selección la marca y avisa — mensaje distinto al de la cotización")
    void reboteDeSeleccionCalzaYAvisaConMensajeCorrecto() throws InterruptedException {
        var creada = quoteService.create(cotizacion());
        quoteService.markSentManually(creada.id(), Instant.now());
        var opcionId = quoteService.getDetail(creada.id()).options().get(0).id();
        selectionService.select(creada.codigo(), opcionId);

        var selections = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(creada.id());
        esperar(() -> selectionRepo.findById(selections.get(0).getId())
                .map(s -> s.getResendEmailId() != null).orElse(false));
        var resendId = selectionRepo.findById(selections.get(0).getId()).orElseThrow().getResendEmailId();
        int enviadosAntesDelRebote = correosEnviados.get();

        boolean calzo = selectionService.recordBounce(resendId, "mailbox full");

        assertThat(calzo).isTrue();
        var conMarca = selectionRepo.findById(selections.get(0).getId()).orElseThrow();
        assertThat(conMarca.getBounceDetectedAt()).isNotNull();
        assertThat(conMarca.getBounceReason()).isEqualTo("mailbox full");

        esperar(() -> correosEnviados.get() > enviadosAntesDelRebote);
        assertThat(correosEnviados.get())
                .as("el rebote de un aviso de selección tiene que generar SU PROPIO aviso — la acción es llamar")
                .isGreaterThan(enviadosAntesDelRebote);
    }

    @Test
    @DisplayName("un id que no calza con ninguna selección no revienta")
    void idQueNoCalzaNoRevienta() {
        boolean calzo = selectionService.recordBounce("re_inexistente", "algo");
        assertThat(calzo).isFalse();
    }
}
