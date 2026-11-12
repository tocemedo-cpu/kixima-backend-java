package ao.kixima.banner;

import ao.kixima.banner.dto.BannerDto;
import ao.kixima.banner.dto.PublicBannerDto;
import ao.kixima.common.error.NotFoundException;
import ao.kixima.common.error.ValidationException;
import ao.kixima.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Banners da homepage pública. O contrato é o de {@code docs/api-banners.md} do frontend (e do seu servidor mock):
 * limites de texto, ligações internas ou https, {@code ativo} só com imagem, ordem 1..n sem buracos, imagem JPG/PNG/WEBP
 * até 5 MB. Os erros de validação são 422 {@code VALIDATION_ERROR} com {@code details = {campo: mensagem}} — o formato
 * que o ecrã de gestão mostra junto de cada campo.
 */
@Service
public class BannerService {

    static final int MAX_ETIQUETA = 40;
    static final int MAX_TITULO = 80;
    static final int MAX_TEXTO = 180;
    static final int MAX_CTA = 30;
    static final long MAX_IMAGEM_BYTES = 5L * 1024 * 1024;
    static final Set<String> FORMATOS_IMAGEM = Set.of("image/jpeg", "image/png", "image/webp");
    static final String PASTA_ARMAZENAMENTO = "banners";

    /** Caminho interno ("/produtos?promo=true") ou endereço https. "//x" é recusado: seria um endereço externo disfarçado. */
    private static final Pattern CAMINHO_INTERNO = Pattern.compile("^/(?!/)[^\\n\\r\\t]*$");
    private static final Pattern ENDERECO_HTTPS = Pattern.compile("^https://\\S+$");
    private static final String LINK_INVALIDO = "Use um caminho interno (/produtos) ou um endereço https://.";
    private static final List<String> CAMPOS_TEXTO = List.of("etiqueta", "titulo", "texto", "cta", "href", "cta2", "href2");

    private final BannerRepository bannerRepository;
    private final StorageService storageService;

    public BannerService(BannerRepository bannerRepository, StorageService storageService) {
        this.bannerRepository = bannerRepository;
        this.storageService = storageService;
    }

