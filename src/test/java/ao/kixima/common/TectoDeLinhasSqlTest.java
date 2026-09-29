package ao.kixima.common;

import ao.kixima.common.persistence.TectoDeLinhas;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A reescrita de SQL do tecto — só toca no que o `take: TETO_POR_OMISSAO` do Node tocaria. */
class TectoDeLinhasSqlTest {

    private static String aplicar(String sql) {
        return TectoDeLinhas.aplicar(sql, 1000);
    }

    @Test
    void acrescentaLimitAUmSelectSemPaginacao() {
        assertThat(aplicar("select p1_0.id,p1_0.name from products p1_0 where p1_0.active=? order by p1_0.created_at desc"))
                .isEqualTo("select p1_0.id,p1_0.name from products p1_0 where p1_0.active=? order by p1_0.created_at desc limit 1000");
        assertThat(aplicar("SELECT * FROM companies;")).isEqualTo("SELECT * FROM companies limit 1000");
        assertThat(aplicar("with recentes as (select id from invoices limit 5) select i.id from invoices i join recentes r on r.id=i.id"))
                .endsWith("on r.id=i.id limit 1000");
    }

    @Test
    void naoTocaNoQueJaVemPaginado() {
        String limit = "select p1_0.id from products p1_0 limit ?";
        String fetch = "select p1_0.id from products p1_0 order by p1_0.created_at desc offset ? rows fetch first ? rows only";
        String fetchSemOffset = "select p1_0.id from products p1_0 fetch first ? rows only";
        String nativoComLimit = "SELECT p.id FROM products p ORDER BY p.unit_price ASC, p.id LIMIT 40";
        assertThat(aplicar(limit)).isSameAs(limit);
        assertThat(aplicar(fetch)).isSameAs(fetch);
        assertThat(aplicar(fetchSemOffset)).isSameAs(fetchSemOffset);
        assertThat(aplicar(nativoComLimit)).isSameAs(nativoComLimit);
    }

    @Test
    void umLimitDentroDeUmaSubconsultaNaoContaComoPaginacao() {
        assertThat(aplicar("select c.id from companies c where c.id in (select p.supplier_id from products p order by p.rating desc limit 10)"))
                .endsWith("limit 10) limit 1000");
        // Aspas: um ')' ou um 'limit' dentro de texto/identificador não é sintaxe.
        assertThat(aplicar("select a.id from audit_logs a where a.action = 'x) limit 1' and a.\"limit\" = ?"))
                .endsWith("a.\"limit\" = ? limit 1000");
    }

    @Test
    void oLimitEntraAntesDoBloqueioDeLinha() {
        assertThat(aplicar("select po1_0.id from purchase_orders po1_0 where po1_0.id=? for no key update"))
                .isEqualTo("select po1_0.id from purchase_orders po1_0 where po1_0.id=? limit 1000 for no key update");
        assertThat(aplicar("select i.id from invoices i where i.id=? for update"))
                .isEqualTo("select i.id from invoices i where i.id=? limit 1000 for update");
        assertThat(aplicar("select i.id from invoices i where i.id=? for share skip locked"))
                .isEqualTo("select i.id from invoices i where i.id=? limit 1000 for share skip locked");
        // Já paginado E bloqueado: fica como está.
        String ambos = "select s.id from agtseriesfe s order by s.created_at desc limit 1 for update";
        assertThat(aplicar(ambos)).isSameAs(ambos);
    }

    @Test
    void naoTocaEmContagensNemEscritasNemNoContadorDeReferencias() {
        for (String intacto : new String[]{
                "select count(p1_0.id) from products p1_0 where p1_0.active=?",
                "select count(*) from (select 1 from x) t",
                "insert into audit_logs (id,action) values (?,?)",
                "update products set active=? where id=?",
                "delete from notifications where id=?",
                "select nextval('hibernate_sequence')",
                "insert into \"reference_counters\" (\"key\",\"value\") values (?,?) on conflict (\"key\") do update set \"value\"=\"reference_counters\".\"value\"+1 returning \"value\"",
                "select r.key, r.value from reference_counters r",
        }) {
            assertThat(aplicar(intacto)).as(intacto).isSameAs(intacto);
        }
        assertThat(TectoDeLinhas.aplicar(null, 1000)).isNull();
    }
}
