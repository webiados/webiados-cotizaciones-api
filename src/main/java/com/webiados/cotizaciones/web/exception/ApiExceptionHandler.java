package com.webiados.cotizaciones.web.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Datos inválidos");
        detail.setDetail(ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst().orElse("Validación fallida"));
        return detail;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException ex) {
        var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle("No encontrado");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        var detail = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        detail.setTitle("Operación no permitida");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArg(IllegalArgumentException ex) {
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Solicitud inválida");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        // Sin este handler explícito, Exception.class de más abajo intercepta esta excepción
        // de Spring antes que el resolver por defecto y la convierte en 500, ocultando que
        // la ruta existe pero con otro verbo (ej: DELETE en una ruta que solo tiene GET/POST).
        var detail = ProblemDetail.forStatus(HttpStatus.METHOD_NOT_ALLOWED);
        detail.setTitle("Método no soportado");
        detail.setDetail(ex.getMessage());
        return detail;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        // Mismo patrón que el 405 de arriba: sin este handler, Exception.class atrapaba
        // cualquier ResponseStatusException lanzada a mano en un controller (ej: el 403 de
        // ClientQuoteController cuando el código del token no coincide con el de la ruta) y la
        // convertía en 500 — un error ya resuelto, empeorado. Se respeta el status con el que
        // se lanzó, no se hardcodea uno.
        var detail = ProblemDetail.forStatus(ex.getStatusCode());
        detail.setTitle(ex.getStatusCode().toString());
        detail.setDetail(ex.getReason());
        return detail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        // Mismo patrón otra vez: un token de cliente pegándole a un endpoint @PreAuthorize de
        // admin lanza esto durante la invocación del controller — Exception.class lo atrapaba
        // antes de que llegara a convertirse en el 403 que ya le correspondía.
        var detail = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        detail.setTitle("Acceso denegado");
        detail.setDetail("No tienes permiso para esta operación");
        return detail;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // Encontrado en producción real: GET /api/admin/quotes/no-es-un-uuid daba 500 en vez
        // de 400 — el {id} de la ruta espera un UUID, y cualquier cosa que no lo sea (un
        // codigo pegado por error donde iba el id, un typo) lanzaba esto y caía en
        // Exception.class antes de convertirse en el 400 que ya le correspondía.
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Parámetro inválido");
        detail.setDetail("«%s» no es un valor válido para «%s»".formatted(ex.getValue(), ex.getName()));
        return detail;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedBody(HttpMessageNotReadableException ex) {
        // Mismo patrón: un JSON roto en el cuerpo de la petición (typo, cliente a medio
        // terminar) daba 500 en vez de 400 en producción real.
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Cuerpo de la solicitud inválido");
        detail.setDetail("El cuerpo de la solicitud no es JSON válido");
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Error inesperado", ex);
        var detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setTitle("Error interno");
        detail.setDetail("Ocurrió un error inesperado");
        return detail;
    }
}
