package ao.kixima.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BudgetLimitRepository extends JpaRepository<BudgetLimit, String> {

    Optional<BudgetLimit> findByCompanyId(String companyId);
}
