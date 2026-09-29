package ao.kixima.user;

import ao.kixima.security.PersonaRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);

    /** Espelha `prisma.user.findMany({ where: { companyId, role: { in: roles }, active: true } })` (notificationService.notifyUsersByRole). */
    List<User> findByCompanyIdAndRoleInAndActiveTrue(String companyId, List<PersonaRole> roles);

    /** Usado por UploadAccessService — o avatar de um utilizador é sempre público. */
    boolean existsByAvatarUrl(String avatarUrl);

    /** Lista de assessores para o seletor "Transferir para..." (SupportController.agentes). */
    List<User> findByRoleAndActiveTrue(PersonaRole role);

    /** Lugares já ocupados no plano da empresa — ver InviteService.assertLugaresDisponiveis. */
    long countByCompanyIdAndActiveTrue(String companyId);

    Optional<User> findByIdAndCompanyId(String id, String companyId);

    /** Espelha `orderBy: [{ active: 'asc' }, { createdAt: 'desc' }]` de companyService.listCompanyUsers. */
    @Query("SELECT u FROM User u WHERE u.companyId = :companyId ORDER BY u.active ASC, u.createdAt DESC")
    List<User> findByCompanyIdOrderByActiveAscCreatedAtDesc(@Param("companyId") String companyId);

    /**
     * `company` é LAZY (plano, secção 2) — o AuthenticationFilter e o login
     * precisam de `company.type`/`company.plan` na mesma resposta que o
     * Node devolve (`include: { company: ... }`), por isso trazem-na já
     * carregada aqui em vez de arriscar LazyInitializationException fora
     * de uma sessão aberta.
     */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.company WHERE u.id = :id")
    Optional<User> findByIdWithCompany(@Param("id") String id);

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.company WHERE u.email = :email")
    Optional<User> findByEmailWithCompany(@Param("email") String email);

    /** Espelha o `updateMany` de retencaoService.limpar — códigos de 2FA por email já expirados há muito. */
    @Modifying
    @Query("UPDATE User u SET u.mfaCodeHash = NULL, u.mfaCodeExpiraEm = NULL, u.mfaCodeTentativas = 0 "
            + "WHERE u.mfaCodeExpiraEm IS NOT NULL AND u.mfaCodeExpiraEm < :ate")
    int limparCodigos2faExpirados(@Param("ate") Instant ate);

    /** O Company Admin mais antigo da empresa — em nome de quem o PO Robot cria a PO (poRoboService.executarRegra). */
    Optional<User> findFirstByCompanyIdAndRoleAndActiveTrueOrderByCreatedAtAsc(String companyId, PersonaRole role);

    /** Espelha mfaLembreteService.pendentes — contas com poder que ainda não têm 2FA. */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.company WHERE u.role IN :roles AND u.active = true "
            + "AND u.totpEnabledAt IS NULL ORDER BY u.name ASC")
    List<User> findMfaPendentes(@Param("roles") List<PersonaRole> roles);

    /** Espelha o `groupBy({ by: ['companyId'], where: { active: true }, _count })` de companyService.subscriptionsFor — UMA consulta para N empresas. */
    @Query("SELECT u.companyId, COUNT(u) FROM User u WHERE u.companyId IN :ids AND u.active = true GROUP BY u.companyId")
    List<Object[]> contagemAtivosPorEmpresa(@Param("ids") List<String> ids);

    /** adminService.listUsers — bloqueados primeiro, depois do mais recente. */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.company ORDER BY u.active ASC, u.createdAt DESC")
    List<User> findTodosOrderByActiveAscCreatedAtDesc();

    List<User> findTop10ByOrderByCreatedAtDesc();

    long countByRoleInAndActiveTrueAndTotpEnabledAtIsNull(List<PersonaRole> roles);

    /** loginAttemptService.bloqueadasAgora — contas com bloqueio progressivo ainda a decorrer. */
    List<User> findByBloqueadoAteAfterOrderByEmailAsc(Instant agora);

    long countByCompanyId(String companyId);

    List<User> findByCompanyId(String companyId);
}
