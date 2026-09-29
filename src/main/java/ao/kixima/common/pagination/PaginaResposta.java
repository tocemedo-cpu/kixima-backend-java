package ao.kixima.common.pagination;

import java.util.List;

/** Envelope de listagem paginada — mesmos nomes de campo do Node (utils/paginacao.js). */
public record PaginaResposta<T>(List<T> itens, long total, int pagina, int porPagina, int paginas) {
}
