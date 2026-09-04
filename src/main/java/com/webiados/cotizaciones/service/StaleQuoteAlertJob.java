package com.webiados.cotizaciones.service;

import com.webiados.cotizaciones.config.AppProperties;
import com.webiados.cotizaciones.domain.Quote;
import com.webiados.cotizaciones.repo.QuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Avisa cuando una cotización se envió (o se abrió) y pasaron {@code app.stale-alert.days} sin
 * que el cliente eligiera ni rechazara. Es lo que habría hecho visible, en su momento, que tres
 * oportunidades de venta reales murieron después de dar el precio sin que nadie se enterara de
 * si las habían mirado.
 *
 * <p><strong>Apagada por defecto</strong> (regla 13) — {@code QUOTE_STALE_ALERT_ENABLED=false}.
 * Nada se enciende porque exista el código.
 *
 * <p><strong>La primera vez que se activa, siembra en silencio.</strong> Si ya había
 * cotizaciones viejas sin respuesta antes de prender la alerta, la primera pasada las marca
 * como revisadas sin mandar ningún correo — una alerta que nace con veinte avisos atrasados de
 * golpe nace ignorada. Se detecta la "primera vez" comprobando si alguna cotización tiene
 * {@code staleAlertedAt} no nulo: si ninguna lo tiene todavía, esta pasada es la siembra.
 */
@Component
public class StaleQuoteAlertJob {

    private static final Logger log = LoggerFactory.getLogger(StaleQuoteAlertJob.class);

    private final QuoteRepository quoteRepo;
    private final EmailService emailService;
    private final AppProperties props;

    public StaleQuoteAlertJob(QuoteRepository quoteRepo, EmailService emailService, AppProperties props) {
        this.quoteRepo = quoteRepo;
        this.emailService = emailService;
        this.props = props;
    }

    /** 09:00 todos los días, hora del servidor. */
    @Scheduled(cron = "0 0 9 * * *")
    public void run() {
        check(Instant.now());
    }

    /**
     * Sin {@code @Transactional} a propósito, no por descuido — tenía una puesta y se sacó el
     * 2026-09-04. {@code run()} la llama como {@code check(...)}, bare, misma clase: eso bypasea
     * el proxy de Spring, así que la anotación no envolvía nada de verdad — quedaba decorativa,
     * y una anotación decorativa es peor que no tenerla, porque el próximo que lea esto va a
     * creer que hay una transacción y va a construir encima suponiéndolo.
     *
     * <p>Y aunque se pudiera envolver de verdad (extrayendo esto a un bean aparte, mismo patrón
     * que {@code SendFailureRecorder}), no es lo que conviene acá: mantendría una transacción
     * abierta durante todo el lote, y "si el proceso se cae a mitad de la corrida, algunas
     * cotizaciones quedan marcadas/avisadas y otras no" es el comportamiento correcto para un
     * barrido de alertas — cada cotización marcada ya avisó; revertirla porque la siguiente
     * falló sería peor, no mejor. Cada {@code quoteRepo.save(quote)} de abajo abre su propia
     * transacción por llamada (así funciona Spring Data sin una ambiente), y eso es exactamente
     * lo que se quiere: cada fila se guarda de verdad, y la atomicidad del lote no se busca a
     * propósito.
     */
    void check(Instant now) {
        if (!props.staleAlert().enabled()) {
            return;
        }
        var limite = now.minus(props.staleAlert().days(), ChronoUnit.DAYS);
        var candidatas = quoteRepo.findStaleCandidates(limite);
        if (candidatas.isEmpty()) {
            // Late latido: sin esto, una corrida que no encuentra nada no deja rastro, y no hay
            // forma de distinguir "no había vencidas" de "el job dejó de correr".
            log.info("Chequeo de cotizaciones sin respuesta: corrió, 0 candidatas");
            return;
        }

        boolean esLaPrimeraVez = !quoteRepo.existsByStaleAlertedAtIsNotNull();
        for (Quote quote : candidatas) {
            quote.markStaleAlerted(now);
            quoteRepo.save(quote); // cada save es su propia transacción — ver el Javadoc de arriba
            if (!esLaPrimeraVez) {
                long dias = ChronoUnit.DAYS.between(referencia(quote), now);
                emailService.notifyStale(quote, dias);
            }
        }

        if (esLaPrimeraVez) {
            log.info("Aviso de cotización sin respuesta activado por primera vez: {} cotizaciones "
                    + "viejas sembradas en silencio, sin avisar", candidatas.size());
        } else {
            log.info("Chequeo de cotizaciones sin respuesta: corrió, {} candidatas avisadas", candidatas.size());
        }
    }

    /** Desde cuándo cuenta el plazo: cuándo se envió, o si nunca se marcó, cuándo se abrió. */
    private static Instant referencia(Quote quote) {
        return quote.getSentAt() != null ? quote.getSentAt() : quote.getUnlockedAt();
    }
}
