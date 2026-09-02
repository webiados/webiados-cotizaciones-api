package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.domain.QuoteOption;
import com.webiados.cotizaciones.dto.admin.CreateQuoteRequest;
import com.webiados.cotizaciones.dto.admin.CreateQuoteResponse;
import com.webiados.cotizaciones.dto.admin.OptionRequest;
import com.webiados.cotizaciones.dto.admin.QuoteAdminDetail;
import com.webiados.cotizaciones.dto.admin.QuoteAdminSummary;
import com.webiados.cotizaciones.dto.admin.UpdateQuoteRequest;
import com.webiados.cotizaciones.dto.client.QuoteClientView;
import com.webiados.cotizaciones.repo.QuoteRepository;
import com.webiados.cotizaciones.repo.SelectionRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class QuoteService {

    private final QuoteRepository quoteRepo;
    private final SelectionRepository selectionRepo;
    private final PasswordEncoder passwordEncoder;
    private final CodeGenerator codeGenerator;
    private final QuoteMapper mapper;
    private final AppProperties props;
    private final EmailService emailService;

    public QuoteService(QuoteRepository quoteRepo, SelectionRepository selectionRepo,
                        PasswordEncoder passwordEncoder, CodeGenerator codeGenerator,
                        QuoteMapper mapper, AppProperties props, EmailService emailService) {
        this.quoteRepo = quoteRepo;
        this.selectionRepo = selectionRepo;
        this.passwordEncoder = passwordEncoder;
        this.codeGenerator = codeGenerator;
        this.mapper = mapper;
        this.props = props;
        this.emailService = emailService;
    }

    @Transactional
    public CreateQuoteResponse create(CreateQuoteRequest req) {
        String codigo = codeGenerator.generateCodigo();
        String clave = codeGenerator.generateClave();
        String claveHash = passwordEncoder.encode(clave);

        Instant now = Instant.now();
        // Solo el histórico informa createdAt; una cotización nueva se fecha sola.
        Instant createdAt = req.createdAt() != null ? req.createdAt() : now;
        if (createdAt.isAfter(now)) {
            throw new IllegalArgumentException("La fecha de emisión no puede estar en el futuro");
        }
        Instant expiresAt = createdAt.plus(props.quote().validityDays(), ChronoUnit.DAYS);

        // Normalizamos el nombre al guardar (no al mostrar), para que salga bien en el correo,
        // en la landing y en el panel a la vez. "felipe" → "Felipe".
        var quote = new Quote(UUID.randomUUID(), codigo, claveHash, clave,
                Formatos.nombre(req.clientName()), req.clientEmail(), req.notes(), createdAt, expiresAt,
                req.titulo(), req.mensaje(), req.imagenes());

        // Un borrador nacido de un lead puede no traer opciones todavía; el vendedor las agrega
        // después. `send` ya se niega a enviar una cotización sin opciones.
        if (req.options() != null) {
            int index = 0;
            for (OptionRequest optReq : req.options()) {
                quote.addOption(newOption(optReq, index++));
            }
        }

        quoteRepo.save(quote);

        return new CreateQuoteResponse(quote.getId(), codigo, clave, publicUrl(codigo));
    }

    private QuoteOption newOption(OptionRequest req, int orderIndex) {
        return new QuoteOption(
                UUID.randomUUID(), orderIndex,
                req.titulo(), req.descripcion(),
                req.precio(), req.precioMensual(),
                req.currency() != null ? req.currency() : "CLP",
                req.recomendado(),
                req.features(),
                req.pricingRef(),
                req.planSinPieMeses()
        );
    }

    /** URL de la landing del cliente. La sirve el frontend, no este servicio. */
    public String publicUrl(String codigo) {
        return props.quote().publicBaseUrl() + "/" + codigo;
    }

    /**
     * Marca la cotización como enviada y se la manda por correo al cliente.
     *
     * <p>El correo va <em>antes</em> de persistir el estado: si el envío falla, la
     * transacción se revierte y la cotización no queda marcada como enviada. Una SENT sin
     * correo enviado falsearía la tasa de cierre, que es justo el dato que esto existe
     * para producir.
     */
    @Transactional
    public QuoteAdminDetail send(UUID id) {
        var quote = quoteRepo.findWithOptionsById(id)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));

        if (quote.getOptions().isEmpty()) {
            throw new IllegalStateException("La cotización no tiene opciones; no se puede enviar");
        }

        emailService.sendQuoteToClient(quote, publicUrl(quote.getCodigo()));
        quote.markSent(Instant.now());

        var history = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(id);
        return mapper.toDetail(quote, history, Instant.now());
    }

    /**
     * Registra una entrega hecha fuera del sistema, sin mandar ningún correo.
     *
     * <p>Existe por dos motivos: cargar el histórico —cotizaciones que ya se enviaron en
     * PDF— sin volver a escribirle a un cliente que hace semanas la recibió, y cubrir el
     * caso normal de entregarla por WhatsApp o en una reunión.
     */
    @Transactional
    public QuoteAdminDetail markSentManually(UUID id, Instant sentAt) {
        var quote = quoteRepo.findWithOptionsById(id)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
        quote.recordManualDelivery(sentAt != null ? sentAt : Instant.now());
        var history = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(id);
        return mapper.toDetail(quote, history, Instant.now());
    }

    /** Registra que el cliente dijo que no. Sin esto, un "no" es indistinguible de silencio. */
    @Transactional
    public QuoteAdminDetail reject(UUID id) {
        var quote = quoteRepo.findWithOptionsById(id)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
        quote.markRejected(Instant.now());
        var history = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(id);
        return mapper.toDetail(quote, history, Instant.now());
    }

    /**
     * Agrega una opción a una cotización que ya existe, sin tener que rehacerla —
     * rehacerla cambiaría el código y la clave que el cliente ya tiene.
     */
    @Transactional
    public QuoteAdminDetail addOption(UUID quoteId, OptionRequest req) {
        var quote = quoteRepo.findWithOptionsById(quoteId)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
        quote.addOption(newOption(req, quote.getOptions().size()));
        var history = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(quoteId);
        return mapper.toDetail(quote, history, Instant.now());
    }

    public List<QuoteAdminSummary> listAll() {
        Instant now = Instant.now();
        return quoteRepo.findAllByOrderByCreatedAtDesc().stream().map(q -> {
            Map<String, String> titles = q.getOptions().stream()
                    .collect(Collectors.toMap(o -> o.getId().toString(), QuoteOption::getTitulo));
            return mapper.toSummary(q, now, titles);
        }).toList();
    }

    public QuoteAdminDetail getDetail(UUID id) {
        var quote = quoteRepo.findWithOptionsById(id)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
        var history = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(id);
        return mapper.toDetail(quote, history, Instant.now());
    }

    @Transactional
    public QuoteAdminDetail updateQuote(UUID id, UpdateQuoteRequest req) {
        var quote = quoteRepo.findWithOptionsById(id)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
        quote.updateMeta(req.titulo(), req.mensaje(), req.notes(), req.imagenes(),
                req.expiresAt());
        var history = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(id);
        return mapper.toDetail(quote, history, Instant.now());
    }

    @Transactional
    public QuoteAdminDetail updateOption(UUID quoteId, UUID optionId, OptionRequest req) {
        var quote = quoteRepo.findWithOptionsById(quoteId)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
        var option = quote.getOptions().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Opción no encontrada"));
        option.update(req.titulo(), req.descripcion(), req.precio(), req.precioMensual(),
                req.currency(), req.recomendado(), req.features(), req.pricingRef(),
                req.planSinPieMeses());
        var history = selectionRepo.findByQuoteIdOrderByCreatedAtAsc(quoteId);
        return mapper.toDetail(quote, history, Instant.now());
    }

    /**
     * Borra una opción.
     *
     * <p>Se niega si es la que el cliente eligió: en base de datos, borrar la opción
     * arrastra por CASCADE las filas de {@code selection} que registran esa elección y
     * deja la cotización de vuelta en "sin elegir". Es decir, un solo DELETE podía borrar
     * el hecho de que una cotización fue aceptada, sin avisar. Eso es pérdida de datos.
     */
    @Transactional
    public void deleteOption(UUID quoteId, UUID optionId) {
        var quote = quoteRepo.findWithOptionsById(quoteId)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));

        if (optionId.equals(quote.getSelectedOptionId())) {
            throw new IllegalStateException(
                    "Esa es la opción que el cliente eligió: borrarla perdería el registro de "
                            + "la aceptación. Si de verdad hay que quitarla, primero hay que "
                            + "resolver la cotización a mano.");
        }

        boolean removed = quote.getOptions().removeIf(o -> o.getId().equals(optionId));
        if (!removed) throw new NoSuchElementException("Opción no encontrada");

        // Reindexa para que el orden no quede con huecos.
        int i = 0;
        for (QuoteOption o : quote.getOptions()) {
            o.setOrderIndex(i++);
        }
    }

    public Quote findByCodigo(String codigo) {
        return quoteRepo.findByCodigo(codigo)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
    }

    public QuoteClientView getClientViewByCodigo(String codigo) {
        var quote = quoteRepo.findByCodigo(codigo)
                .orElseThrow(() -> new NoSuchElementException("Cotización no encontrada"));
        return mapper.toClientView(quote, Instant.now());
    }

    /**
     * Registra que el cliente puso la clave correcta — se llama tras un {@code unlock}
     * exitoso. Silenciosa a propósito: si la cotización no existe, {@code unlock} ya
     * devolvió 401 antes de llegar acá, y esto no debe poder hacer fallar un login válido.
     */
    @Transactional
    public void recordUnlock(String codigo) {
        quoteRepo.findByCodigo(codigo).ifPresent(q -> q.markUnlocked(Instant.now()));
    }
}
