package ao.kixima.policy;

import ao.kixima.company.PolicyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface KiximaToClientPolicyRepository extends JpaRepository<KiximaToClientPolicy, String> {

    List<KiximaToClientPolicy> findByCompanyId(String companyId);

    /** Espelha o `findMany` de policyService.sendExpiryAlerts — apólices activas a expirar dentro do prazo, ainda não avisadas. */
    List<KiximaToClientPolicy> findByStatusAndValidUntilBetweenAndExpiryAlertSentAtIsNull(
            PolicyStatus status, Instant desde, Instant ate);
}
