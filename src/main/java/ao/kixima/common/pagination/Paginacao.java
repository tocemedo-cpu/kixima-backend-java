package ao.kixima.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Espelha backend/src/utils/paginacao.js — {@code ?page=&limit=} convertido
 * num {@link Pageable} do Spring Data, com o mesmo tecto de segurança
 * (`limit` absurdo não passa, `page` negativo volta à primeira). O envelope
 * de resposta ({@link PaginaResposta}) usa os MESMOS nomes de campo em
 * português do Node ({@code itens/total/pagina/porPagina/paginas}) — é
 * contrato de API partilhado com o frontend, não uma escolha interna.
 */
public final class Paginacao {

    public static final int POR_OMISSAO = 25;
    public static final int MAXIMO = 100;

    private Paginacao() {
    }

    public static Pageable parametros(Integer page, Integer limit, Sort sort) {
        int pagina = Math.max(1, page == null ? 1 : page);
        int porPagina = (limit != null && limit > 0) ? Math.min(MAXIMO, limit) : POR_OMISSAO;
        return PageRequest.of(pagina - 1, porPagina, sort == null ? Sort.unsorted() : sort);
    }

    public static Pageable parametros(Integer page, Integer limit) {
        return parametros(page, limit, null);
    }

    public static <T> PaginaResposta<T> envelope(org.springframework.data.domain.Page<T> pagina) {
        return new PaginaResposta<>(pagina.getContent(), pagina.getTotalElements(), pagina.getNumber() + 1,
                pagina.getSize(), Math.max(1, pagina.getTotalPages()));
    }
}
