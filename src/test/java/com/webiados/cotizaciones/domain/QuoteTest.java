package com.webiados.cotizaciones.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuoteTest {

    private static final Instant AHORA = Instant.parse("2026-07-27T12:00:00Z");

    private static Quote nueva() {
        return new Quote(UUID.randomUUID(), "abc123", "hash", "clave", "Cliente",
                "cliente@ejemplo.cl", null, AHORA, AHORA.plus(15, ChronoUnit.DAYS),
                null, null, null);
    }

    @Nested
    @DisplayName("estado")
    class Estado {

        @Test
        @DisplayName("nace PENDING")
        void nacePending() {
            assertThat(nueva().statusAt(AHORA)).isEqualTo(QuoteStatus.PENDING);
        }

        @Test
        @DisplayName("al enviarla queda SENT y guarda la fecha de envío")
        void enviarGuardaFecha() {
            var q = nueva();
            q.markSent(AHORA);

            assertThat(q.statusAt(AHORA)).isEqualTo(QuoteStatus.SENT);
            assertThat(q.getSentAt()).isEqualTo(AHORA);
        }

        @Test
        @DisplayName("reenviar no pisa la fecha del primer envío")
        void reenviarConservaLaPrimeraFecha() {
            var q = nueva();
            q.markSent(AHORA);
            q.markSent(AHORA.plus(3, ChronoUnit.DAYS));

            assertThat(q.getSentAt())
                    .as("la tasa de cierre se mide desde el primer envío")
                    .isEqualTo(AHORA);
        }

        @Test
        @DisplayName("elegir una opción la deja SELECTED")
        void elegirDejaSelected() {
            var q = nueva();
            q.markSent(AHORA);
            q.recordSelection(UUID.randomUUID(), AHORA.plus(1, ChronoUnit.DAYS));

            assertThat(q.statusAt(AHORA.plus(2, ChronoUnit.DAYS))).isEqualTo(QuoteStatus.SELECTED);
            assertThat(q.getSentAt()).isEqualTo(AHORA);
        }

        @Test
        @DisplayName("rechazar la deja REJECTED con su fecha")
        void rechazar() {
            var q = nueva();
            q.markSent(AHORA);
            q.markRejected(AHORA.plus(1, ChronoUnit.DAYS));

            assertThat(q.statusAt(AHORA)).isEqualTo(QuoteStatus.REJECTED);
            assertThat(q.getRejectedAt()).isEqualTo(AHORA.plus(1, ChronoUnit.DAYS));
        }

        @Test
        @DisplayName("una PENDING vencida se muestra EXPIRED sin haberse guardado así")
        void pendingVencida() {
            var q = nueva();
            var despues = AHORA.plus(20, ChronoUnit.DAYS);

            assertThat(q.statusAt(despues)).isEqualTo(QuoteStatus.EXPIRED);
            assertThat(q.getStatus())
                    .as("EXPIRED se deriva, no se persiste")
                    .isEqualTo(QuoteStatus.PENDING);
        }

        @Test
        @DisplayName("una SENT vencida se muestra EXPIRED pero conserva su fecha de envío")
        void sentVencida() {
            var q = nueva();
            q.markSent(AHORA);
            var despues = AHORA.plus(20, ChronoUnit.DAYS);

            assertThat(q.statusAt(despues)).isEqualTo(QuoteStatus.EXPIRED);
            assertThat(q.getSentAt())
                    .as("expirar no borra que se envió: sigue contando en el embudo")
                    .isEqualTo(AHORA);
        }

        @Test
        @DisplayName("una aceptada no expira aunque pase la fecha")
        void aceptadaNoExpira() {
            var q = nueva();
            q.recordSelection(UUID.randomUUID(), AHORA);

            assertThat(q.statusAt(AHORA.plus(90, ChronoUnit.DAYS)))
                    .isEqualTo(QuoteStatus.SELECTED);
        }

        @Test
        @DisplayName("no se puede volver a 'enviada' una que el cliente ya respondió")
        void noSeReenviaUnaRespondida() {
            var q = nueva();
            q.recordSelection(UUID.randomUUID(), AHORA);

            assertThatThrownBy(() -> q.markSent(AHORA.plus(1, ChronoUnit.DAYS)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("no se puede rechazar una que ya fue aceptada")
        void noSeRechazaUnaAceptada() {
            var q = nueva();
            q.recordSelection(UUID.randomUUID(), AHORA);

            assertThatThrownBy(() -> q.markRejected(AHORA.plus(1, ChronoUnit.DAYS)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("actualización parcial")
    class Patch {

        @Test
        @DisplayName("un campo null no borra el valor que ya estaba")
        void nullNoBorra() {
            var q = new Quote(UUID.randomUUID(), "abc123", "hash", "clave", "Cliente",
                    null, "notas originales", AHORA, AHORA.plus(15, ChronoUnit.DAYS),
                    "Título original", "Mensaje original", "img.jpg");

            q.updateMeta(null, null, "notas nuevas", null, null);

            assertThat(q.getTitulo()).isEqualTo("Título original");
            assertThat(q.getMensaje()).isEqualTo("Mensaje original");
            assertThat(q.getImagenes()).isEqualTo("img.jpg");
            assertThat(q.getNotes()).isEqualTo("notas nuevas");
        }

        @Test
        @DisplayName("imagenes se puede editar (antes no había forma)")
        void imagenesEditable() {
            var q = nueva();
            q.updateMeta(null, null, null, "nueva.jpg", null);

            assertThat(q.getImagenes()).isEqualTo("nueva.jpg");
        }
    }

    @Nested
    @DisplayName("IVA")
    class Iva {

        /**
         * Los valores esperados salen de las cotizaciones reales ya enviadas: el PDF de
         * Macarena y el Markdown de Vientos del Sur. Si esta cuenta cambia, un cliente
         * vería un total distinto al que ya recibió.
         */
        @ParameterizedTest(name = "{0} neto → {1} IVA → {2} total")
        @CsvSource({
                // Macarena Larraín (PDF, 24 JUL 2026)
                "150000,  28500,  178500",
                "260000,  49400,  309400",
                "380000,  72200,  452200",
                // Pastelería Vientos del Sur
                "1040000, 197600, 1237600",
                "1240000, 235600, 1475600",
                "1640000, 311600, 1951600",
                // Mensualidades
                "49000,   9310,   58310",
                "74000,   14060,  88060",
                "25000,   4750,   29750",
                "50000,   9500,   59500",
        })
        void coincideConLasCotizacionesReales(long neto, long iva, long total) {
            var q = nueva();
            var montoNeto = BigDecimal.valueOf(neto);

            assertThat(q.ivaSobre(montoNeto)).isEqualByComparingTo(BigDecimal.valueOf(iva));
            assertThat(q.totalConIva(montoNeto)).isEqualByComparingTo(BigDecimal.valueOf(total));
        }

        @Test
        @DisplayName("un monto nulo no se convierte en cero")
        void nullSigueSiendoNull() {
            var q = nueva();

            assertThat(q.ivaSobre(null)).isNull();
            assertThat(q.totalConIva(null)).isNull();
        }

        @Test
        @DisplayName("el total es entero: CLP no tiene decimales")
        void totalEntero() {
            var q = nueva();

            assertThat(q.totalConIva(BigDecimal.valueOf(333333)).scale()).isZero();
        }
    }

    @Nested
    @DisplayName("opciones")
    class Opciones {

        @Test
        @DisplayName("una opción puede llevar mensualidad, y otra no")
        void mensualidadOpcional() {
            var q = nueva();
            q.addOption(new QuoteOption(UUID.randomUUID(), 0, "Con plan", null,
                    BigDecimal.valueOf(1040000), BigDecimal.valueOf(49000), "CLP", false,
                    List.of()));
            q.addOption(new QuoteOption(UUID.randomUUID(), 1, "Sin plan", null,
                    BigDecimal.valueOf(380000), null, "CLP", false, List.of()));

            assertThat(q.getOptions().get(0).getPrecioMensual())
                    .isEqualByComparingTo(BigDecimal.valueOf(49000));
            assertThat(q.getOptions().get(1).getPrecioMensual()).isNull();
        }
    }

    @Nested
    @DisplayName("desbloqueo")
    class Desbloqueo {

        @Test
        @DisplayName("nace sin desbloquear")
        void naceSinDesbloquear() {
            assertThat(nueva().getUnlockedAt()).isNull();
        }

        @Test
        @DisplayName("al desbloquearla guarda la fecha")
        void desbloquearGuardaFecha() {
            var q = nueva();
            q.markUnlocked(AHORA);

            assertThat(q.getUnlockedAt()).isEqualTo(AHORA);
        }

        @Test
        @DisplayName("volver a desbloquearla no pisa la primera fecha — es intención real, no un contador")
        void redesbloquearConservaLaPrimeraFecha() {
            var q = nueva();
            q.markUnlocked(AHORA);
            q.markUnlocked(AHORA.plus(3, ChronoUnit.DAYS));

            assertThat(q.getUnlockedAt()).isEqualTo(AHORA);
        }
    }
}