    // --- leitura -------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PublicBannerDto> publicos() {
        return bannerRepository.findByAtivoTrueAndImagemNotOrderByOrdemAscCreatedAtAsc("").stream()
                .map(BannerService::paraPublico).toList();
    }

    @Transactional(readOnly = true)
    public List<BannerDto> listar() {
        return bannerRepository.findAllByOrderByOrdemAscCreatedAtAsc().stream().map(BannerService::paraDto).toList();
    }

    // --- escrita -------------------------------------------------------------------------------------------------

    /** Cria no fim da ordem. Nasce sem imagem, por isso só pode nascer inactivo. */
    @Transactional
    public BannerDto criar(Map<String, Object> corpo) {
        List<Banner> todos = bannerRepository.findAllByOrderByOrdemAscCreatedAtAsc();
        int ordem = todos.stream().mapToInt(Banner::getOrdem).max().orElse(0) + 1;
        Banner novo = new Banner(UUID.randomUUID().toString(), ordem, Instant.now());
        Map<String, String> erros = new LinkedHashMap<>();
        aplicar(novo, corpo, erros);
        validar(novo, erros);
        novo.touch();
        return paraDto(bannerRepository.save(novo));
    }

    /** Só os campos enviados mudam; a validação corre sobre o estado FINAL (como o servidor mock). */
    @Transactional
    public BannerDto atualizar(String id, Map<String, Object> corpo) {
        Banner banner = obter(id);
        // Valida o estado final numa cópia: um pedido inválido nunca deixa a entidade gerida alterada.
        Banner proposto = banner.copiaParaValidar();
        Map<String, String> erros = new LinkedHashMap<>();
        aplicar(proposto, corpo, erros);
        validar(proposto, erros);
        banner.copiarEditaveisDe(proposto);
        banner.touch();
        return paraDto(banner);
    }

    /** {@code ids} tem de ser TODOS os banners, uma vez cada; a ordem passa a 1..n pela ordem recebida. */
    @Transactional
    public List<BannerDto> reordenar(Object ids) {
        List<Banner> todos = bannerRepository.findAllByOrderByOrdemAscCreatedAtAsc();
        String regra = "A nova ordem tem de incluir todos os banners, uma vez cada.";
        if (!(ids instanceof List<?> lista) || lista.size() != todos.size()) throw new ValidationException(regra);
        Set<String> existentes = new HashSet<>();
        todos.forEach(b -> existentes.add(b.getId()));
        Set<String> vistos = new HashSet<>();
        for (Object o : lista) {
            if (!(o instanceof String s) || !existentes.contains(s) || !vistos.add(s)) throw new ValidationException(regra);
        }
        Map<String, Banner> porId = new LinkedHashMap<>();
        todos.forEach(b -> porId.put(b.getId(), b));
        int ordem = 1;
        for (Object o : lista) {
            Banner b = porId.get((String) o);
            if (b.getOrdem() != ordem) {
                b.setOrdem(ordem);
                b.touch();
            }
            ordem++;
        }
        return porId.values().stream().sorted(java.util.Comparator.comparingInt(Banner::getOrdem)).map(BannerService::paraDto).toList();
    }

    @Transactional
    public BannerDto carregarImagem(String id, MultipartFile ficheiro) {
        Banner banner = obter(id);
        if (ficheiro == null || ficheiro.isEmpty()) throw new ValidationException("Envie a imagem no campo «image».");
        if (!FORMATOS_IMAGEM.contains(ficheiro.getContentType())) {
            throw new ValidationException("Formato de imagem não suportado (use JPG, PNG ou WEBP).");
        }
        if (ficheiro.getSize() > MAX_IMAGEM_BYTES) throw new ValidationException("A imagem excede 5 MB.");
        byte[] bytes;
        try {
            bytes = ficheiro.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível ler o ficheiro enviado.", e);
        }
        // Valida o CONTEÚDO contra o tipo declarado (um .exe renomeado para .jpg é recusado) e guarda no provider configurado.
        banner.setImagem(storageService.saveFile(bytes, ficheiro.getOriginalFilename(), ficheiro.getContentType(),
                "banner-" + id, PASTA_ARMAZENAMENTO));
        banner.touch();
        return paraDto(banner);
    }

    /** Remove e volta a numerar 1..n sem buracos. */
    @Transactional
    public BannerDto remover(String id) {
        Banner banner = obter(id);
        BannerDto removido = paraDto(banner);
        bannerRepository.delete(banner);
        bannerRepository.flush();
        int ordem = 1;
        for (Banner b : bannerRepository.findAllByOrderByOrdemAscCreatedAtAsc()) {
            if (b.getOrdem() != ordem) b.setOrdem(ordem);
            ordem++;
        }
        return removido;
    }

    // --- validação -----------------------------------------------------------------------------------------------

    private Banner obter(String id) {
        return bannerRepository.findById(id).orElseThrow(() -> new NotFoundException("Banner"));
    }

    /** Copia só as chaves presentes no corpo. Texto em branco = ausente. Um tipo errado vira erro do campo. */
    private static void aplicar(Banner alvo, Map<String, Object> corpo, Map<String, String> erros) {
        if (corpo == null) return;
        for (String campo : CAMPOS_TEXTO) {
            if (!corpo.containsKey(campo)) continue;
            Object valor = corpo.get(campo);
            if (valor != null && !(valor instanceof String)) {
                erros.put(campo, "Tem de ser texto.");
                continue;
            }
            String texto = valor == null ? "" : ((String) valor).trim();
            String guardado = texto.isEmpty() ? null : texto;
            switch (campo) {
                case "etiqueta" -> alvo.setEtiqueta(guardado);
                case "titulo" -> alvo.setTitulo(guardado);
                case "texto" -> alvo.setTexto(guardado);
                case "cta" -> alvo.setCta(guardado);
                case "href" -> alvo.setHref(guardado);
                case "cta2" -> alvo.setCta2(guardado);
                default -> alvo.setHref2(guardado);
            }
        }
        if (corpo.containsKey("ativo")) {
            if (corpo.get("ativo") instanceof Boolean b) alvo.setAtivo(b);
            else erros.put("ativo", "Tem de ser verdadeiro ou falso.");
        }
    }

    private static void validar(Banner b, Map<String, String> erros) {
        obrigatorio(erros, "titulo", b.getTitulo(), MAX_TITULO);
        obrigatorio(erros, "texto", b.getTexto(), MAX_TEXTO);
        obrigatorio(erros, "cta", b.getCta(), MAX_CTA);
        if (b.getEtiqueta() != null && b.getEtiqueta().length() > MAX_ETIQUETA) erros.putIfAbsent("etiqueta", "Máximo " + MAX_ETIQUETA + " caracteres.");
        if (b.getHref() == null || !linkValido(b.getHref())) erros.putIfAbsent("href", LINK_INVALIDO);
        if (b.getCta2() != null && b.getHref2() == null) erros.putIfAbsent("href2", "Indique a ligação da segunda chamada.");
        if (b.getHref2() != null && !linkValido(b.getHref2())) erros.putIfAbsent("href2", LINK_INVALIDO);
        if (b.getCta2() != null && b.getCta2().length() > MAX_CTA) erros.putIfAbsent("cta2", "Máximo " + MAX_CTA + " caracteres.");
        if (b.isAtivo() && b.getImagem().isEmpty()) erros.putIfAbsent("ativo", "Um banner só pode ser activado depois de ter imagem.");
        if (!erros.isEmpty()) throw new ValidationException("Verifique os campos assinalados.", erros);
    }

    private static void obrigatorio(Map<String, String> erros, String campo, String valor, int max) {
        if (valor == null) erros.putIfAbsent(campo, "Obrigatório.");
        else if (valor.length() > max) erros.putIfAbsent(campo, "Máximo " + max + " caracteres.");
    }

    static boolean linkValido(String v) {
        return CAMINHO_INTERNO.matcher(v).matches() || ENDERECO_HTTPS.matcher(v).matches();
    }

    // --- mapeamento ----------------------------------------------------------------------------------------------

    static BannerDto paraDto(Banner b) {
        return new BannerDto(b.getId(), b.getEtiqueta(), b.getTitulo(), b.getTexto(), b.getCta(), b.getHref(), b.getCta2(),
                b.getHref2(), b.getImagem(), b.isAtivo(), b.getOrdem(), b.getUpdatedAt());
    }

    static PublicBannerDto paraPublico(Banner b) {
        return new PublicBannerDto(b.getId(), b.getEtiqueta(), b.getTitulo(), b.getTexto(), b.getCta(), b.getHref(),
                b.getCta2(), b.getHref2(), b.getImagem());
    }
}
