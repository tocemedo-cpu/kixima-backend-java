package ao.kixima.porobo;

import ao.kixima.common.persistence.AbstractPersistableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Uma corrida agendada do robot para UMA empresa (tabela {@code po_robo_execucoes}, V4): quantas regras avaliou,
 * quantas propostas criou e que erros houve (JSON em texto, uma frase por erro).
 */
@Entity
@Table(name = "po_robo_execucoes")
public class PoRoboExecucao extends AbstractPersistableEntity<String> {

    @Id
    private String id;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "iniciada_em", nullable = false)
    private Instant iniciadaEm;

    @Column(name = "terminada_em")
    private Instant terminadaEm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoExecucao estado = EstadoExecucao.EM_CURSO;

    @Column(nullable = false)
    private String origem = "AGENDADA";

    @Column(name = "regras_avaliadas", nullable = false)
    private int regrasAvaliadas;

    @Column(name = "propostas_criadas", nullable = false)
    private int propostasCriadas;

    @Column(nullable = false)
    private String erros = "[]";

    protected PoRoboExecucao() {
        // JPA
    }

    public PoRoboExecucao(String id, String companyId, Instant iniciadaEm) {
        this.id = id;
        this.companyId = companyId;
        this.iniciadaEm = iniciadaEm;
    }

    public void terminar(Instant em, EstadoExecucao estadoFinal, int regrasAvaliadas, int propostasCriadas, String errosJson) {
        this.terminadaEm = em;
        this.estado = estadoFinal;
        this.regrasAvaliadas = regrasAvaliadas;
        this.propostasCriadas = propostasCriadas;
        this.erros = errosJson;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public Instant getIniciadaEm() {
        return iniciadaEm;
    }

    public Instant getTerminadaEm() {
        return terminadaEm;
    }

    public EstadoExecucao getEstado() {
        return estado;
    }

    public String getOrigem() {
        return origem;
    }

    public int getRegrasAvaliadas() {
        return regrasAvaliadas;
    }

    public int getPropostasCriadas() {
        return propostasCriadas;
    }

    public String getErros() {
        return erros;
    }
}
