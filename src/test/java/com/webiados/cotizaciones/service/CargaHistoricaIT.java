package com.webiados.cotizaciones.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webiados.cotizaciones.db.TestPostgres;
import com.webiados.cotizaciones.domain.QuoteStatus;
import com.webiados.cotizaciones.dto.admin.CreateQuoteRequest;
import com.webiados.cotizaciones.repo.QuoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Ensaya la carga del histórico usando <strong>los mismos archivos JSON</strong> que va a
 * mandar {@code docs/carga-inicial/cargar.sh}, contra un Postgres real.
 *
 * <p>La idea es que nadie descubra un payload mal armado escribiendo en producción: si
 * este test pasa, el script funciona.
 */
@SpringBootTest
@Import(CargaHistoricaIT.PostgresConfig.class)
@TestPropertySource(properties = {
        "app.admin.bootstrap-email=",
        "app.admin.bootstrap-password=",
})
class CargaHistoricaIT {

    @TestConfiguration
    static class PostgresConfig {
        @Bean
        DataSource dataSource() {
            return TestPostgres.freshDatabase();
        }
    }

    private static final Path CARGA = Path.of("docs", "carga-inicial");

    @Autowired
    QuoteService quoteService;

    @Autowired
    QuoteRepository quoteRepo;

    @Autowired
    ObjectMapper json;

    /** El correo se mockea: cargar el histórico no debe escribirle a nadie. */
    @MockBean
    JavaMailSender mailSender;

    /**
     * Estado <strong>guardado</strong>, no el derivado.
     *
     * <p>El histórico se emitió en julio de 2026 y la validez son 15 días: hoy
     * {@code statusAt(now)} devuelve EXPIRED, y hace bien. Lo que este test verifica es que la
     * carga deje la marca de "enviada" en la base, que es el dato del embudo. La derivación a
     * EXPIRED se prueba aparte, con reloj explícito, en {@code QuoteTest}.
     */
    private QuoteStatus estadoGuardado(java.util.UUID id) {
        return quoteRepo.findById(id).orElseThrow().getStatus();
    }

    private CreateQuoteRequest leer(String archivo) throws Exception {
        return json.readValue(CARGA.resolve(archivo).toFile(), CreateQuoteRequest.class);
    }

    @Test
    @DisplayName("Macarena: 3 opciones, emitida el 24-07, entregada, y acepta la Opción C")
    void macarena() throws Exception {
        var creada = quoteService.create(leer("macarena.json"));

        var emitida = Instant.parse("2026-07-24T15:00:00Z");
        quoteService.markSentManually(creada.id(), emitida);
        var detalle = quoteService.getDetail(creada.id());

        assertThat(detalle.clientName()).isEqualTo("Macarena Larraín");
        assertThat(detalle.createdAt()).isEqualTo(emitida);
        assertThat(detalle.sentAt()).isEqualTo(emitida);
        assertThat(detalle.options()).hasSize(3);

        var opcionC = detalle.options().get(2);
        assertThat(opcionC.titulo()).startsWith("Opción C");
        assertThat(opcionC.precio()).isEqualByComparingTo("380000");
        assertThat(opcionC.precioTotal())
                .as("el PDF que recibió dice $452.200")
                .isEqualByComparingTo("452200");
        assertThat(opcionC.recomendado()).isTrue();
        assertThat(opcionC.precioMensual())
                .as("el plan de mantención es una decisión aparte, no la mensualidad de la opción")
                .isNull();

        // Queda SENT. El paso a SELECTED lo hace el script registrando la elección real
        // del cliente a través del flujo del cliente (unlock + select), para que quede
        // también en la bitácora de selecciones y no solo como un estado escrito a mano.
        assertThat(estadoGuardado(creada.id())).isEqualTo(QuoteStatus.SENT);

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("Vientos del Sur: 3 opciones con mensualidad, queda SENT de verdad")
    void vientosDelSur() throws Exception {
        var creada = quoteService.create(leer("vientos-del-sur.json"));

        var emitida = Instant.parse("2026-07-27T12:00:00Z");
        var detalle = quoteService.markSentManually(creada.id(), emitida);

        assertThat(detalle.clientName()).isEqualTo("Pastelería Vientos del Sur");
        assertThat(estadoGuardado(creada.id()))
                .as("este es el estado que antes era imposible representar")
                .isEqualTo(QuoteStatus.SENT);
        assertThat(detalle.sentAt()).isEqualTo(emitida);

        var opciones = detalle.options();
        assertThat(opciones).hasSize(3);

        // Los totales tienen que coincidir con el Markdown que ya recibió el cliente.
        assertThat(opciones.get(0).precioTotal()).isEqualByComparingTo("1237600");
        assertThat(opciones.get(1).precioTotal()).isEqualByComparingTo("1475600");
        assertThat(opciones.get(2).precioTotal()).isEqualByComparingTo("1951600");

        // Mensualidades: A y B a $49.000, C a $74.000 — ahora como dato, no como texto.
        assertThat(opciones.get(0).precioMensual()).isEqualByComparingTo("49000");
        assertThat(opciones.get(0).precioMensualTotal()).isEqualByComparingTo("58310");
        assertThat(opciones.get(2).precioMensual()).isEqualByComparingTo("74000");
        assertThat(opciones.get(2).precioMensualTotal()).isEqualByComparingTo("88060");

        assertThat(opciones.get(1).recomendado()).as("la Opción B es la marcada ⭐").isTrue();

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("ninguna opción trae el IVA escrito a mano en el texto")
    void sinIvaEnElTexto() throws Exception {
        for (String archivo : new String[]{"macarena.json", "vientos-del-sur.json"}) {
            var req = leer(archivo);
            for (var o : req.options()) {
                assertThat(o.descripcion())
                        .as("%s / %s: el IVA es un dato calculado, no texto", archivo, o.titulo())
                        .doesNotContain("IVA");
                assertThat(o.features())
                        .noneMatch(f -> f.startsWith("TOTAL +IVA"));
            }
        }
    }
}
