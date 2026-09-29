package ao.kixima.config;

import ao.kixima.common.persistence.TectoDeLinhas;
import ao.kixima.common.persistence.TectoDeLinhasStatementInspector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga o {@link TectoDeLinhasStatementInspector} ao Hibernate — equivalente
 * ao {@code base.$extends({ query: { $allModels: { findMany } } })} de
 * config/database.js, que embrulha TODAS as leituras em lista de uma vez, em
 * vez de um limite escrito à mão em cada serviço.
 */
@Configuration
public class TectoDeLinhasConfig {

    @Bean
    public HibernatePropertiesCustomizer tectoDeLinhasNoHibernate(
            @Value("${kixima.db.max-rows:" + TectoDeLinhas.POR_OMISSAO + "}") int tecto) {
        return props -> props.put("hibernate.session_factory.statement_inspector", new TectoDeLinhasStatementInspector(tecto));
    }
}
