package ao.kixima.porobo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tradução das acções de auditoria para os tipos e descrições da linha do tempo (contrato {@code Actividade}). */
class PoRoboHistoricoMapeamentoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode d(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void cadaAccaoDeAuditoriaTemOSeuTipoDoContrato() throws Exception {
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.REGRA_CRIADA, d("{}"))).isEqualTo("REGRA_CRIADA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.REGRA_REMOVIDA, d("{}"))).isEqualTo("REGRA_REMOVIDA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.EXECUCAO, d("{}"))).isEqualTo("EXECUCAO");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.PROPOSTA_CRIADA, d("{}"))).isEqualTo("PROPOSTA_CRIADA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.PROPOSTA_AJUSTADA, d("{}"))).isEqualTo("PROPOSTA_AJUSTADA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.PROPOSTA_SUBMETIDA, d("{}"))).isEqualTo("PROPOSTA_SUBMETIDA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.PROPOSTA_REJEITADA, d("{}"))).isEqualTo("PROPOSTA_REJEITADA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.PROPOSTA_EXPIRADA, d("{}"))).isEqualTo("PROPOSTA_EXPIRADA");
    }

    @Test
    void alterarUmaRegraDistingueActivarDesactivarDeAlterar() throws Exception {
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.REGRA_ATUALIZADA, d("{\"ativo\":true,\"produto\":\"X\"}"))).isEqualTo("REGRA_ACTIVADA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.REGRA_ATUALIZADA, d("{\"ativo\":false,\"produto\":\"X\"}"))).isEqualTo("REGRA_DESACTIVADA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.REGRA_ATUALIZADA, d("{\"mediaMensal\":30,\"produto\":\"X\"}"))).isEqualTo("REGRA_ALTERADA");
        assertThat(PoRoboHistoricoService.tipoDe(PoRoboAuditoria.REGRA_ATUALIZADA, d("{\"ativo\":false,\"mediaMensal\":30}")))
                .as("ativo junto com os campos da regra = alteração").isEqualTo("REGRA_ALTERADA");
    }

    @Test
    void asDescricoesLeemSeBemMesmoComRegistosAntigosSemDetalhe() throws Exception {
        assertThat(PoRoboHistoricoService.descricao("PROPOSTA_CRIADA", d("{\"produto\":\"Válvula\",\"quantidade\":2,\"quantidadeOriginal\":5,\"limiteAplicado\":true}")))
                .contains("2 un").contains("«Válvula»").contains("limite de segurança").contains("5");
        assertThat(PoRoboHistoricoService.descricao("PROPOSTA_SUBMETIDA", d("{\"produto\":\"Válvula\",\"purchaseOrderReference\":\"PO-0001\"}")))
                .contains("PO-0001").contains("aprovação");
        assertThat(PoRoboHistoricoService.descricao("PROPOSTA_REJEITADA", d("{\"produto\":\"V\",\"motivo\":\"Sem orçamento\"}"))).contains("Sem orçamento");
        assertThat(PoRoboHistoricoService.descricao("EXECUCAO", d("{\"regrasAvaliadas\":3,\"propostasCriadas\":1,\"erros\":2}")))
                .contains("3 regra").contains("1 proposta").contains("2 erro");
        // Registos antigos (sem detalhe) não rebentam.
        assertThat(PoRoboHistoricoService.descricao("REGRA_REMOVIDA", d("{}"))).contains("removida");
        assertThat(PoRoboHistoricoService.descricao("REGRA_ALTERADA", JSON.createObjectNode())).contains("alterada");
    }

    @Test
    void todasAsAccoesDaActividadeEstaoNaLista() {
        assertThat(PoRoboAuditoria.ACCOES_DE_ACTIVIDADE).hasSize(9).doesNotHaveDuplicates()
                .allMatch(a -> a.startsWith("PO_ROBO_"));
    }
}
