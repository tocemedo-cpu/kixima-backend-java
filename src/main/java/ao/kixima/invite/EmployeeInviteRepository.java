package ao.kixima.invite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmployeeInviteRepository extends JpaRepository<EmployeeInvite, String> {

    List<EmployeeInvite> findByCompanyIdOrderByCreatedAtDesc(String companyId);

    /** Convites de assessor (ADMIN_SISTEMA) — mesma tabela que os convites de funcionário, sem empresa. */
    List<EmployeeInvite> findByRoleOrderByCreatedAtDesc(ao.kixima.security.PersonaRole role);

    Optional<EmployeeInvite> findByToken(String token);

    Optional<EmployeeInvite> findByIdAndCompanyId(String id, String companyId);

    long countByCompanyIdAndStatusAndExpiresAtAfter(String companyId, InviteStatus status, Instant instant);

    /**
     * Espelha o `deleteMany` de retencaoService.limpar — convites mortos (EXPIRADO/CANCELADO) há muito.
     * Os estados vêm como parâmetro (nunca como literal JPQL) — um literal de enum aqui faz o Hibernate
     * gerar um cast `::InviteStatus` sem aspas, que o Postgres baixa para `invitestatus` e falha, já
     * que o tipo real na base é case-sensitive (`"InviteStatus"`, criado assim pelo Prisma).
     */
    @Modifying
    @Query("DELETE FROM EmployeeInvite i WHERE i.status IN :estados AND i.updatedAt < :ate")
    int deleteMortosAntesDe(@Param("estados") List<InviteStatus> estados, @Param("ate") Instant ate);
}
