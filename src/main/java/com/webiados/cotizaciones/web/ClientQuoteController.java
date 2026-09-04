package com.webiados.cotizaciones.web;

import com.webiados.cotizaciones.dto.admin.TokenResponse;
import com.webiados.cotizaciones.dto.client.QuoteClientView;
import com.webiados.cotizaciones.dto.client.SelectRequest;
import com.webiados.cotizaciones.dto.client.UnlockRequest;
import com.webiados.cotizaciones.security.JwtAuthFilter.JwtPrincipal;
import com.webiados.cotizaciones.security.RateLimiter;
import com.webiados.cotizaciones.service.AuthService;
import com.webiados.cotizaciones.service.QuoteService;
import com.webiados.cotizaciones.service.SelectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/client/quotes")
public class ClientQuoteController {

    private static final Logger log = LoggerFactory.getLogger(ClientQuoteController.class);

    private final QuoteService quoteService;
    private final SelectionService selectionService;
    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public ClientQuoteController(QuoteService quoteService, SelectionService selectionService,
                                  AuthService authService, RateLimiter rateLimiter) {
        this.quoteService = quoteService;
        this.selectionService = selectionService;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{codigo}/unlock")
    public ResponseEntity<TokenResponse> unlock(@PathVariable String codigo,
                                                @Valid @RequestBody UnlockRequest req,
                                                HttpServletRequest httpReq) {
        String rateLimitKey = codigo + ":" + httpReq.getRemoteAddr();
        if (rateLimiter.isBlocked(rateLimitKey)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        String token;
        try {
            // Buscamos el quote; si no existe lanzamos mismo 401 que clave mala
            var quote = quoteService.findByCodigo(codigo);
            token = authService.clientUnlock(codigo, req.clave(), quote.getClaveHash());
        } catch (Exception ex) {
            // Mismo 401 para "no existe" y "clave mala" para no filtrar existencia
            rateLimiter.record(rateLimitKey); // un solo registro por intento fallido
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        rateLimiter.reset(rateLimitKey); // éxito: resetear contador
        try {
            // Fuera del try de arriba a propósito: recordUnlock() dice en su propio Javadoc que
            // esto no debe poder tumbar un login válido. Antes estaba en el mismo catch que la
            // clave — si esto fallaba por cualquier razón ajena a la clave (una escritura a la
            // BD, por ejemplo), el cliente con la clave correcta recibía el mismo 401 que
            // alguien con la clave mala: el sistema afirmaba "clave incorrecta" sin poder
            // saberlo.
            quoteService.recordUnlock(codigo); // primera vez que hubo intención real, no un clic perdido
        } catch (Exception ex) {
            log.error("No se pudo registrar el desbloqueo de {} (el login sí fue válido)", codigo, ex);
        }
        return ResponseEntity.ok(new TokenResponse(token));
    }

    @GetMapping("/{codigo}")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<QuoteClientView> get(@PathVariable String codigo,
                                               @AuthenticationPrincipal JwtPrincipal principal) {
        validateCodigo(codigo, principal);
        return ResponseEntity.ok(quoteService.getClientViewByCodigo(codigo));
    }

    @PostMapping("/{codigo}/select")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<QuoteClientView> select(@PathVariable String codigo,
                                                  @Valid @RequestBody SelectRequest req,
                                                  @AuthenticationPrincipal JwtPrincipal principal) {
        validateCodigo(codigo, principal);
        return ResponseEntity.ok(selectionService.select(codigo, req.optionId()));
    }

    private void validateCodigo(String codigo, JwtPrincipal principal) {
        if (principal == null || !codigo.equals(principal.codigo())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Acceso denegado");
        }
    }
}
