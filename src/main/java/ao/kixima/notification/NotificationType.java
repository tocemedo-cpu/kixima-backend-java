package ao.kixima.notification;

/**
 * Espelha o enum Prisma `NotificationType` (schema.prisma) — TODOS os
 * valores, mesmo os que nenhum caminho Java emite: a base é PARTILHADA
 * com o Node, que continua a escrever linhas com qualquer um destes tipos;
 * um enum incompleto aqui rebentaria a leitura dessas linhas
 * (EnumType.STRING falha em qualquer valor fora do conjunto Java).
 */
public enum NotificationType {
    PO_AGUARDA_APROVACAO,
    PO_APROVADA,
    PO_REJEITADA,
    PO_RECEBIDA_FORNECEDOR,
    FATURA_GERADA,
    PAGAMENTO_PROCESSADO,
    ENTREGA_DESPACHADA,
    RECECAO_COM_DIVERGENCIA,
    DIVERGENCIA_RESOLVIDA,
    SUPPLIER_DEV_RECEBIDA,
    APOLICE_SUBMETIDA_APROVADA,
    APOLICE_A_EXPIRAR,
    CADASTRO_EMPRESA_APROVADO,
    CADASTRO_EMPRESA_REJEITADO,
    SUBSCRICAO_COMPROVATIVO,
    SUBSCRICAO_CONFIRMADA,
    SUBSCRICAO_A_EXPIRAR,
    SUPORTE_MENSAGEM,
    CHAT_COMERCIAL_MENSAGEM,
    ALERTA_SEGURANCA,
    PO_RECUSADA_FORNECEDOR,
    PO_ENTREGUE,
    PO_RECEBIDA_CONFORME,
    PO_CONCLUIDA,
    NOTA_CREDITO_EMITIDA,
    ESTOQUE_BAIXO
}
