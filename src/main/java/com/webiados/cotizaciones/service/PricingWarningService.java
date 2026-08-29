package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.dto.pricing.ItemPrecio;
import com.webiados.cotizaciones.dto.pricing.PricingCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Candado <strong>suave</strong>: avisa cuando el precio de una opción no calza con el catálogo
 * del Core, sin bloquear el guardado ni la lectura.
 *
 * <p>Solo compara opciones con {@code pricingRef} — el slug/nombre del ítem del catálogo que el
 * panel dejó al armar la opción eligiendo del catálogo, nunca tecleado a mano. Una opción sin
 * {@code pricingRef} (armada combinando kits, con un descuento negociado, con un precio a medida)
 * no se compara contra nada: es la mitad de los casos y es exactamente para eso que existe una
 * cotización en vez de un catálogo que se aplica solo.
 *
 * <p>Un candado por igualdad exacta acá sería falso, no estricto: {@code QuoteOption} no tiene
 * FK al catálogo, así que no hay "el" ítem contra el que comparar salvo que el panel lo haya
 * dejado dicho. Por eso esto es un aviso, no una validación — y por eso, si el Core no responde,
 * calla en vez de romper la lectura de una cotización que no tiene nada que ver con esto.
 */
@Service
public class PricingWarningService {

    private static final Logger log = LoggerFactory.getLogger(PricingWarningService.class);

    private final Supplier<PricingCatalog> pricing;

    @Autowired
    public PricingWarningService(PricingClient pricingClient) {
        this(pricingClient::get);
    }

    /** Para pruebas: inyectar un catálogo fijo o una falla, sin levantar HTTP. */
    PricingWarningService(Supplier<PricingCatalog> pricing) {
        this.pricing = pricing;
    }

    public List<String> check(List<QuoteOption> options) {
        boolean algunaConRef = options.stream()
                .anyMatch(o -> o.getPricingRef() != null && !o.getPricingRef().isBlank());
        if (!algunaConRef) {
            return List.of();
        }

        PricingCatalog catalogo;
        try {
            catalogo = pricing.get();
        } catch (Exception ex) {
            // Advisory, no crítico: si el Core no responde, la cotización se sigue viendo igual.
            log.warn("No se pudo comparar precios contra el catálogo del Core; sigo sin avisos", ex);
            return List.of();
        }

        List<String> warnings = new ArrayList<>();
        for (QuoteOption option : options) {
            String ref = option.getPricingRef();
            if (ref == null || ref.isBlank()) {
                continue;
            }
            ItemPrecio item = buscar(catalogo, ref);
            if (item == null) {
                warnings.add("«%s»: no se encontró «%s» en el catálogo actual — puede que haya "
                        .formatted(option.getTitulo(), ref)
                        + "cambiado de nombre o se haya retirado.");
                continue;
            }
            comparar(option.getTitulo(), "de instalación", item.setup(), option.getPrecio(), warnings);
            comparar(option.getTitulo(), "mensuales", item.mensual(), option.getPrecioMensual(), warnings);
        }
        return warnings;
    }

    private void comparar(String titulo, String etiqueta, BigDecimal delCatalogo, BigDecimal deLaOpcion,
                           List<String> warnings) {
        if (delCatalogo == null || deLaOpcion == null) {
            return; // Sin los dos montos no hay nada que comparar.
        }
        if (delCatalogo.compareTo(deLaOpcion) != 0) {
            warnings.add("«%s»: el catálogo hoy dice %s %s, esta opción dice %s.".formatted(
                    titulo, Formatos.moneda(delCatalogo), etiqueta, Formatos.moneda(deLaOpcion)));
        }
    }

    private ItemPrecio buscar(PricingCatalog catalogo, String ref) {
        for (List<ItemPrecio> familia : List.of(
                nonNull(catalogo.kits()), nonNull(catalogo.landings()),
                nonNull(catalogo.addons()), nonNull(catalogo.identidad()))) {
            for (ItemPrecio item : familia) {
                if (ref.equals(item.slug()) || ref.equals(item.nombre())) {
                    return item;
                }
            }
        }
        return null;
    }

    private static List<ItemPrecio> nonNull(List<ItemPrecio> lista) {
        return lista != null ? lista : List.of();
    }
}
