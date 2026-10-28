package ao.kixima.common.error;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Espelha backend/src/middleware/errorHandler.js — o envelope
 * {@code {error:{code,message,details?}}}, os mesmos códigos de estado, e a
 * mesma tradução de erros de ORM (Prisma P2002/P2025 → equivalente
 * JPA/Hibernate) e de upload (Multer → MaxUploadSizeExceededException).
 * O que vai para o Sentry é o mesmo que no Node: os 5xx de {@link AppException}
 * e os erros inesperados — nunca os 4xx (ver {@link SentryReporter}).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final boolean isDevelopment;
    private final SentryReporter sentry;

    public GlobalExceptionHandler(org.springframework.core.env.Environment env, SentryReporter sentry) {
        this.isDevelopment = List.of(env.getActiveProfiles()).contains("dev");
        this.sentry = sentry;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex, HttpServletRequest req) {
        if (ex.getStatusCode() >= 500) {
            log.error(ex.getMessage(), ex);
            sentry.captureException(ex, req); // erros de servidor (5xx) — para o Sentry
        } else {
            log.warn("{} [{}] {}", ex.getCode(), req.getRequestURI(), ex.getMessage());
        }
        ErrorResponse body = ex.getDetails() != null
                ? ErrorResponse.of(ex.getCode(), ex.getMessage(), ex.getDetails())
                : ErrorResponse.of(ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** Bean Validation (@Valid em @RequestBody) — equivalente ao zod nas rotas. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        // A mesma forma do `result.error.flatten()` do zod (utils/validate.js): erros de
        // campo agrupados pelo PRIMEIRO segmento do caminho (items[0].quantity → items),
        // erros do objecto em `formErrors`.
        java.util.Map<String, List<String>> fieldErrors = new java.util.LinkedHashMap<>();
        for (var fe : ex.getBindingResult().getFieldErrors()) {
            String campo = fe.getField().split("[.\\[]", 2)[0];
            fieldErrors.computeIfAbsent(campo, k -> new java.util.ArrayList<>()).add(fe.getDefaultMessage());
        }
        List<String> formErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> ge.getDefaultMessage()).collect(Collectors.toList());
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("formErrors", formErrors);
        details.put("fieldErrors", fieldErrors);
        log.warn("VALIDATION_ERROR [{}] {}", req.getRequestURI(), fieldErrors);
        return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "Dados inválidos.", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        log.warn("VALIDATION_ERROR [{}] {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "Dados inválidos."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("VALIDATION_ERROR [{}] corpo do pedido ilegível", req.getRequestURI());
        return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "Dados inválidos."));
    }

    /** Equivalente ao bloco `err.name === 'MulterError'` do Node. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(HttpServletRequest req) {
        log.warn("Upload rejeitado: LIMIT_FILE_SIZE [{}]", req.getRequestURI());
        return ResponseEntity.status(413).body(ErrorResponse.of("LIMIT_FILE_SIZE",
                "O ficheiro é demasiado grande. Reduza o tamanho da imagem e tente novamente."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(HttpServletRequest req) {
        log.warn("Upload rejeitado: campo de ficheiro em falta [{}]", req.getRequestURI());
        return ResponseEntity.status(400).body(ErrorResponse.of("LIMIT_UNEXPECTED_FILE", "Campo de ficheiro inesperado no envio."));
    }

    /** Equivalente a `err.code === 'P2002'` (Prisma) — violação de unicidade. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleUniqueConstraint(HttpServletRequest req) {
        log.warn("UNIQUE_CONSTRAINT [{}]", req.getRequestURI());
        return ResponseEntity.status(409).body(ErrorResponse.of("UNIQUE_CONSTRAINT", "Já existe um registo com estes dados."));
    }

    /** Equivalente a `err.code === 'P2025'` (Prisma) — registo não encontrado numa operação. */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(HttpServletRequest req) {
        log.warn("NOT_FOUND [{}]", req.getRequestURI());
        return ResponseEntity.status(404).body(ErrorResponse.of("NOT_FOUND", "Recurso não encontrado."));
    }

    /** Equivalente a notFoundHandler() no fundo de errorHandler.js. */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleRouteNotFound(NoHandlerFoundException ex) {
        String message = "Rota " + ex.getHttpMethod() + " " + ex.getRequestURL() + " não existe.";
        return ResponseEntity.status(404).body(ErrorResponse.of("ROUTE_NOT_FOUND", message));
    }

    // --- Pedidos inválidos do cliente: nunca devem sair como 500 -------------------------------------
    //
    // Sem estes handlers, o Spring MVC lança estas excepções ANTES de o método do controller correr e
    // elas caíam no fallback abaixo como "500 INTERNAL_ERROR" — um erro do cliente (Content-Type
    // errado, parâmetro em falta, tipo inválido) a parecer uma avaria do servidor, sem pista nenhuma
    // para quem o chamou. No Node cada um destes casos é um 4xx (zod/validate.js ou o 404 de rota).

    /** Equivalente a `validate(zod)` com um corpo que o `express.json()` não leu (Content-Type que não é JSON). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        log.warn("VALIDATION_ERROR [{}] Content-Type não suportado: {}", req.getRequestURI(), ex.getContentType());
        return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "Dados inválidos."));
    }

    /** Parâmetro de query obrigatório em falta — a mesma forma `flatten()` do zod que {@link #handleValidation}. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest req) {
        log.warn("VALIDATION_ERROR [{}] parâmetro em falta: {}", req.getRequestURI(), ex.getParameterName());
        return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "Dados inválidos.",
                detalhesDeCampo(ex.getParameterName(), "Required")));
    }

    /** Parâmetro de caminho/query com o tipo errado (ex.: `?page=abc`, `/:id` não numérico). */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(TypeMismatchException ex, HttpServletRequest req) {
        String campo = ex.getPropertyName() != null ? ex.getPropertyName() : "parametro";
        log.warn("VALIDATION_ERROR [{}] valor inválido para {}", req.getRequestURI(), campo);
        return ResponseEntity.status(422).body(ErrorResponse.of("VALIDATION_ERROR", "Dados inválidos.",
                detalhesDeCampo(campo, "Invalid")));
    }

    /** O Express não tem rota para um método diferente do registado: responde o mesmo 404 de rota inexistente. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        String message = "Rota " + ex.getMethod() + " " + req.getRequestURI() + " não existe.";
        return ResponseEntity.status(404).body(ErrorResponse.of("ROUTE_NOT_FOUND", message));
    }

    private static java.util.Map<String, Object> detalhesDeCampo(String campo, String mensagem) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("formErrors", List.of());
        details.put("fieldErrors", java.util.Map.of(campo, List.of(mensagem)));
        return details;
    }

    // --- Base de dados inacessível: 503, não 500 ---------------------------------------------------
    //
    // Uma falha de LIGAÇÃO (base parada, rede, senha recusada, pooler sem o utilizador, ligações
    // esgotadas) não é um erro do pedido nem um bug: é uma dependência em baixo. Responder 503 diz-o
    // a quem chama (e ao monitor), e a causa real fica no registo — antes saía como o mesmo 500
    // genérico de qualquer bug, sem distinção. O `/ready` já fazia esta distinção (503 `degradado`).

    /** Classes SQLSTATE de "a base não está disponível": 08 ligação, 28 autorização, 53300 ligações a mais, 57P0x paragem. */
    private static final List<String> SQLSTATE_BASE_INDISPONIVEL = List.of("08", "28", "53300", "57P0");

    /** Percorre a cadeia de causas à procura de uma falha de ligação à base (e não de uma falha de SQL). */
    static boolean baseIndisponivel(Throwable ex) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException
                    || t instanceof org.hibernate.exception.JDBCConnectionException
                    || t instanceof java.sql.SQLTransientConnectionException
                    || t instanceof java.sql.SQLNonTransientConnectionException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && SQLSTATE_BASE_INDISPONIVEL.stream().anyMatch(sql.getSQLState()::startsWith)) {
                return true;
            }
        }
        return false;
    }

    /** Fallback — equivalente ao "Erro não tratado" no fundo do errorHandler.js. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        if (baseIndisponivel(ex)) {
            log.error("Base de dados indisponível [{}] {}", req.getRequestURI(), ex.getMessage(), ex);
            sentry.captureException(ex, req);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ErrorResponse.of("SERVICO_INDISPONIVEL",
                    "A base de dados está temporariamente indisponível. Tente novamente dentro de instantes."));
        }
        log.error("Erro não tratado [{}] {}", req.getRequestURI(), ex.getMessage(), ex);
        sentry.captureException(ex, req); // erro inesperado — sempre reportado ao Sentry
        String message = "Ocorreu um erro interno. Tente novamente mais tarde.";
        if (isDevelopment) {
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.withStack("INTERNAL_ERROR", message, sw.toString()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of("INTERNAL_ERROR", message));
    }
}
