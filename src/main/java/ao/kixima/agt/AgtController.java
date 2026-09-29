package ao.kixima.agt;

import ao.kixima.common.error.ServiceUnavailableException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.security.CurrentUser;
import ao.kixima.security.CurrentUserHolder;
import ao.kixima.security.PersonaRole;
import ao.kixima.security.RequirePermission;
import ao.kixima.security.RequireRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static ao.kixima.security.AdminArea.FATURACAO;
import static ao.kixima.security.PersonaRole.ADMIN_SISTEMA;
import static ao.kixima.security.PersonaRole.COMPANY_ADMIN;
import static ao.kixima.security.PersonaRole.FORNECEDOR;

/**
 * Espelha o troço de AGT de backend/src/routes/faturacaoRoutes.js —
 * /agt-payload, /agt-estado, /agt-serie-payload, /agt-series-fe. NÃO
 * PORTADO NESTE MARCO (M4): /integridade, /saft, /saft/resumo, /metricas —
 * dependem de faturacaoService.verificarCadeia/saftService/metricasService,
 * domínios de relatório/exportação à parte da integração AGT em si (M5).
 */
@RestController
@RequestMapping("/api/faturacao")
public class AgtController {

    private final AgtPayloadService agtPayloadService;
    private final AgtSeriesService agtSeriesService;
    private final AgtSigningService agtSigningService;
    private final AgtSandboxClient agtSandboxClient;
    private final String nif;
    private final String establishmentNumber;

    public AgtController(AgtPayloadService agtPayloadService, AgtSeriesService agtSeriesService,
                          AgtSigningService agtSigningService, AgtSandboxClient agtSandboxClient,
                          @Value("${kixima.agt.nif:}") String nif,
                          @Value("${kixima.agt.establishment-number:}") String establishmentNumber) {
        this.agtPayloadService = agtPayloadService;
        this.agtSeriesService = agtSeriesService;
        this.agtSigningService = agtSigningService;
        this.agtSandboxClient = agtSandboxClient;
        this.nif = nif;
        this.establishmentNumber = establishmentNumber;
    }

    /** O Fornecedor só pede o SEU documento; o Admin do Sistema tem de indicar de qual empresa. */
    private String resolverEmpresaFornecedora(String supplierCompanyIdQuery) {
        CurrentUser user = CurrentUserHolder.get();
        if (user.role() == ADMIN_SISTEMA) {
            if (supplierCompanyIdQuery == null || supplierCompanyIdQuery.isBlank()) {
                throw new ValidationException("Indique a empresa fornecedora (supplierCompanyId).");
            }
            return supplierCompanyIdQuery;
        }
        return user.companyId();
    }

    private void exigirAssinaturaAgtConfigurada() {
        if (!agtSigningService.disponivel()) {
            throw new ServiceUnavailableException(
                    "A assinatura AGT ainda não está configurada neste ambiente. Em falta: "
                            + String.join(", ", agtSigningService.emFalta())
                            + ". Sem isso, nenhum documento pode ser assinado — contacte quem administra o ambiente.");
        }
    }

    private void exigirSandboxAgtConfigurada() {
        if (!agtSandboxClient.disponivel()) {
            throw new ServiceUnavailableException(
                    "A ligação à Sandbox da AGT ainda não está configurada neste ambiente. Em falta: "
                            + String.join(", ", agtSandboxClient.emFalta())
                            + ". Sem isso, nenhum pedido é submetido à AGT — contacte quem administra o ambiente.");
        }
    }

    /** Payload de submissão AGT (FT/NC/RC) — devolve o JSON já assinado, não submete a nada. */
    @GetMapping("/agt-payload/{tipo}/{id}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public Map<String, Object> agtPayload(@PathVariable String tipo, @PathVariable String id,
                                           @RequestParam(required = false) String supplierCompanyId) {
        exigirAssinaturaAgtConfigurada();
        String empresa = resolverEmpresaFornecedora(supplierCompanyId);
        return agtPayloadService.construirPayload(tipo.toUpperCase(), id, empresa);
    }

    /** Estado real do processamento da última submissão do FT desta fatura (obterEstado). */
    @GetMapping("/agt-estado/{invoiceId}")
    @RequireRole({FORNECEDOR, COMPANY_ADMIN, ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public AgtPayloadService.EstadoConsultado agtEstado(@PathVariable String invoiceId,
                                                          @RequestParam(required = false) String supplierCompanyId) {
        exigirSandboxAgtConfigurada();
        String empresa = resolverEmpresaFornecedora(supplierCompanyId);
        return agtPayloadService.consultarEstadoFatura(invoiceId, empresa);
    }

    /** Pedido de série de numeração à AGT ("Solicitar Série") — só Admin do Sistema, passo de configuração/pré-requisito. */
    @GetMapping("/agt-serie-payload")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public AgtSeriesService.ResultadoSolicitacao agtSeriePayload(@RequestParam Integer ano, @RequestParam String tipoDocumento) {
        exigirSandboxAgtConfigurada();
        if (nif == null || nif.isBlank()) {
            throw new ServiceUnavailableException(
                    "O NIF da conta AGT (AGT_NIF) ainda não está configurado neste ambiente — sem ele não se pode gerar "
                            + "um pedido de série. Contacte quem administra o ambiente.");
        }
        if (establishmentNumber == null || establishmentNumber.isBlank()) {
            throw new ServiceUnavailableException(
                    "O código do estabelecimento na AGT (AGT_ESTABLISHMENT_NUMBER) ainda não está configurado neste "
                            + "ambiente — sem ele não se pode gerar um pedido de série. Confirme o código correto junto da "
                            + "AGT para o NIF configurado (AGT_NIF) antes de o definir; nunca um valor adivinhado por tentativa.");
        }
        if (ano == null) throw new ValidationException("Indique o ano da série (ano).");
        if (tipoDocumento == null || tipoDocumento.isBlank()) throw new ValidationException("Indique o tipo de documento (tipoDocumento).");

        CurrentUser user = CurrentUserHolder.get();
        return agtSeriesService.solicitarSerie(
                new AgtSeriesService.ParametrosSerie(nif, ano, tipoDocumento.trim().toUpperCase(), establishmentNumber),
                new AgtSeriesService.Contexto(user.id(), user.name()));
    }

    /** Histórico dos pedidos "Solicitar Série" já aceites pela AGT. */
    @GetMapping("/agt-series-fe")
    @RequireRole({ADMIN_SISTEMA})
    @RequirePermission(FATURACAO)
    public java.util.List<AgtSeriesFe> agtSeriesFe() {
        return agtSeriesService.listarHistorico();
    }
}
