package ao.kixima.quote;

/** Espelha o enum Postgres "QuoteStatus". */
public enum QuoteStatus {
    ABERTA,      // pedido enviado, a aguardar resposta do fornecedor
    RESPONDIDA,  // fornecedor respondeu com preço/prazo
    FECHADA      // comprador encerrou o pedido
}
