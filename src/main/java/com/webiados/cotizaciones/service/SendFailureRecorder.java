package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.repo.QuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Registra un {@code /send} fallido en su <strong>propia</strong> transacción — a propósito,
 * separada de la clase (Spring no aplica {@code REQUIRES_NEW} en una llamada de un método a
 * otro dentro del mismo bean; necesita pasar por el proxy).
 *
 * <p>{@code QuoteService.send()} deja la transacción del intento fallido revertirse entera (la
 * cotización no puede quedar marcada como {@code SENT} sin haber salido el correo); esta marca
 * tiene que sobrevivir esa reversión, no formar parte de ella.
 */
@Service
public class SendFailureRecorder {

    private final QuoteRepository quoteRepo;

    public SendFailureRecorder(QuoteRepository quoteRepo) {
        this.quoteRepo = quoteRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID quoteId, String motivo) {
        quoteRepo.findById(quoteId).ifPresent(q -> q.markSendFailed(Instant.now(), motivo));
    }
}
