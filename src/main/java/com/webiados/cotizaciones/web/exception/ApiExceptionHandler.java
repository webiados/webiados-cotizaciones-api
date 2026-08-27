package com.webiados.cotizaciones.web.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Error inesperado", ex);
        var detail = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        detail.setTitle("Error interno");
        detail.setDetail("Ocurrió un error inesperado");
        return detail;
    }
}
