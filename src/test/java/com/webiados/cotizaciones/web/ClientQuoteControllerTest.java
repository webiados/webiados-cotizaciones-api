package com.webiados.cotizaciones.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.security.RateLimiter;
import com.webiados.cotizaciones.service.AuthService;
import com.webiados.cotizaciones.service.QuoteService;
import com.webiados.cotizaciones.service.SelectionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code recordUnlock} lo dice en su propio Javadoc: "esto no debe poder hacer fallar un login
 * válido". Pero el {@code catch (Exception ex)} del controller envolvía la clave correcta Y el
 * registro de auditoría en el mismo bloque — si {@code recordUnlock} fallaba por cualquier razón
 * (una escritura a la BD, por ejemplo), el cliente con la clave correcta recibía el mismo 401 que
 * alguien con la clave mala. El sistema afirmaba "clave incorrecta" sin poder saberlo: la clave
 * sí era correcta, solo falló un dato de auditoría que no debería poder tumbar un login.
 */
class ClientQuoteControllerTest {

    private final QuoteService quoteService = mock(QuoteService.class);
    private final SelectionService selectionService = mock(SelectionService.class);
    private final AuthService authService = mock(AuthService.class);
    private final RateLimiter rateLimiter =
            new RateLimiter(new AppProperties(null, null, null, null,
                    new AppProperties.Ratelimit(5, 15), null, null, null));

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ClientQuoteController(quoteService, selectionService, authService, rateLimiter))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void unlockConClaveCorrectaNoFallaAunqueRecordUnlockFalle() throws Exception {
        String codigo = "abc12345";
        Quote quote = new Quote(UUID.randomUUID(), codigo, "hash", "clave-texto", "Cliente",
                "cliente@ejemplo.cl", null, Instant.now(), Instant.now().plusSeconds(864_000));

        when(quoteService.findByCodigo(codigo)).thenReturn(quote);
        when(authService.clientUnlock(anyString(), anyString(), anyString())).thenReturn("un-jwt-valido");
        // La clave fue correcta (authService no lanzó nada), pero registrar el desbloqueo falla
        // por una razón ajena a la clave — un problema transitorio de escritura, por ejemplo.
        doThrow(new RuntimeException("fallo transitorio de BD"))
                .when(quoteService).recordUnlock(codigo);

        mockMvc.perform(post("/api/client/quotes/{codigo}/unlock", codigo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new java.util.HashMap<>(java.util.Map.of("clave", "la-clave-real")))))
                .andExpect(status().isOk());
    }
}
