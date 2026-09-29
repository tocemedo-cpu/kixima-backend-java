package ao.kixima.addon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyAddonRepository extends JpaRepository<CompanyAddon, String> {

    /** Espelha `findUnique({ where: { companyId_addonKey } })` — índice único (companyId, addonKey). */
    Optional<CompanyAddon> findByCompanyIdAndAddonKey(String companyId, String addonKey);
}
