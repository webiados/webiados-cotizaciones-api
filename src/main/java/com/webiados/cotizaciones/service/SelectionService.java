package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.domain.Selection;
import com.webiados.cotizaciones.domain.SelectionKind;
import com.webiados.cotizaciones.dto.client.QuoteClientView;
import com.webiados.cotizaciones.repo.QuoteOptionRepository;
import com.webiados.cotizaciones.repo.QuoteRepository;
import com.webiados.cotizaciones.repo.SelectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class SelectionService {

    private static final Logger log = LoggerFactory.getLogger(SelectionService.class);

    private final QuoteRepository quoteRepo;
    private final QuoteOptionRepository optionRepo;
    private final SelectionRepository selectionRepo;
    private final QuoteMapper mapper;
    private final EmailService emailService;
    private final SelectionResendIdRecorder resendIdRecorder;

    public SelectionService(QuoteRepository quoteRepo, QuoteOptionRepository optionRepo,
                            SelectionRepository selectionRepo, QuoteMapper mapper,
                            EmailService emailService, SelectionResendIdRecorder resendIdRecorder) {
        this.quoteRepo = quoteRepo;
        this.optionRepo = optionRepo;
        this.selectionRepo = selectionRepo;
        this.mapper = mapper;
        this.emailService = emailService;
        this.resendIdRecorder = resendIdRecorder;
    }

    @Transactional
    public QuoteClientView select(String codigo, UUID optionId) {
        Instant now = Instant.now();

        var quote = quoteRepo.findByCodigo(codigo)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));

        if (!quote.canSelect(now)) {
            throw new IllegalStateException("Esta cotización ha expirado");
        }

        QuoteOption option = optionRepo.findById(optionId)
                .orElseThrow(() -> new NoSuchElementException("Opción no encontrada"));

        if (!option.getQuote().getId().equals(quote.getId())) {
            throw new IllegalArgumentException("La opción no pertenece a esta cotización");
        }

        SelectionKind kind = quote.getSelectedOptionId() == null
                ? SelectionKind.INITIAL
                : SelectionKind.UPGRADE;

        var selection = new Selection(UUID.randomUUID(), quote, option, kind, now);
        selectionRepo.save(selection);
        quote.recordSelection(optionId, now);
        quoteRepo.save(quote);

        UUID selectionId = selection.getId();
        // notifySelection es @Async: esto no bloquea la respuesta al cliente. El id que Resend
        // devuelva se guarda cuando llegue, en su propia transacción real (SelectionResendIdRecorder,
        // bean aparte a propósito — ver su Javadoc) — es la llave real para que un rebote futuro
        // de ESTE aviso calce con ESTA selección (no con la cotización en general, que puede
        // tener varias).
        emailService.notifySelection(quote, option, kind)
                .thenAccept(resendId -> {
                    if (resendId != null) {
                        resendIdRecorder.record(selectionId, resendId);
                    }
                })
                // Sin esto, un fallo acá (de cualquier tipo) desaparece en silencio: .thenAccept
                // no pasa por el manejador de excepciones de @Async, así que nada lo loguea por
                // sí solo — exactamente el problema que esto existe para evitar en primer lugar.
                .exceptionally(ex -> {
                    log.error("No se pudo guardar el resendEmailId de la selección {}", selectionId, ex);
                    return null;
                });

        return mapper.toClientView(quote, now);
    }

    /**
     * Registra que Resend avisó, por webhook, que el aviso interno de una selección rebotó de
     * verdad — y avisa a quien puede llamar por teléfono con el mensaje correcto: la aceptación
     * no se perdió, lo que se perdió es que alguien se enterara a tiempo.
     *
     * @return true si el id calzó con una selección real; false si no (un correo distinto a
     *         NOTIFY_TO, o de otro entorno) — el llamador lo loguea para que el volumen de
     *         "no calzó" también quede visible.
     */
    @Transactional
    public boolean recordBounce(String resendEmailId, String motivo) {
        return selectionRepo.findByResendEmailId(resendEmailId)
                .map(s -> {
                    s.markBounced(Instant.now(), motivo);
                    emailService.notifySelectionBounce(s.getQuote(), s.getOption(), motivo);
                    return true;
                })
                .orElse(false);
    }
}
