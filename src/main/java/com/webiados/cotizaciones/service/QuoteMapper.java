package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.domain.Selection;
import com.webiados.cotizaciones.dto.admin.QuoteAdminDetail;
import com.webiados.cotizaciones.dto.admin.QuoteAdminSummary;
import com.webiados.cotizaciones.dto.admin.SelectionHistoryEntry;
import com.webiados.cotizaciones.dto.client.OptionClientView;
import com.webiados.cotizaciones.dto.client.QuoteClientView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class QuoteMapper {

    private final PricingWarningService pricingWarnings;

    public QuoteMapper(PricingWarningService pricingWarnings) {
        this.pricingWarnings = pricingWarnings;
    }

    public QuoteClientView toClientView(Quote quote, Instant now) {
        var options = quote.getOptions().stream().map(o -> toOptionView(quote, o)).toList();
        return new QuoteClientView(
                quote.getClientName(),
                quote.canSelect(now),
                quote.isExpired(now),
                quote.getExpiresAt(),
                quote.getSelectedOptionId(),
                quote.getTitulo(),
                quote.getMensaje(),
                quote.getImagenes(),
                quote.getIvaPct(),
                options
        );
    }

    public QuoteAdminSummary toSummary(Quote quote, Instant now, Map<String, String> optionTituloById) {
        String selectedTitulo = quote.getSelectedOptionId() != null
                ? optionTituloById.get(quote.getSelectedOptionId().toString())
                : null;
        return new QuoteAdminSummary(
                quote.getId(),
                quote.getCodigo(),
                quote.getClientName(),
                quote.getClientEmail(),
                quote.statusAt(now),
                selectedTitulo,
                quote.getCreatedAt(),
                quote.getExpiresAt(),
                quote.getSentAt(),
                quote.getSelectedAt(),
                quote.getRejectedAt()
        );
    }

    public QuoteAdminDetail toDetail(Quote quote, List<Selection> history, Instant now) {
        var options = quote.getOptions().stream().map(o -> toOptionView(quote, o)).toList();
        var optionMap = quote.getOptions().stream()
                .collect(Collectors.toMap(o -> o.getId().toString(), QuoteOption::getTitulo));
        var historyEntries = history.stream().map(s -> new SelectionHistoryEntry(
                s.getId(),
                s.getOption().getId(),
                optionMap.getOrDefault(s.getOption().getId().toString(), "—"),
                s.getKind(),
                s.getCreatedAt()
        )).toList();
        return new QuoteAdminDetail(
                quote.getId(),
                quote.getCodigo(),
                quote.getClaveTexto(),
                quote.getClientName(),
                quote.getClientEmail(),
                quote.getNotes(),
                quote.getTitulo(),
                quote.getMensaje(),
                quote.getImagenes(),
                quote.statusAt(now),
                quote.canSelect(now),
                quote.getCreatedAt(),
                quote.getExpiresAt(),
                quote.getSentAt(),
                quote.getSelectedOptionId(),
                quote.getSelectedAt(),
                quote.getRejectedAt(),
                quote.getIvaPct(),
                options,
                historyEntries,
                pricingWarnings.check(quote.getOptions())
        );
    }

    /**
     * El IVA se calcula acá, con el porcentaje que trae la cotización, y viaja desglosado.
     * El frontend no calcula impuestos y el histórico conserva la tasa con la que se emitió.
     */
    OptionClientView toOptionView(Quote quote, QuoteOption opt) {
        return new OptionClientView(
                opt.getId(),
                opt.getOrderIndex(),
                opt.getTitulo(),
                opt.getDescripcion(),
                opt.getPrecio(),
                quote.ivaSobre(opt.getPrecio()),
                quote.totalConIva(opt.getPrecio()),
                opt.getPrecioMensual(),
                quote.ivaSobre(opt.getPrecioMensual()),
                quote.totalConIva(opt.getPrecioMensual()),
                opt.getCurrency(),
                quote.getIvaPct(),
                opt.isRecomendado(),
                List.copyOf(opt.getFeatures())
        );
    }
}
