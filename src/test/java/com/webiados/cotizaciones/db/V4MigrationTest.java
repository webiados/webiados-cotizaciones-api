package com.webiados.cotizaciones.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La migración V4 es la que hace posible medir el embudo. Se prueba sobre base vacía y
 * sobre base con datos, porque el riesgo real está en el backfill: producción ya tiene
 * cotizaciones y ninguna tiene estado guardado.
 */
class V4MigrationTest {

    private static Flyway flywayFor(DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load();
    }

    /** Corre solo hasta V3, para poder insertar datos "viejos" antes de aplicar V4. */
    private static void migrateTo(DataSource ds, String target) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private static List<String> query(DataSource ds, String sql) throws SQLException {
        var out = new ArrayList<String>();
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void exec(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Nested
    @DisplayName("sobre base vacía")
    class BaseVacia {

        @Test
        @DisplayName("todas las migraciones aplican de cero")
        void aplicaDesdeCero() {
            DataSource ds = TestPostgres.freshDatabase();
            var result = flywayFor(ds).migrate();

            assertThat(result.success).isTrue();
            // V5 agregó pricing_ref (2026-08-29) y V6 plan_sin_pie_meses (2026-09-01) a
            // quote_option: sube de 4 a 6.
            assertThat(result.migrationsExecuted).isEqualTo(6);
        }

        @Test
        @DisplayName("quote queda con status NOT NULL, default PENDING, y sus fechas")
        void esquemaCorrecto() throws SQLException {
            DataSource ds = TestPostgres.freshDatabase();
            flywayFor(ds).migrate();

            var cols = query(ds, """
                    SELECT column_name || ':' || data_type || ':' || is_nullable
                      FROM information_schema.columns
                     WHERE table_name = 'quote'
                       AND column_name IN ('status','sent_at','rejected_at','iva_pct')
                     ORDER BY column_name
                    """);

            assertThat(cols).containsExactly(
                    "iva_pct:integer:NO",
                    "rejected_at:timestamp with time zone:YES",
                    "sent_at:timestamp with time zone:YES",
                    "status:character varying:NO");
        }

        @Test
        @DisplayName("quote_option gana precio_mensual, nullable")
        void precioMensualNullable() throws SQLException {
            DataSource ds = TestPostgres.freshDatabase();
            flywayFor(ds).migrate();

            var cols = query(ds, """
                    SELECT data_type || ':' || is_nullable
                      FROM information_schema.columns
                     WHERE table_name = 'quote_option' AND column_name = 'precio_mensual'
                    """);

            assertThat(cols).containsExactly("numeric:YES");
        }

        @Test
        @DisplayName("el CHECK rechaza un status inventado")
        void rechazaStatusInvalido() {
            DataSource ds = TestPostgres.freshDatabase();
            flywayFor(ds).migrate();

            assertThatThrownBy(() -> insertQuote(ds, "codigo1", "REVIEWED", null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_quote_status");
        }

        @Test
        @DisplayName("no se puede quedar en SENT sin fecha de envío")
        void sentExigeFecha() {
            DataSource ds = TestPostgres.freshDatabase();
            flywayFor(ds).migrate();

            assertThatThrownBy(() -> insertQuote(ds, "codigo2", "SENT", null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_quote_sent_at");
        }

        @Test
        @DisplayName("no se puede quedar en REJECTED sin fecha de rechazo")
        void rejectedExigeFecha() {
            DataSource ds = TestPostgres.freshDatabase();
            flywayFor(ds).migrate();

            assertThatThrownBy(() -> insertQuote(ds, "codigo3", "REJECTED", null, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_quote_rejected_at");
        }
    }

    @Nested
    @DisplayName("sobre base con datos")
    class BaseConDatos {

        @Test
        @DisplayName("una cotización sin opción elegida queda PENDING, no inventa envío")
        void backfillPending() throws SQLException {
            DataSource ds = TestPostgres.freshDatabase();
            migrateTo(ds, "3");
            insertQuoteV3(ds, "vieja1", null);

            flywayFor(ds).migrate();

            assertThat(query(ds, "SELECT status FROM quote WHERE codigo = 'vieja1'"))
                    .containsExactly("PENDING");
            assertThat(query(ds, "SELECT sent_at FROM quote WHERE codigo = 'vieja1'"))
                    .containsExactly((String) null);
        }

        @Test
        @DisplayName("una cotización con opción elegida queda SELECTED y conserva selected_at")
        void backfillSelected() throws SQLException {
            DataSource ds = TestPostgres.freshDatabase();
            migrateTo(ds, "3");

            String quoteId = "11111111-1111-1111-1111-111111111111";
            String optionId = "22222222-2222-2222-2222-222222222222";
            exec(ds, """
                    INSERT INTO quote (id, codigo, clave_hash, client_name, expires_at, selected_at)
                    VALUES ('%s', 'vieja2', 'hash', 'Cliente', now() + interval '10 days', now())
                    """.formatted(quoteId));
            exec(ds, """
                    INSERT INTO quote_option (id, quote_id, order_index, titulo, precio)
                    VALUES ('%s', '%s', 0, 'Opción A', 380000)
                    """.formatted(optionId, quoteId));
            exec(ds, "UPDATE quote SET selected_option_id = '%s' WHERE id = '%s'"
                    .formatted(optionId, quoteId));

            flywayFor(ds).migrate();

            assertThat(query(ds, "SELECT status FROM quote WHERE codigo = 'vieja2'"))
                    .containsExactly("SELECTED");
            assertThat(query(ds, "SELECT selected_at IS NOT NULL FROM quote WHERE codigo = 'vieja2'"))
                    .containsExactly("t");
        }

        @Test
        @DisplayName("las opciones que ya existían quedan con precio_mensual NULL, no 0")
        void precioMensualNoSeInventa() throws SQLException {
            DataSource ds = TestPostgres.freshDatabase();
            migrateTo(ds, "3");

            String quoteId = "33333333-3333-3333-3333-333333333333";
            exec(ds, """
                    INSERT INTO quote (id, codigo, clave_hash, client_name, expires_at)
                    VALUES ('%s', 'vieja3', 'hash', 'Cliente', now() + interval '10 days')
                    """.formatted(quoteId));
            exec(ds, """
                    INSERT INTO quote_option (id, quote_id, order_index, titulo, precio)
                    VALUES ('44444444-4444-4444-4444-444444444444', '%s', 0, 'Opción A', 150000)
                    """.formatted(quoteId));

            flywayFor(ds).migrate();

            assertThat(query(ds, "SELECT precio_mensual FROM quote_option WHERE titulo = 'Opción A'"))
                    .containsExactly((String) null);
        }

        @Test
        @DisplayName("el IVA por defecto de las cotizaciones históricas es 19")
        void ivaPorDefecto() throws SQLException {
            DataSource ds = TestPostgres.freshDatabase();
            migrateTo(ds, "3");
            insertQuoteV3(ds, "vieja4", null);

            flywayFor(ds).migrate();

            assertThat(query(ds, "SELECT iva_pct::text FROM quote WHERE codigo = 'vieja4'"))
                    .containsExactly("19");
        }

        @Test
        @DisplayName("la migración es idempotente: correrla de nuevo no cambia nada")
        void idempotente() throws SQLException {
            DataSource ds = TestPostgres.freshDatabase();
            migrateTo(ds, "3");
            insertQuoteV3(ds, "vieja5", null);

            flywayFor(ds).migrate();
            var segunda = flywayFor(ds).migrate();

            assertThat(segunda.migrationsExecuted).isZero();
            assertThat(query(ds, "SELECT status FROM quote WHERE codigo = 'vieja5'"))
                    .containsExactly("PENDING");
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static void insertQuoteV3(DataSource ds, String codigo, String ignored)
            throws SQLException {
        exec(ds, """
                INSERT INTO quote (id, codigo, clave_hash, client_name, expires_at)
                VALUES (gen_random_uuid(), '%s', 'hash', 'Cliente', now() + interval '10 days')
                """.formatted(codigo));
    }

    private static void insertQuote(DataSource ds, String codigo, String status,
                                    String sentAt, String rejectedAt) throws SQLException {
        exec(ds, """
                INSERT INTO quote (id, codigo, clave_hash, client_name, expires_at, status, sent_at, rejected_at)
                VALUES (gen_random_uuid(), '%s', 'hash', 'Cliente', now() + interval '10 days',
                        '%s', %s, %s)
                """.formatted(codigo, status,
                sentAt == null ? "NULL" : "'" + sentAt + "'",
                rejectedAt == null ? "NULL" : "'" + rejectedAt + "'"));
    }
}
