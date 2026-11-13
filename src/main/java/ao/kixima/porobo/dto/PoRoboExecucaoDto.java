package ao.kixima.porobo.dto;

import ao.kixima.porobo.EstadoExecucao;
import ao.kixima.porobo.PoRoboExecucao;

import java.time.Instant;
import java.util.List;

/** Contrato {@code Execucao} do frontend ({@code origem} é sempre AGENDADA: o frontend não lança execuções). */
public record PoRoboExecucaoDto(String id, Instant iniciadaEm, Instant terminadaEm, EstadoExecucao estado, String origem,
                                int regrasAvaliadas, int propostasCriadas, List<String> erros) {

    public static PoRoboExecucaoDto de(PoRoboExecucao e, List<String> erros) {
        return new PoRoboExecucaoDto(e.getId(), e.getIniciadaEm(), e.getTerminadaEm(), e.getEstado(), e.getOrigem(),
                e.getRegrasAvaliadas(), e.getPropostasCriadas(), erros);
    }
}
