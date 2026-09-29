package ao.kixima.common.persistence;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Metade "SQL" do tecto de {@link TectoDeLinhas}: registado no Hibernate
 * ({@code hibernate.session_factory.statement_inspector}, ver
 * {@link ao.kixima.config.TectoDeLinhasConfig}), acrescenta {@code limit N}
 * às leituras feitas dentro de uma chamada a um repositório — o equivalente
 * ao {@code take: TETO_POR_OMISSAO} que a extensão do Prisma injecta.
 */
public class TectoDeLinhasStatementInspector implements StatementInspector {

    private final int tecto;

    public TectoDeLinhasStatementInspector(int tecto) {
        this.tecto = TectoDeLinhas.efectivo(tecto);
    }

    public int tecto() {
        return tecto;
    }

    @Override
    public String inspect(String sql) {
        if (!TectoDeLinhas.dentroDeRepositorio()) return sql;
        return TectoDeLinhas.aplicar(sql, tecto);
    }
}
