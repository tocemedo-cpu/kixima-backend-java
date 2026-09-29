package ao.kixima.supplierdev;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface SupplierDevRequestRepository
        extends JpaRepository<SupplierDevRequest, String>, JpaSpecificationExecutor<SupplierDevRequest> {

    Optional<SupplierDevRequest> findByReference(String reference);

    /** Espelha o `groupBy({ by: ['status'], _count: { _all: true } })` do Node. */
    @Query("SELECT s.status, COUNT(s) FROM SupplierDevRequest s GROUP BY s.status")
    List<Object[]> contagemPorEstado();

    long countByFeeStatus(PlatformFeeStatus feeStatus);

    @Query("SELECT COALESCE(SUM(s.accessFeeUsd), 0) FROM SupplierDevRequest s WHERE s.feeStatus = :feeStatus")
    BigDecimal somaTaxaAcessoPorEstado(PlatformFeeStatus feeStatus);
}
