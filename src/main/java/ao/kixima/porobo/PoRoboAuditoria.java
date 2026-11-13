package ao.kixima.porobo;

/**
 * Nomes usados no trilho de auditoria (`audit_logs`) pelo módulo do PO Robot. A listagem de actividades
 * ({@code GET /api/po-robot/actividades}) lê exactamente estas acções, por empresa.
 */
public final class PoRoboAuditoria {

    public static final String ENTIDADE_REGRA = "PoRoboRegra";
    public static final String ENTIDADE_PROPOSTA = "PoRoboProposta";
    public static final String ENTIDADE_EXECUCAO = "PoRoboExecucao";

    public static final String REGRA_CRIADA = "PO_ROBO_REGRA_CRIADA";
    public static final String REGRA_ATUALIZADA = "PO_ROBO_REGRA_ATUALIZADA";
    public static final String REGRA_REMOVIDA = "PO_ROBO_REGRA_REMOVIDA";
    public static final String EXECUCAO = "PO_ROBO_EXECUCAO";
    public static final String PROPOSTA_CRIADA = "PO_ROBO_PROPOSTA_CRIADA";
    public static final String PROPOSTA_AJUSTADA = "PO_ROBO_PROPOSTA_AJUSTADA";
    public static final String PROPOSTA_SUBMETIDA = "PO_ROBO_PROPOSTA_SUBMETIDA";
    public static final String PROPOSTA_REJEITADA = "PO_ROBO_PROPOSTA_REJEITADA";
    public static final String PROPOSTA_EXPIRADA = "PO_ROBO_PROPOSTA_EXPIRADA";

    /** Acções que aparecem na linha do tempo da empresa. */
    public static final java.util.List<String> ACCOES_DE_ACTIVIDADE = java.util.List.of(REGRA_CRIADA, REGRA_ATUALIZADA,
            REGRA_REMOVIDA, EXECUCAO, PROPOSTA_CRIADA, PROPOSTA_AJUSTADA, PROPOSTA_SUBMETIDA, PROPOSTA_REJEITADA, PROPOSTA_EXPIRADA);

    private PoRoboAuditoria() {
    }
}
