# KIXIMA — Backend Java

API REST do KIXIMA em Java 21 / Spring Boot 3, projecto **independente**: compila,
testa e arranca sozinho, sem precisar de nenhum outro repositório.

Este código nasceu como uma migração incremental, lado a lado, de um backend
Node/Express/Prisma existente — por isso vais encontrar, no código-fonte,
muitos comentários do tipo *"Espelha backend/src/services/x.js"*. Ficaram
propositadamente: documentam a origem e a razão de cada decisão de
comportamento (porque é que um erro tem este código, porque é que um valor
decimal sai como texto, etc.), o que continua útil mesmo sem o outro projecto
ao lado. Nenhum desses comentários implica uma dependência real — confirma-se
abaixo.

## Estado desta separação

Este repositório foi extraído do monorepo original (`tocemedo-cpu/Kixima`,
pasta `backend-java/`) em 2026-09-29. O código-fonte já não tinha nenhuma
dependência de ficheiros fora dessa pasta (confirmado por auditoria — zero
referências de caminho a `../backend` ou `../frontend` em `src/` ou no
`pom.xml`); a única coisa que ficou deliberadamente de fora foi um conjunto de
classes acrescentadas numa tentativa anterior de "serviço único" (Java a
servir também o frontend compilado) — ver a secção seguinte.

## O que ficou de fora, e porquê

| O quê | Porquê ficou de fora |
|---|---|
| `frontend/` (pacote Java: `FrontendDist`, `SpaHandlerMapping`) | Decisão explícita: este projecto é uma **API pura**. Não compila nem serve o frontend. Se precisares de voltar a esta funcionalidade, o código existe no monorepo original, commit da branch `backup-antes-reset-20260928`. |
| `config/DatabaseUrlEnvironmentPostProcessor` + `META-INF/spring.factories` | Traduzia uma `DATABASE_URL` no formato do Node (connection string única) para as três propriedades que o Spring espera — só fazia sentido enquanto este serviço partilhava o painel de variáveis do Render com o backend Node. Configura a base de dados com `DATABASE_URL_JDBC` + `DATABASE_USER` + `DATABASE_PASSWORD` (já suportado nativamente, ver `application.yml`). |
| Ganchos do SPA em `PublicPaths`, `AuthenticationFilter`, `GlobalExceptionHandler` | Consequência directa de não servir o frontend — nada para marcar como público nem nenhum 404 de ficheiro estático a tratar. |
| `paridade/` (scripts de comparação Node×Java) | Ferramenta que arranca os DOIS backends para comparar respostas — não faz sentido dentro de um repositório só-Java. Se precisares dela outra vez, está no monorepo original. |
| `Dockerfile`, `render.yaml`, CI (`.github/workflows/ci.yml`) | Foram escritos para a topologia "serviço único ao lado do Node" — não servem de base para o deploy deste projecto. O deploy standalone fica para uma fase seguinte. |

## Base de dados e schema — LEIAM ANTES DE PÔR ISTO EM PRODUÇÃO

**Isto é a dependência que a separação de repositórios NÃO resolve.** O
Hibernate está configurado com `ddl-auto: validate` (`application.yml`) — ele
só **lê** a estrutura de tabelas/colunas que já existe na base de dados;
nunca a cria nem altera. Até agora, quem criava e evoluía essa estrutura era
o Prisma, a partir das migrações do backend Node (`backend/prisma/migrations`
no monorepo original).

Este projecto corre em **paralelo** ao backend Node, não substituindo-o: o
Node continua a servir a sua própria base de dados PostgreSQL, sem qualquer
alteração. Este repositório terá a SUA PRÓPRIA base de dados, nova, criada
do zero — nunca partilhada com o Node.

