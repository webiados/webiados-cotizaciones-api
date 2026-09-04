package com.webiados.cotizaciones.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "quote")
public class Quote {

    /** IVA chileno. Regla del repo: el IVA es 19% y el dinero no se redondea a la ligera. */
    public static final int IVA_PCT_CHILE = 19;

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String codigo;

    @Column(name = "clave_hash", nullable = false)
    private String claveHash;

    @Column(name = "clave_texto", length = 64)
    private String claveTexto;

    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "client_email")
    private String clientEmail;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "selected_option_id")
    private UUID selectedOptionId;

    @Column(name = "selected_at")
    private Instant selectedAt;

    /** Estado persistido. Nunca vale EXPIRED: eso se deriva en {@link #statusAt}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuoteStatus status = QuoteStatus.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    /**
     * Primera vez que el cliente puso la clave correcta. {@code null} = nunca la abrió. Es
     * intención real, no un contador de vistas — no se pisa en desbloqueos siguientes, igual
     * que {@link #sentAt}. Sin esto, "el cliente entró y no eligió nada" era invisible: el
     * único rastro que quedaba era {@code selectedAt}/{@code rejectedAt}.
     */
    @Column(name = "unlocked_at")
    private Instant unlockedAt;

    /**
     * Cuándo se avisó (o se sembró en silencio) que esta cotización quedó sin respuesta.
     * {@code null} = todavía no corresponde avisar, o nadie la revisó nunca. Existe para que
     * el aviso no vuelva a sonar todos los días una vez que ya sonó una vez — sin esto, la
     * gente aprende a ignorarlo, que es peor que no tenerlo.
     */
    @Column(name = "stale_alerted_at")
    private Instant staleAlertedAt;

    /**
     * Cuándo falló el último intento real de {@code /send}, o {@code null} si nunca falló (o
     * si el último intento sí funcionó). Sin esto, una cotización con el correo rechazado por
     * el SMTP se ve <strong>idéntica</strong> en el panel a una que nunca se intentó enviar —
     * las dos quedan en {@code PENDING} — y quien la mira no puede distinguir "está esperando
     * al cliente" de "el cliente nunca la recibió".
     */
    @Column(name = "send_failed_at")
    private Instant sendFailedAt;

    @Column(name = "send_failure_reason")
    private String sendFailureReason;

    /** El id que Resend devuelve al aceptar el envío. Llave real para calzar el webhook de
     *  rebote contra esta cotización exacta — no un supuesto por correo + tiempo. */
    @Column(name = "resend_email_id", length = 64)
    private String resendEmailId;

    /**
     * Cuándo Resend avisó, por webhook, que el correo YA ACEPTADO rebotó de verdad —
     * distinto de {@link #sendFailedAt}, que es un fallo al momento de enviar. Este se
     * entera minutos u horas después: el correo se veía enviado y no llegó.
     */
    @Column(name = "bounce_detected_at")
    private Instant bounceDetectedAt;

    @Column(name = "bounce_reason")
    private String bounceReason;

    /** Porcentaje de IVA vigente al emitir. Se guarda para que el histórico no cambie. */
    @Column(name = "iva_pct", nullable = false)
    private int ivaPct = IVA_PCT_CHILE;

    @Column(length = 200)
    private String titulo;

    @Column(columnDefinition = "text")
    private String mensaje;

    @Column(columnDefinition = "text")
    private String imagenes;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<QuoteOption> options = new ArrayList<>();

    protected Quote() {
    }

    public Quote(UUID id, String codigo, String claveHash, String claveTexto, String clientName,
                 String clientEmail, String notes, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.codigo = codigo;
        this.claveHash = claveHash;
        this.claveTexto = claveTexto;
        this.clientName = clientName;
        this.clientEmail = clientEmail;
        this.notes = notes;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Quote(UUID id, String codigo, String claveHash, String claveTexto, String clientName,
                 String clientEmail, String notes, Instant createdAt, Instant expiresAt,
                 String titulo, String mensaje, String imagenes) {
        this(id, codigo, claveHash, claveTexto, clientName, clientEmail, notes, createdAt, expiresAt);
        this.titulo = titulo;
        this.mensaje = mensaje;
        this.imagenes = imagenes;
    }

    public void addOption(QuoteOption option) {
        option.setQuote(this);
        this.options.add(option);
    }

    /** Expirada solo si nunca se aceptó. Tras aceptar queda viva para upgrades. */
    public boolean isExpired(Instant now) {
        return selectedOptionId == null && now.isAfter(expiresAt);
    }

    public boolean canSelect(Instant now) {
        return selectedOptionId != null || !now.isAfter(expiresAt);
    }

    /**
     * Estado efectivo. SELECTED y REJECTED son definitivos; PENDING y SENT caducan.
     */
    public QuoteStatus statusAt(Instant now) {
        if (status == QuoteStatus.SELECTED || status == QuoteStatus.REJECTED) {
            return status;
        }
        return now.isAfter(expiresAt) ? QuoteStatus.EXPIRED : status;
    }

    /**
     * Marca la cotización como enviada al cliente.
     *
     * <p>Reenviar no pisa la fecha original: {@code sentAt} es <em>la primera vez</em> que
     * salió, y es el dato con el que se calcula la tasa de cierre.
     */
    public void markSent(Instant when) {
        if (status == QuoteStatus.SELECTED || status == QuoteStatus.REJECTED) {
            throw new IllegalStateException(
                    "La cotización ya fue respondida por el cliente; no se puede volver a marcar como enviada");
        }
        this.status = QuoteStatus.SENT;
        if (this.sentAt == null) {
            this.sentAt = when;
        }
        // Un envío que sí funciona deja atrás cualquier fallo anterior — reintentar y que
        // funcione no debería seguir mostrando la marca de un intento viejo.
        this.sendFailedAt = null;
        this.sendFailureReason = null;
        // Mismo criterio: un reenvío que sí funciona deja atrás un rebote viejo. Si vuelve a
        // rebotar, el webhook lo va a marcar de nuevo con el resendEmailId del intento nuevo.
        this.bounceDetectedAt = null;
        this.bounceReason = null;
    }

    /** Guarda el id que Resend devolvió al aceptar el envío — llave para el webhook de rebote. */
    public void recordResendEmailId(String resendEmailId) {
        this.resendEmailId = resendEmailId;
    }

    /**
     * Registra que Resend avisó, por webhook, que este envío YA ACEPTADO rebotó de verdad.
     * No cambia el status — igual que {@link #markSendFailed}, sigue en el estado que tenía
     * (probablemente SENT, porque el envío sí se había aceptado); el rebote es información
     * adicional, no un rollback del estado.
     */
    public void markBounced(Instant when, String motivo) {
        this.bounceDetectedAt = when;
        this.bounceReason = (motivo != null && !motivo.isBlank()) ? motivo : "Motivo desconocido";
    }

    /**
     * Registra que se intentó mandar el correo real al cliente y falló — no cambia el estado
     * (sigue {@code PENDING}, se puede reintentar sin recrear nada), pero deja la marca donde
     * el panel la puede mostrar en vez de que se vea igual que una que nunca se intentó.
     */
    public void markSendFailed(Instant when, String motivo) {
        this.sendFailedAt = when;
        this.sendFailureReason = (motivo != null && !motivo.isBlank()) ? motivo : "Error desconocido";
    }

    /**
     * Registra la primera vez que el cliente desbloqueó la cotización con su clave. No cambia
     * el estado — desbloquear no es aceptar ni rechazar, solo mirar — y no pisa la fecha si ya
     * se había desbloqueado antes.
     */
    public void markUnlocked(Instant when) {
        if (this.unlockedAt == null) {
            this.unlockedAt = when;
        }
    }

    /**
     * Marca que ya se avisó (o se sembró en silencio) el "sin respuesta" de esta cotización,
     * para que {@code StaleQuoteAlertJob} no la vuelva a tomar mañana.
     */
    public void markStaleAlerted(Instant when) {
        this.staleAlertedAt = when;
    }

    /**
     * Registra una entrega hecha fuera del sistema, con su fecha real.
     *
     * <p>A diferencia de {@link #markSent}, acá la fecha <em>sí</em> se fija: sirve para
     * cargar el histórico —cotizaciones que se mandaron en PDF a mano— sin falsear el
     * día en que salieron. También se puede usar sobre una cotización ya aceptada: el
     * cliente respondió, así que evidentemente la recibió.
     */
    public void recordManualDelivery(Instant when) {
        if (when == null) {
            throw new IllegalArgumentException("Falta la fecha de envío");
        }
        if (when.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "La fecha de envío no puede ser anterior a la creación de la cotización");
        }
        this.sentAt = when;
        if (status == QuoteStatus.PENDING) {
            this.status = QuoteStatus.SENT;
        }
    }

    public void markRejected(Instant when) {
        if (status == QuoteStatus.SELECTED) {
            throw new IllegalStateException(
                    "La cotización ya fue aceptada; no se puede marcar como rechazada");
        }
        this.status = QuoteStatus.REJECTED;
        this.rejectedAt = when;
    }

    public void recordSelection(UUID optionId, Instant when) {
        this.selectedOptionId = optionId;
        this.selectedAt = when;
        this.status = QuoteStatus.SELECTED;
        this.rejectedAt = null;
    }

    /**
     * Actualización parcial: un campo en {@code null} significa "no lo toques", no
     * "bórralo". Antes esto asignaba directo y un PATCH parcial borraba en silencio el
     * título y el mensaje.
     */
    public void updateMeta(String titulo, String mensaje, String notes, String imagenes,
                           Instant expiresAt) {
        if (titulo != null) {
            this.titulo = titulo;
        }
        if (mensaje != null) {
            this.mensaje = mensaje;
        }
        if (notes != null) {
            this.notes = notes;
        }
        if (imagenes != null) {
            this.imagenes = imagenes;
        }
        if (expiresAt != null) {
            this.expiresAt = expiresAt;
        }
    }

    // --- Dinero -------------------------------------------------------------------
    // El IVA se calcula, no se guarda: guardar neto, IVA y total por separado es
    // invitar a que se contradigan. Lo que se guarda es el porcentaje aplicado.

    /** IVA correspondiente a un monto neto, redondeado a peso entero. */
    public BigDecimal ivaSobre(BigDecimal neto) {
        if (neto == null) {
            return null;
        }
        return neto.multiply(BigDecimal.valueOf(ivaPct))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    /** Neto + IVA, en pesos enteros (CLP no tiene decimales). */
    public BigDecimal totalConIva(BigDecimal neto) {
        if (neto == null) {
            return null;
        }
        return neto.setScale(0, RoundingMode.HALF_UP).add(ivaSobre(neto));
    }

    public UUID getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getClaveHash() {
        return claveHash;
    }

    public String getClaveTexto() {
        return claveTexto;
    }

    public String getClientName() {
        return clientName;
    }

    public String getClientEmail() {
        return clientEmail;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getSelectedOptionId() {
        return selectedOptionId;
    }

    public Instant getSelectedAt() {
        return selectedAt;
    }

    /** Estado crudo persistido. Para mostrar al usuario usa {@link #statusAt(Instant)}. */
    public QuoteStatus getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }

    public Instant getStaleAlertedAt() {
        return staleAlertedAt;
    }

    public Instant getSendFailedAt() {
        return sendFailedAt;
    }

    public String getSendFailureReason() {
        return sendFailureReason;
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

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public int getIvaPct() {
        return ivaPct;
    }

    public List<QuoteOption> getOptions() {
        return options;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getMensaje() {
        return mensaje;
    }

    public String getImagenes() {
        return imagenes;
    }
}
