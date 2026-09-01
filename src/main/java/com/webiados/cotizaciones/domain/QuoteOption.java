package com.webiados.cotizaciones.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "quote_option")
public class QuoteOption {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false)
    private Quote quote;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(nullable = false)
    private String titulo;

    @Column(columnDefinition = "text")
    private String descripcion;

    /** Precio de instalación / desarrollo. Pago único. Neto, sin IVA. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal precio;

    /**
     * Precio recurrente mensual, neto y sin IVA. {@code null} = esta opción no tiene
     * mensualidad (distinto de 0, que sería una mensualidad gratuita explícita).
     */
    @Column(name = "precio_mensual", precision = 14, scale = 2)
    private BigDecimal precioMensual;

    @Column(nullable = false, length = 3)
    private String currency = "CLP";

    @Column(nullable = false)
    private boolean recomendado = false;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "quote_option_feature", joinColumns = @JoinColumn(name = "option_id"))
    @OrderColumn(name = "position")
    @Column(name = "feature", length = 500, nullable = false)
    private List<String> features = new ArrayList<>();

    /**
     * Slug o nombre del ítem del catálogo del Core del que salió esta opción — {@code null} si
     * se armó a mano (combinada, negociada, a medida). Lo llena <strong>solo</strong> el panel
     * al elegir del catálogo; nunca se teclea. Sirve para el aviso de {@link PricingWarningService}
     * cuando el precio guardado ya no calza con lo que el Core publica hoy — no es una FK ni se
     * valida acá: si no calza con nada, es solo un aviso, no un error.
     */
    @Column(name = "pricing_ref")
    private String pricingRef;

    /**
     * Meses del plan sin pie de esta opción — {@code null} si la opción no es un plan sin pie.
     * Dato, no texto: sin un campo propio, "dura los meses que se indican en esta cotización"
     * no indica nada en ninguna parte, y nadie puede filtrar, contar ni avisar sobre un plazo
     * que solo vive dentro de un párrafo. Se compara contra {@code item.planSinPie().meses()}
     * del catálogo igual que el resto de los montos, con el mismo aviso no bloqueante.
     */
    @Column(name = "plan_sin_pie_meses")
    private Integer planSinPieMeses;

    protected QuoteOption() {
    }

    public QuoteOption(UUID id, int orderIndex, String titulo, String descripcion,
                       BigDecimal precio, BigDecimal precioMensual, String currency,
                       boolean recomendado, List<String> features) {
        this(id, orderIndex, titulo, descripcion, precio, precioMensual, currency, recomendado,
                features, null, null);
    }

    public QuoteOption(UUID id, int orderIndex, String titulo, String descripcion,
                       BigDecimal precio, BigDecimal precioMensual, String currency,
                       boolean recomendado, List<String> features, String pricingRef) {
        this(id, orderIndex, titulo, descripcion, precio, precioMensual, currency, recomendado,
                features, pricingRef, null);
    }

    public QuoteOption(UUID id, int orderIndex, String titulo, String descripcion,
                       BigDecimal precio, BigDecimal precioMensual, String currency,
                       boolean recomendado, List<String> features, String pricingRef,
                       Integer planSinPieMeses) {
        this.id = id;
        this.orderIndex = orderIndex;
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.precio = precio;
        this.precioMensual = precioMensual;
        this.currency = currency != null ? currency : "CLP";
        this.recomendado = recomendado;
        this.features = features != null ? new ArrayList<>(features) : new ArrayList<>();
        this.pricingRef = pricingRef;
        this.planSinPieMeses = planSinPieMeses;
    }

    public UUID getId() {
        return id;
    }

    public Quote getQuote() {
        return quote;
    }

    void setQuote(Quote quote) {
        this.quote = quote;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public String getTitulo() {
        return titulo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public BigDecimal getPrecio() {
        return precio;
    }

    public BigDecimal getPrecioMensual() {
        return precioMensual;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isRecomendado() {
        return recomendado;
    }

    public List<String> getFeatures() {
        return features;
    }

    public String getPricingRef() {
        return pricingRef;
    }

    public Integer getPlanSinPieMeses() {
        return planSinPieMeses;
    }

    public void update(String titulo, String descripcion, BigDecimal precio,
                       BigDecimal precioMensual, String currency, boolean recomendado,
                       List<String> features, String pricingRef, Integer planSinPieMeses) {
        this.titulo = titulo;
        this.descripcion = descripcion;
        this.precio = precio;
        this.precioMensual = precioMensual;
        this.currency = currency != null ? currency : "CLP";
        this.recomendado = recomendado;
        this.features.clear();
        if (features != null) this.features.addAll(features);
        this.pricingRef = pricingRef;
        this.planSinPieMeses = planSinPieMeses;
    }
}
