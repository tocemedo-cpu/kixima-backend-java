package ao.kixima.plan;

import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Espelha só a parte de backend/src/services/assinaturaService.js que o
 * job precisa — {@code enviarAvisosDeExpiracao} e os patamares que a
 * governam. NÃO PORTADO (fica só no Node por agora): o resto do ficheiro —
 * pedir/confirmar/cancelar subscrição, submissão de comprovativo, os canais
 * de pagamento automáticos (gateway) — é o fluxo completo de cobrança, um
 * domínio à parte (pagamentos/faturação da própria KIXIMA, distinto do
 * pagamento das PO entre empresas), sem qualquer chamador Java ainda.
 */
@Service
public class SubscriptionExpiryService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionExpiryService.class);
    private static final long DIA_MS = 24L * 60 * 60 * 1000;

    /** Espelha PATAMARES_AVISO — ordenados do menos para o mais urgente; o último cujo limiar se aplica é o patamar actual. */
    private record Patamar(String tier, int limiarDias) {
    }

    private final CompanyRepository companyRepository;
    private final NotificationService notificationService;
    private final PlanService planService;

    public SubscriptionExpiryService(CompanyRepository companyRepository, NotificationService notificationService,
                                      PlanService planService) {
        this.companyRepository = companyRepository;
        this.notificationService = notificationService;
        this.planService = planService;
    }

    private List<Patamar> patamares() {
        return List.of(
                new Patamar("D30", 30),
                new Patamar("D7", 7),
                new Patamar("D3", 3),
                new Patamar("D1", 1),
                new Patamar("D0", 0),
                new Patamar("GRACE_INICIO", -1),
                // A meio do período de tolerância — arredondado para cima para nunca cair
                // depois do fim do grace period (ex.: 7 dias de tolerância → aviso ao 4º).
                new Patamar("GRACE_META", -(int) Math.ceil(planService.gracePeriodDays() / 2.0)));
    }

    private int diasAte(Instant data, Instant agora) {
        return (int) Math.ceil((data.toEpochMilli() - agora.toEpochMilli()) / (double) DIA_MS);
    }

    /** Espelha assinaturaService.patamarAtual — o último patamar cujo limiar já foi atingido. */
    String patamarAtual(int diasAteVencer) {
        String atual = null;
        for (Patamar p : patamares()) {
            if (diasAteVencer <= p.limiarDias()) atual = p.tier();
        }
        return atual;
    }

    /**
     * Envia os avisos de expiração do dia — um por empresa, no máximo, e só
     * quando o patamar SOBE (nunca repete o mesmo, nunca volta atrás). Corre
     * uma vez por dia via {@link SubscriptionExpiryJob}.
     *
     * Empresas já RESTRITAS não recebem mais avisos: passado o período de
     * tolerância o acesso já está bloqueado nos pontos certos (ver
     * {@link PlanService#assertFeature}) — mais um aviso periódico seria
     * ruído sem ação nova possível.
     */
    @Transactional
    public int enviarAvisosDeExpiracao() {
        Instant agora = Instant.now();
        List<Company> candidatas = companyRepository.findByPlanoValidoAteIsNotNull();

        Map<String, Integer> rank = new LinkedHashMap<>();
        List<Patamar> patamares = patamares();
        for (int i = 0; i < patamares.size(); i++) rank.put(patamares.get(i).tier(), i);

        int enviados = 0;
        for (Company company : candidatas) {
            if (planService.estadoSubscricao(company, agora) == SubscriptionState.RESTRITA) continue;

            String tier = patamarAtual(diasAte(company.getPlanoValidoAte(), agora));
            if (tier == null) continue;

            int rankAnterior = rank.getOrDefault(company.getUltimoAvisoSubscricaoTier(), -1);
            if (rank.get(tier) <= rankAnterior) continue;

            notificationService.subscricaoAExpirar(company, tier);
            company.setUltimoAvisoSubscricaoTier(tier);
            enviados++;
        }

        if (enviados > 0) {
            log.info("Avisos de expiração de subscrição enviados: {}", enviados);
        }
        return enviados;
    }
}
