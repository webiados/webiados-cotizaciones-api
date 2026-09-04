package com.webiados.cotizaciones.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "selection")
public class Selection {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private QuoteOption option;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SelectionKind kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * El id que Resend devolvió al aceptar el aviso interno de esta selección
     * ({@code EmailService.notifySelection}) — igual que {@link Quote#getResendEmailId()},
     * pero acá porque una cotización puede tener varias selecciones (INICIAL + UPGRADE) y un
     * rebote tiene que calzar con el aviso exacto, no con la cotización en general.
     */
    @Column(name = "resend_email_id", length = 64)
    private String resendEmailId;

    /**
     * Cuándo Resend avisó, por webhook, que este aviso interno YA ACEPTADO rebotó de verdad.
     * Acá el mensaje al notificar no puede ser "algo falló": la aceptación del cliente no se
     * perdió — está guardada en {@link Quote#getStatus()} — lo que se perdió es que alguien se
     * enterara a tiempo. La acción no es reintentar el correo, es que una persona llame.
     */
    @Column(name = "bounce_detected_at")
    private Instant bounceDetectedAt;

    private String bounceReason;

    protected Selection() {
    }

    public Selection(UUID id, Quote quote, QuoteOption option, SelectionKind kind, Instant createdAt) {
        this.id = id;
        this.quote = quote;
        this.option = option;
        this.kind = kind;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Quote getQuote() {
        return quote;
    }

    public QuoteOption getOption() {
        return option;
    }

    public SelectionKind getKind() {
        return kind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getResendEmailId() {
        return resendEmailId;
    }

    public Instant getBounceDetectedAt() {
        return bounceDetectedAt;
    }

    public String getBounceReason() {
        return bounceReason;
    }

    /** Guarda el id que Resend devolvió al aceptar el aviso interno de esta selección. */
    public void recordResendEmailId(String resendEmailId) {
        this.resendEmailId = resendEmailId;
    }

    /**
     * Registra que Resend avisó, por webhook, que el aviso interno de esta selección rebotó de
     * verdad. No hay estado que corregir: la selección ya está guardada.
     */
    public void markBounced(Instant when, String motivo) {
        this.bounceDetectedAt = when;
        this.bounceReason = (motivo != null && !motivo.isBlank()) ? motivo : "Motivo desconocido";
    }
}
