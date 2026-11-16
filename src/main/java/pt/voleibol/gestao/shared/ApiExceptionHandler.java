package pt.voleibol.gestao.shared;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Formato único de erro da API: RFC 9457 ({@code application/problem+json}).
 *
 * <p>Os serviços lançam {@link ResponseStatusException} com a mensagem em português que o cliente
 * pode mostrar; essa mensagem viaja no campo {@code detail} e o {@code title} é o resumo do estado.
 * Este é o contrato em que o cliente se apoia ({@code frontend/src/api.ts}), por isso não se muda
 * sem mudar também o cliente.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        return respond(problem(ex.getStatusCode(), ex.getReason(), request));
    }

    /** Campos que falharam a validação do corpo, por nome do campo. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Corpo do pedido inválido", request);
        problem.setProperty("errors", errors);
        return respond(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "Corpo do pedido inválido ou mal formado", request));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException ex,
                                                         HttpServletRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "Parâmetro obrigatório em falta: " + ex.getParameterName(), request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "Parâmetro inválido: " + ex.getName(), request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(problem(HttpStatus.NOT_FOUND, "Recurso não encontrado", request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                         HttpServletRequest request) {
        return respond(problem(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido neste recurso", request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return respond(problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de conteúdo não suportado", request));
    }

    private static ProblemDetail problem(HttpStatusCode status, String message, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                message == null || message.isBlank() ? title(status) : message);
        problem.setTitle(title(status));
        instance(request).ifPresent(problem::setInstance);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static Optional<URI> instance(HttpServletRequest request) {
        String path = request == null ? null : request.getRequestURI();
        if (path == null || path.isBlank()) return Optional.empty();
        try {
            return Optional.of(URI.create(path));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Resumo curto do estado, em português: serve de título e de mensagem quando não há detalhe. */
    private static String title(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Pedido inválido";
            case 401 -> "Autenticação necessária";
            case 403 -> "Acesso negado";
            case 404 -> "Não encontrado";
            case 405 -> "Método não permitido";
            case 409 -> "Conflito com o estado atual";
            case 415 -> "Tipo de conteúdo não suportado";
            case 422 -> "Dados inválidos";
            case 429 -> "Demasiados pedidos";
            case 502, 503 -> "Serviço indisponível";
            default -> status.is5xxServerError() ? "Erro interno" : "Pedido falhou";
        };
    }
}
