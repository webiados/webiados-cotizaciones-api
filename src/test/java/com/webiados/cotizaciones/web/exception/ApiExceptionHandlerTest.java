package com.webiados.cotizaciones.web.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El handler genérico (Exception.class) no debe tragarse excepciones que ya traen su propio
 * status HTTP correcto — como el 405 de un método no soportado en una ruta que sí existe con
 * otro verbo, o el 403 de un token de cliente que no coincide con el código de la cotización.
 * Antes de cada fix, esa excepción caía en handleGeneric y salía como 500.
 *
 * <p>Encontrados en producción real, no en teoría: el 403 de {@code ClientQuoteController}
 * cuando el código del token no coincide con el de la ruta (navegando entre dos cotizaciones en
 * la misma pestaña, sin volver a poner la clave), y el 403 de {@code AccessDeniedException}
 * cuando un token de cliente pega a un endpoint solo-admin. Dos ocurrencias del mismo patrón en
 * el mismo archivo — se corrigen las dos a la vez, con un handler por tipo de excepción, no por
 * caso puntual, para no dejar una tercera.
 */
class ApiExceptionHandlerTest {

    @RestController
    static class DummyController {
        @GetMapping("/dummy")
        public String get() {
            return "ok";
        }

        @GetMapping("/prohibido")
        public String prohibido() {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso denegado");
        }

        @GetMapping("/solo-admin")
        public String soloAdmin() {
            throw new AccessDeniedException("Access is denied");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DummyController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void metodoNoSoportadoDevuelve405NoNot500() throws Exception {
        // /dummy solo tiene GET; un POST debe dar 405, no 500.
        mockMvc.perform(post("/dummy"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void responseStatusExceptionRespetaSuPropioStatus() throws Exception {
        // Reproduce el 403 real de ClientQuoteController cuando el código del token no
        // coincide con el de la ruta — antes del fix salía como 500.
        mockMvc.perform(get("/prohibido"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Acceso denegado"));
    }

    @Test
    void accessDeniedExceptionDevuelve403NoNot500() throws Exception {
        // Reproduce el 403 real de un token de cliente pegándole a un endpoint @PreAuthorize
        // de admin — antes del fix salía como 500.
        mockMvc.perform(get("/solo-admin"))
                .andExpect(status().isForbidden());
    }
}
