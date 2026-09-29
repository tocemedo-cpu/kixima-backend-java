package ao.kixima.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlatformFeeRepository extends JpaRepository<PlatformFee, String> {

    Optional<PlatformFee> findByInvoiceId(String invoiceId);

    List<PlatformFee> findByCompanyIdOrderByCreatedAtDesc(String companyId);

    List<PlatformFee> findAllByOrderByCreatedAtDesc();
}
