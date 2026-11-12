package ao.kixima;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ponto de entrada. Equivalente a src/server.js no backend Node original de
 * onde este projecto nasceu — ver README.md para o histórico.
 *
 * Projecto standalone: liga-se à SUA PRÓPRIA base de dados PostgreSQL
 * (nunca partilhada com o backend Node nem com nenhum outro serviço). O
 * Hibernate nunca cria/altera o schema ({@code ddl-auto: validate} em
 * application.yml); quem o aplica é o Flyway, opt-in via
 * {@code FLYWAY_ENABLED=true} — ver docs/provisionamento-postgresql.md.
 */
// A autenticação é por JWT (AuthenticationFilter) e httpBasic/formLogin estão desligados (SecurityConfig): não há
// utilizadores em memória. Sem esta exclusão o Spring Security cria o utilizador "user" e ESCREVE a sua senha gerada no
// log a cada arranque («Using generated security password») — uma credencial no log que a aplicação nunca usa.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class KiximaApplication {
    public static void main(String[] args) {
        SpringApplication.run(KiximaApplication.class, args);
    }
}
