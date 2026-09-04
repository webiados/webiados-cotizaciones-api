package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.repo.SelectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Guarda el {@code resendEmailId} de una selección desde un callback {@code .thenAccept}, en su
 * propia transacción de verdad.
 *
 * <p>Bean aparte a propósito — mismo motivo que {@link SendFailureRecorder}: un método
 * {@code @Transactional} llamado con {@code this.metodo(...)} desde otro método de la MISMA
 * clase no pasa por el proxy de Spring, así que la anotación no hace nada. Encontrado por el
 * test de integración: el objeto en memoria mostraba el id guardado, pero nunca llegaba a la
 * base — exactamente el mismo bug documentado en {@code SendFailureRecorder}, esta vez del lado
 * de quien escribió el código, no de un ejemplo de manual.
 */
@Service
public class SelectionResendIdRecorder {

    private final SelectionRepository selectionRepo;

    public SelectionResendIdRecorder(SelectionRepository selectionRepo) {
        this.selectionRepo = selectionRepo;
    }

    @Transactional
    public void record(UUID selectionId, String resendId) {
        selectionRepo.findById(selectionId).ifPresent(s -> s.recordResendEmailId(resendId));
    }
}
