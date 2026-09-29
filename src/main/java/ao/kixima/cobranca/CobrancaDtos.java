package ao.kixima.cobranca;

import ao.kixima.company.Company;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/** As linhas `PlanoCobranca`/`AddonCobranca` tal como o Node as devolve (com `company` só na fila da KIXIMA). */
public final class CobrancaDtos {

    private CobrancaDtos() {
    }

    public record CompanyRef(String id, String name, String plan) {
        static CompanyRef de(Company c) {
            return c == null ? null : new CompanyRef(c.getId(), c.getName(), c.getPlan() == null ? null : c.getPlan().name());
        }
    }

    public record PlanoCobrancaDto(String id, String referencia, String companyId, String planoAtual, String planoNovo, BigDecimal valorUsd,
                                   String periodo, int meses, String status, String comprovativoUrl, Instant submetidoEm, String confirmadaPor,
                                   Instant confirmadaEm, Instant validoAte, String notas, String createdById, Instant createdAt, Instant updatedAt,
                                   String canal, String referenciaExterna, String telemovel,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) CompanyRef company) {
        public static PlanoCobrancaDto de(PlanoCobranca c, boolean comEmpresa) {
            return de(c, comEmpresa ? c.getCompany() : null);
        }

        /** Com a empresa resolvida à parte — numa sessão que acabou de criar a linha, a relação lazy ainda não está carregada. */
        public static PlanoCobrancaDto de(PlanoCobranca c, Company company) {
            boolean comEmpresa = company != null;
            return new PlanoCobrancaDto(c.getId(), c.getReferencia(), c.getCompanyId(), c.getPlanoAtual().name(), c.getPlanoNovo().name(),
                    c.getValorUsd(), c.getPeriodo(), c.getMeses(), c.getStatus().name(), c.getComprovativoUrl(), c.getSubmetidoEm(),
                    c.getConfirmadaPor(), c.getConfirmadaEm(), c.getValidoAte(), c.getNotas(), c.getCreatedById(), c.getCreatedAt(),
                    c.getUpdatedAt(), c.getCanal().name(), c.getReferenciaExterna(), c.getTelemovel(),
                    comEmpresa ? CompanyRef.de(company) : null);
        }
    }

    public record AddonCobrancaDto(String id, String referencia, String companyId, String addonKey, BigDecimal valorUsd, String periodo,
                                   int meses, String status, String comprovativoUrl, Instant submetidoEm, String confirmadaPor,
                                   Instant confirmadaEm, Instant validoAte, String notas, String canal, String referenciaExterna,
                                   String telemovel, String createdById, Instant createdAt, Instant updatedAt,
                                   @JsonInclude(JsonInclude.Include.NON_NULL) CompanyRef company) {
        public static AddonCobrancaDto de(AddonCobranca c, boolean comEmpresa) {
            return de(c, comEmpresa ? c.getCompany() : null);
        }

        public static AddonCobrancaDto de(AddonCobranca c, Company company) {
            boolean comEmpresa = company != null;
            return new AddonCobrancaDto(c.getId(), c.getReferencia(), c.getCompanyId(), c.getAddonKey(), c.getValorUsd(), c.getPeriodo(),
                    c.getMeses(), c.getStatus().name(), c.getComprovativoUrl(), c.getSubmetidoEm(), c.getConfirmadaPor(), c.getConfirmadaEm(),
                    c.getValidoAte(), c.getNotas(), c.getCanal().name(), c.getReferenciaExterna(), c.getTelemovel(), c.getCreatedById(),
                    c.getCreatedAt(), c.getUpdatedAt(), comEmpresa ? CompanyRef.de(company) : null);
        }
    }
}
