package com.webiados.cotizaciones.repo;

import com.webiados.cotizaciones.domain.Selection;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SelectionRepository extends JpaRepository<Selection, UUID> {

    List<Selection> findByQuoteIdOrderByCreatedAtAsc(UUID quoteId);

    long countByQuoteId(UUID quoteId);

    /**
     * Para calzar el webhook de rebote de Resend contra el aviso interno exacto que lo mandó.
     * {@code quote} y {@code option} se traen de una vez: {@code recordBounce} le pasa esta
     * selección a {@code notifySelectionBounce}, que es {@code @Async} — corre en otro hilo, sin
     * la sesión de Hibernate de esta transacción, así que un proxy lazy ahí revienta con
     * {@code LazyInitializationException}. Encontrado por el test de integración, no supuesto.
     */
    @EntityGraph(attributePaths = {"quote", "option"})
    Optional<Selection> findByResendEmailId(String resendEmailId);
}
