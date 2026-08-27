package com.webiados.cotizaciones.web.exception;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El handler genérico (Exception.class) no debe tragarse excepciones de Spring que ya
 * traen su propio status HTTP correcto — como el 405 de un método no soportado en una
 * ruta que sí existe con otro verbo. Antes del fix, cualquier excepción no listada
 * explícitamente caía en handleGeneric y salía como 500.
 */
class ApiExceptionHandlerTest {

    @RestController
    static class DummyController {
        @GetMapping("/dummy")
        public String get() {
            return "ok";
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
}
