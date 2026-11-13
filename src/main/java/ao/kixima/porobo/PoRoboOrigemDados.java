package ao.kixima.porobo;

/** De onde vem o consumo usado numa previsão. {@code ERP} fica reservado: hoje só há histórico de compras e valores manuais. */
public enum PoRoboOrigemDados {
    HISTORICO_COMPRAS,
    ERP,
    MANUAL
}
