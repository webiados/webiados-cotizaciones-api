package com.webiados.cotizaciones.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EmailContentTest {

    private static final String URL = "https://webiados.com/cotizacion/ic8gtkbiau";
    private static final String CODIGO = "ic8gtkbiau";
    private static final String CLAVE = "DFjddNDKY5";
    private static final String VIGENCIA = "lunes 18 de agosto";

    @Test
    void html_trae_boton_codigo_clave_y_vigencia() {
        String html = EmailService.buildHtml("Felipe", URL, CODIGO, CLAVE, VIGENCIA);

        assertThat(html).contains("Ver mi cotización");         // el botón, no un link pelado
        assertThat(html).contains("href=\"" + URL + "\"");       // el botón apunta a la landing
        assertThat(html).contains(CLAVE);                        // la clave, en su recuadro
        assertThat(html).contains(CODIGO);
        assertThat(html).contains("hasta el <strong");           // vigencia en lenguaje humano
        assertThat(html).contains(VIGENCIA);
        assertThat(html).contains("cid:logo-webiados");           // logo incrustado (cid), no .webp:
                                                                   // WebP no lo soportan todos los
                                                                   // clientes de correo (Outlook, y el
                                                                   // proxy de imágenes de Gmail lo
                                                                   // corrompe en vez de mostrarlo)
        assertThat(html).contains("alt=\"Webiados\"");           // se entiende sin ver la imagen
        assertThat(html).contains("#d3e600");                    // identidad: lima
        assertThat(html).contains("#0a0a0a");                    // identidad: tinta
        assertThat(html).contains("monospace");                  // clave en monoespaciada
    }

    @Test
    void html_usa_estilos_en_linea_y_tablas_no_style_en_head() {
        String html = EmailService.buildHtml("Felipe", URL, CODIGO, CLAVE, VIGENCIA);
        assertThat(html).contains("<table");
        assertThat(html).contains("style=\"");
        assertThat(html).doesNotContain("<style");   // Gmail borra el <style> del head
        assertThat(html).doesNotContain("Bricolage"); // nada de fuentes web
    }

    @Test
    void html_escapa_el_nombre() {
        String html = EmailService.buildHtml("<b>hola</b>", URL, CODIGO, CLAVE, VIGENCIA);
        assertThat(html).contains("Hola &lt;b&gt;hola&lt;/b&gt;,");
        assertThat(html).doesNotContain("Hola <b>hola</b>,");
    }

    @Test
    void texto_plano_existe_y_trae_lo_esencial() {
        String text = EmailService.buildText("Felipe", URL, CODIGO, CLAVE, VIGENCIA);
        assertThat(text).contains("Hola Felipe:");
        assertThat(text).contains(URL);
        assertThat(text).contains(CLAVE);
        assertThat(text).contains(CODIGO);
        assertThat(text).contains(VIGENCIA);
        assertThat(text).doesNotContain("<");   // es texto, no HTML
    }

    /** Deja un preview del correo en target/ para inspección visual. No es una aserción. */
    @Test
    void dump_preview() throws Exception {
        String html = EmailService.buildHtml("Felipe", URL, CODIGO, CLAVE, VIGENCIA);
        Path out = Path.of("target", "correo-preview.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);
        assertThat(Files.exists(out)).isTrue();
    }
}
