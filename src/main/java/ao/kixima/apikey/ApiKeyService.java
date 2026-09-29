package ao.kixima.apikey;

import ao.kixima.apikey.dto.ApiKeyCreatedDto;
import ao.kixima.apikey.dto.ApiKeyDto;
import ao.kixima.apikey.dto.RevokedApiKeyDto;
import ao.kixima.common.error.BusinessRuleException;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.company.Company;
import ao.kixima.company.CompanyRepository;
import ao.kixima.company.CompanyStatus;
import ao.kixima.plan.PlanFeatureFlag;
import ao.kixima.plan.PlanService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Espelha backend/src/services/apiKeyService.js — chaves de API do
 * catálogo (plano Pro). O alcance de uma chave é estritamente o catálogo
 * da própria empresa; só o hash (bcrypt) fica guardado; a chave inteira
 * só é mostrada UMA vez, no momento da criação.
 *
 * NÃO PORTADO: a API pública que consome estas chaves
 * (backend/src/routes/apiCatalogoRoutes.js, {@code GET/PATCH /api/v1/catalogo/*}) —
 * é um mecanismo de autenticação próprio (por chave, não por JWT/sessão,
 * ver {@code autenticarChave} no Node) e precisa de limitação de pedidos
 * por chave (Bucket4j, ainda não é dependência do projeto Java). {@link #autenticar}
 * já está pronto e testado para quando essa API for portada — só falta o
 * filtro/controller que a chama.
 */
@Service
public class ApiKeyService {

    private static final String PREFIXO = "kxm";
    /** Não é um limite comercial — é para a lista continuar legível. */
    private static final int MAXIMO_ATIVAS = 5;

    private final ApiKeyRepository apiKeyRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlanService planService;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository, CompanyRepository companyRepository,
                          PasswordEncoder passwordEncoder, PlanService planService) {
        this.apiKeyRepository = apiKeyRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.planService = planService;
    }

    @Transactional(readOnly = true)
    public List<ApiKeyDto> listar(String companyId) {
        return apiKeyRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream().map(this::toDto).toList();
    }

    @Transactional
    public ApiKeyCreatedDto criar(Company company, String nomeBruto, String criadaPor) {
        planService.assertFeature(company, PlanFeatureFlag.API_CATALOGO, "API de catálogo");
        String nome = nomeBruto == null ? "" : nomeBruto.trim();
        if (nome.isEmpty()) {
            throw new BusinessRuleException(
                    "Dê um nome à chave (ex.: \"ERP da produção\") — é por ele que a vai reconhecer para revogar.");
        }
        long ativas = apiKeyRepository.countByCompanyIdAndRevogadaEmIsNull(company.getId());
        if (ativas >= MAXIMO_ATIVAS) {
            throw new BusinessRuleException(
                    "Já tem " + MAXIMO_ATIVAS + " chaves ativas. Revogue uma antes de criar outra — "
                            + "uma lista longa de chaves é uma lista que ninguém revê.");
        }

        String publico = HexFormat.of().formatHex(bytes(4));
        String segredo = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32));
        String prefixo = PREFIXO + "_" + publico;
        String chave = prefixo + "." + segredo;

        Instant agora = Instant.now();
        ApiKey criada = new ApiKey(UUID.randomUUID().toString(), company.getId(), nome, prefixo,
                passwordEncoder.encode(chave), criadaPor, agora);
        apiKeyRepository.save(criada);

        return new ApiKeyCreatedDto(criada.getId(), criada.getNome(), criada.getPrefixo(), criada.getCreatedAt(),
                chave, "Guarde esta chave agora. Não voltará a ser mostrada — se a perder, revogue-a e crie outra.");
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    @Transactional
    public RevokedApiKeyDto revogar(String companyId, String id) {
        ApiKey chave = apiKeyRepository.findByIdAndCompanyId(id, companyId).orElseThrow(() -> new NotFoundException("Chave de API"));
        if (chave.getRevogadaEm() != null) return new RevokedApiKeyDto(id, chave.getRevogadaEm());
        // Revoga, não apaga: quem quiser saber o que aquela chave andou a fazer tem de continuar a poder identificá-la.
        chave.setRevogadaEm(Instant.now());
        return new RevokedApiKeyDto(id, chave.getRevogadaEm());
    }

    public record Sessao(Company empresa, String chaveId, String prefixo) {
    }

    private record Partes(String prefixo, String segredo) {
    }

    private Partes partir(String apresentada) {
        if (apresentada == null) return null;
        int ponto = apresentada.indexOf('.');
        if (ponto < 0) return null;
        String prefixo = apresentada.substring(0, ponto);
        String segredo = apresentada.substring(ponto + 1);
        if (prefixo.isEmpty() || segredo.isEmpty() || !prefixo.startsWith(PREFIXO + "_")) return null;
        return new Partes(prefixo, segredo);
    }

    /**
     * Autentica uma chave apresentada. A comparação só corre contra a
     * chave cujo PREFIXO bate certo — comparar contra todas seria um
     * bcrypt por chave existente a cada pedido.
     */
    @Transactional
    public Sessao autenticar(String apresentada) {
        Partes partes = partir(apresentada);
        if (partes == null) return null;

        ApiKey registo = apiKeyRepository.findByPrefixo(partes.prefixo()).orElse(null);
        if (registo == null || registo.getRevogadaEm() != null) return null;
        if (!passwordEncoder.matches(apresentada, registo.getHash())) return null;

        Company empresa = companyRepository.findById(registo.getCompanyId()).orElse(null);
        // A chave é do plano Pro — se a empresa descer de plano, a chave deixa de valer.
        if (empresa == null || !planService.hasFeature(empresa.getPlan(), PlanFeatureFlag.API_CATALOGO)) return null;
        if (empresa.getStatus() != CompanyStatus.APROVADA) return null;
        // (o Node também verifica `company.active === false` — campo que não existe no
        // schema/entidade Company; a verificação nunca dispara do lado de lá também.)

        // Carimbo de uso, para se ver uma chave que ninguém usa — best-effort.
        registo.setUltimoUso(Instant.now());

        return new Sessao(empresa, registo.getId(), registo.getPrefixo());
    }

    private ApiKeyDto toDto(ApiKey k) {
        return new ApiKeyDto(k.getId(), k.getNome(), k.getPrefixo(), k.getUltimoUso(), k.getRevogadaEm(),
                k.getCreatedAt(), k.getRevogadaEm() == null);
    }
}
