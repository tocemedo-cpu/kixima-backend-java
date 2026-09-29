package ao.kixima.erp;

/** Espelha o enum Postgres "ErpSyncDirection". */
public enum ErpSyncDirection {
    OUTBOUND, // KIXIMA → ERP (pedido de aprovação enviado)
    INBOUND   // ERP → KIXIMA (decisão/pagamento recebido via callback)
}
