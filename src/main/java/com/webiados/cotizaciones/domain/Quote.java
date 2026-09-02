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
