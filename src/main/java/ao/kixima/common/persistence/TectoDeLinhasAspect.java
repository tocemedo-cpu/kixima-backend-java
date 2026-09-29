package ao.kixima.common.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.AbstractRepositoryMetadata;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metade "grita" do tecto de {@link TectoDeLinhas}: envolve cada chamada a um
 * repositório Spring Data (o {@code findMany} daqui), abre o âmbito em que o
 * {@link TectoDeLinhasStatementInspector} actua e, quando uma lista volta com
 * o tecto cheio, regista o MESMO erro que database.js — quase de certeza foi
 * cortada, e é este aviso que impede o tecto de se tornar uma fonte de
 * números errados.
 *
 * Tal como no Node ({@code args.take !== undefined}), uma chamada com
 * {@code Pageable}/{@code Limit} explícito é responsabilidade do chamador e
 * não avisa.
 */
@Aspect
@Component
public class TectoDeLinhasAspect {

    private static final Logger log = LoggerFactory.getLogger(TectoDeLinhasAspect.class);

    private final int tecto;
    private final Map<Class<?>, String> modelos = new ConcurrentHashMap<>();

    public TectoDeLinhasAspect(@Value("${kixima.db.max-rows:" + TectoDeLinhas.POR_OMISSAO + "}") int tecto) {
        this.tecto = TectoDeLinhas.efectivo(tecto);
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..))")
    public Object comTecto(ProceedingJoinPoint jp) throws Throwable {
        TectoDeLinhas.entrar();
        Object resultado;
        try {
            resultado = jp.proceed();
        } finally {
            TectoDeLinhas.sair();
        }
        if (resultado instanceof Collection<?> lista && lista.size() >= tecto && !comLimiteExplicito(jp.getArgs())) {
            log.error("Leitura de {} atingiu o tecto de {} linhas e foi truncada. "
                            + "Qualquer total calculado a partir daqui está ERRADO. "
                            + "Acrescente paginação a este endpoint ou um take explícito. modelo={} onde={}",
                    modelo(jp.getTarget()), tecto, modelo(jp.getTarget()), origemDaChamada());
        }
        return resultado;
    }

    private static boolean comLimiteExplicito(Object[] args) {
        return Arrays.stream(args).anyMatch(a -> a instanceof Pageable || a instanceof Limit);
    }

    /** O nome do modelo como o Prisma o diz ({@code purchaseOrder}), a partir do tipo de domínio do repositório. */
    private String modelo(Object alvo) {
        if (alvo == null) return "?";
        return modelos.computeIfAbsent(alvo.getClass(), classe -> {
            for (Class<?> iface : classe.getInterfaces()) {
                if (Repository.class.isAssignableFrom(iface) && !iface.getName().startsWith("org.springframework.")) {
                    try {
                        String nome = AbstractRepositoryMetadata.getMetadata(iface).getDomainType().getSimpleName();
                        return Character.toLowerCase(nome.charAt(0)) + nome.substring(1);
                    } catch (RuntimeException ignorado) {
                        // sem metadados resolúveis — cai no nome da interface
                    }
                    return iface.getSimpleName().replaceAll("Repository$", "");
                }
            }
            return classe.getSimpleName();
        });
    }

    /**
     * A linha do código que fez a consulta — {@code origemDaChamada()} do
     * Node. Sem isto o aviso diz que "purchaseOrder foi truncado" e deixa
     * quem o lê à procura em dezoito sítios diferentes.
     */
    private static String origemDaChamada() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().startsWith("ao.kixima.")
                        && !f.getClassName().startsWith("ao.kixima.common.persistence."))
                .map(StackWalker.StackFrame::toString)
                .findFirst()
                .orElse(null));
    }
}
