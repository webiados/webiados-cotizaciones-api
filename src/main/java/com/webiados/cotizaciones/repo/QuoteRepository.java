package com.webiados.cotizaciones.repo;

import com.webiados.cotizaciones.domain.Quote;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {

    @EntityGraph(attributePaths = {"options"})
    Optional<Quote> findByCodigo(String codigo);

    @EntityGraph(attributePaths = {"options"})
    Optional<Quote> findWithOptionsById(UUID id);

    boolean existsByCodigo(String codigo);

    /** Para calzar el webhook de rebote de Resend contra la cotización exacta que lo mandó. */
    Optional<Quote> findByResendEmailId(String resendEmailId);

    List<Quote> findAllByOrderByCreatedAtDesc();

    /**
     * Candidatas a "sin respuesta": el cliente tuvo la oportunidad de responder —se envió, o
     * se abrió sin haberse marcado enviada todavía— y no dijo ni sí ni no, hace al menos
     * {@code limite} tiempo, y todavía no se avisó por esto. Se usa tanto para el aviso real
     * como para la siembra silenciosa la primera vez que se activa (§ {@code
     * StaleQuoteAlertJob}).
     */
    @Query("""
            SELECT q FROM Quote q
             WHERE q.selectedAt IS NULL AND q.rejectedAt IS NULL AND q.staleAlertedAt IS NULL
               AND (q.status = 'SENT' OR (q.status = 'PENDING' AND q.unlockedAt IS NOT NULL))
               AND COALESCE(q.sentAt, q.unlockedAt) <= :limite
            """)
    List<Quote> findStaleCandidates(Instant limite);

    boolean existsByStaleAlertedAtIsNotNull();
}
