package ao.kixima.porobo;

/**
 * Estado de uma execução do robot para uma empresa: {@code CONCLUIDA} (sem erros), {@code COM_ERROS} (alguma regra
 * não gerou proposta mas outras sim, ou nenhuma era para gerar) e {@code FALHOU} (houve erros e nenhuma proposta nasceu).
 */
public enum EstadoExecucao {
    EM_CURSO,
    CONCLUIDA,
    COM_ERROS,
    FALHOU
}