**`src/main/resources/db/migration/V1__baseline.sql`** é o ponto de partida
dessa base nova: um `pg_dump --schema-only` da base do Node em 2026-09-29,
usada só como referência (nunca alterada), limpo para o Flyway. Contém tudo
o que as 47 entidades JPA (+ `reference_counters`/`series_faturacao`, geridas
por SQL nativo) precisam: 1 extensão (`pg_trgm`), 35 tipos ENUM nativos, 49
tabelas, 163 índices, 64 chaves estrangeiras, e as 3 funções + 2 triggers que
mantêm `products.search_text`/`companies.search_text` actualizados — sem eles
a pesquisa fica muda em silêncio, porque nenhum código Java escreve nessas
colunas.

**Validado** (aplicação limpa contra uma base local descartável, diff de
schema idêntico ao original, suite JUnit a arrancar com sucesso contra ela —
ver o cabeçalho do próprio ficheiro) mas **`FLYWAY_ENABLED` continua `false`
por omissão** — ligar só depois de a base de dados nova estar mesmo criada
(`FLYWAY_ENABLED=true` + `DATABASE_URL_JDBC` a apontar para ela).

`docs/schema-referencia.prisma` é uma cópia estática (não vinculada, não lida
por este projecto) do schema Prisma tal como estava em 2026-09-29 — serve só
de referência adicional, em formato Prisma. Não é actualizada automaticamente.

## A correr localmente

Precisas de um PostgreSQL 16 local (ou remoto) com a mesma estrutura de
tabelas do KIXIMA. Sem essa base, a aplicação arranca mas todas as operações
que tocam na base de dados falham.

```bash
mvn test          # corre a suite JUnit contra localhost:5432/kixima_test
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Variáveis de ambiente principais (ver `application.yml` para a lista
completa — está tudo documentado inline):

| Variável | Para quê |
|---|---|
| `DATABASE_URL_JDBC` / `DATABASE_USER` / `DATABASE_PASSWORD` | Ligação à base de dados |
| `JWT_SECRET` | Assinatura de sessão (obrigatória em produção) |
| `PORT` | Porta HTTP (omissão 4001) |
| `STORAGE_PROVIDER` (+ `STORAGE_*`) | `local` (disco, dev) ou `s3` (produção) |
| `EMAIL_PROVIDER` (+ variáveis do provider) | `console` (log, dev), `smtp` ou `brevo` |
| `AGT_*` | Assinatura e submissão de documentos fiscais (Angola, AGT) |
| `KIXIMA_JOB_*_ENABLED` | Jobs agendados — **todos desligados por omissão**, ver abaixo |

## Jobs agendados — desligados por omissão

`retencao`, `expiracao-apolices`, `expiracao-subscricoes`, `lembretes-2fa`,
`po-robot` e `backup` vêm todos com `enabled: false`. Isto fazia sentido
quando outro backend (Node) já os corria em produção — agora que este
projecto é standalone, **nada corre nenhum destes jobs até os ligares
explicitamente** com as variáveis `KIXIMA_JOB_*_ENABLED=true` correspondentes
(ver `application.yml`, bloco `kixima.jobs`). Decide isto antes de pôr este
serviço em produção sozinho.

## Estrutura

```
src/main/java/ao/kixima/
  config/        Configuração transversal (Jackson, Sentry, tecto de linhas, guarda de arranque em produção)
  security/      JWT/cookie, RBAC (@RequireRole/@RequirePermission), TOTP, MFA por email, rate limiting
  common/        Envelope de erro, paginação, referências, dinheiro/decimais
  agt/           Integração fiscal angolana (AGT) — assinatura JWS, submissão sandbox
  <domínio>/     Um pacote por domínio de negócio (auth, company, catalog, po, invoice,
                 payment, contract, policy, support, notification, admin, ...)
    *Controller.java   Camada HTTP fina
    *Service.java      Lógica de negócio
    *Repository.java   Spring Data JPA
  jobs (*Job.java, em vários pacotes)   6 tarefas @Scheduled, desligadas por omissão
```

Cada domínio segue o mesmo padrão de três camadas. `src/test/java/` espelha a
mesma árvore de pacotes, um a um, com JUnit 5 + MockMvc + Mockito.
