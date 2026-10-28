package ao.kixima.common.error;

import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sem Spring nem base de dados: o que cada excepção do Spring MVC e do acesso a dados vira no envelope.
 * A regra: erro do CLIENTE nunca é 500; base de dados inacessível é 503 (não 500); um bug a sério continua 500.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new MockEnvironment(), new SentryReporter());
    private final MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/exemplo");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldErrors(ResponseEntity<ErrorResponse> r) {
        Map<String, Object> details = (Map<String, Object>) r.getBody().error().details();
        return (Map<String, Object>) details.get("fieldErrors");
    }

    @Test
    void parametroEmFaltaDa422ComOCampo() {
        var r = handler.handleMissingParameter(new MissingServletRequestParameterException("ano", "String"), req);
        assertEquals(422, r.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", r.getBody().error().code());
        assertTrue(fieldErrors(r).containsKey("ano"));
    }

    @Test
    void tipoErradoDa422() {
        var ex = new TypeMismatchException("abc", Integer.class);
        ex.initPropertyName("page");
        var r = handler.handleTypeMismatch(ex, req);
        assertEquals(422, r.getStatusCode().value());
        assertTrue(fieldErrors(r).containsKey("page"));
    }

    @Test
    void contentTypeNaoSuportadoDa422() {
        var r = handler.handleMediaTypeNotSupported(new HttpMediaTypeNotSupportedException("text/plain"), req);
        assertEquals(422, r.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", r.getBody().error().code());
    }

    @Test
    void metodoNaoPermitidoDa404DeRotaInexistente() {
        var r = handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE"), req);
        assertEquals(404, r.getStatusCode().value());
        assertEquals("ROUTE_NOT_FOUND", r.getBody().error().code());
        assertTrue(r.getBody().error().message().contains("DELETE"));
    }

    @Test
    void baseDeDadosInacessivelDa503ComCodigoEMensagemDoCatalogo() {
        // O que o Hikari lança quando a base está parada ou o pooler recusa: um erro de ligação embrulhado pelo Spring.
        var causa = new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 5000ms");
        var r = handler.handleUnexpected(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction", causa), req);
        assertEquals(503, r.getStatusCode().value());
        assertEquals("SERVICO_INDISPONIVEL", r.getBody().error().code());
        assertFalse(r.getBody().error().message().contains("Hikari"), "a mensagem para o cliente não pode expor o interior do pool");
    }

    @Test
    void variasFormasDeFalhaDeLigacaoSaoBaseIndisponivel() {
        assertTrue(GlobalExceptionHandler.baseIndisponivel(new DataAccessResourceFailureException("x")));
        assertTrue(GlobalExceptionHandler.baseIndisponivel(new JDBCConnectionException("x", new SQLException("x", "08006"))));
        // Pooler sem o utilizador / senha recusada (classe 28) e ligações a mais (53300).
        assertTrue(GlobalExceptionHandler.baseIndisponivel(new RuntimeException(new SQLException("FATAL: password authentication failed", "28P01"))));
        assertTrue(GlobalExceptionHandler.baseIndisponivel(new RuntimeException(new SQLException("too many connections", "53300"))));
    }

    @Test
    void umBugOuUmErroDeSqlNormalContinuaA500() {
        assertFalse(GlobalExceptionHandler.baseIndisponivel(new NullPointerException("bug")));
        assertFalse(GlobalExceptionHandler.baseIndisponivel(new RuntimeException(new SQLException("duplicate key", "23505"))));
        var r = handler.handleUnexpected(new IllegalStateException("bug a sério"), req);
        assertEquals(500, r.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", r.getBody().error().code());
    }
}
