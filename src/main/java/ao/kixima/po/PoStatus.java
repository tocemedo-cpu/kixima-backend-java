package ao.kixima.po;

/** Espelha o enum `PoStatus` (schema.prisma:876-889) — máquina de estados da PO. */
public enum PoStatus {
    AGUARDANDO_APROVACAO,
    APROVADA,
    REJEITADA,
    ACEITE_FORNECEDOR,
    RECUSADA_FORNECEDOR,
    AGUARDANDO_PAGAMENTO,
    PAGA,
    EM_EXECUCAO,
    ENTREGUE,
    RECEBIDA_CONFORME,
    RECEBIDA_COM_DIVERGENCIA,
    CONCLUIDA,
}
