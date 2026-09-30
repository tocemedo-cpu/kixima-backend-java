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
| `render.yaml`, CI (`.github/workflows/ci.yml`) | Foram escritos para a topologia "serviço único ao lado do Node, no Render" — não servem de base para o deploy deste projecto. Este repositório não depende do Render nem de nenhum serviço gerido específico (ver secção "Produção standalone" abaixo); um `render.yaml` só faz sentido se decidires mesmo usar o Render, e nesse caso é responsabilidade de quem faz esse deploy escrevê-lo. |

## Base de dados e schema — LEIAM ANTES DE PÔR ISTO EM PRODUÇÃO

**Isto é a dependência que a separação de repositórios NÃO resolve.** O
Hibernate está configurado com `ddl-auto: validate` (`application.yml`) — ele
só **lê** a estrutura de tabelas/colunas que já existe na base de dados;
nunca a cria nem altera. Até agora, quem criava e evoluía essa estrutura era
o Prisma, a partir das migrações do backend Node (`backend/prisma/migrations`
no monorepo original).

Este repositório é standalone: tem a SUA PRÓPRIA base de dados PostgreSQL,
nova, criada do zero — nunca partilhada com o backend Node nem com nenhum
outro serviço, arranque ou desligado o Node estejam. O único vínculo que
resta ao Node é histórico: o schema desta base nova nasceu de um `pg_dump`
da estrutura que o Prisma tinha criado (ver abaixo); depois de aplicado, o
Flyway (não o Prisma) passa a ser quem evolui este schema.

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
| `SPRING_PROFILES_ACTIVE` | `prod` em produção (ver secção "Produção standalone" abaixo) |

## Jobs agendados — desligados por omissão

`retencao`, `expiracao-apolices`, `expiracao-subscricoes`, `lembretes-2fa`,
`po-robot` e `backup` vêm todos com `enabled: false`. Isto fazia sentido
quando outro backend (Node) já os corria em produção — agora que este
projecto é standalone, **nada corre nenhum destes jobs até os ligares
explicitamente** com as variáveis `KIXIMA_JOB_*_ENABLED=true` correspondentes
(ver `application.yml`, bloco `kixima.jobs`).

Numa produção standalone (sem o Node ao lado a cobri-los), a recomendação
por job é:

| Job | Variável | Recomendação em standalone |
|---|---|---|
| `retencao` | `KIXIMA_JOB_RETENCAO_ENABLED` | **Ligar.** Sem ele, notificações/convites/códigos antigos nunca são apagados — passa a ser o único processo que aplica os prazos de retenção. |
| `expiracao-apolices` | `KIXIMA_JOB_EXPIRACAO_APOLICES_ENABLED` | **Ligar.** Alerta operacional de apólices a expirar — não há outro sítio onde isto corra. |
| `expiracao-subscricoes` | `KIXIMA_JOB_EXPIRACAO_SUBSCRICOES_ENABLED` | **Ligar.** Idem, para subscrições — afecta directamente o acesso de empresas à plataforma. |
| `lembretes-2fa` | `KIXIMA_JOB_LEMBRETES_2FA_ENABLED` | **Ligar.** Lembretes de segurança a Admin do Sistema/Company Admin sem MFA activo. |
| `backup` | `KIXIMA_JOB_BACKUP_ENABLED` | **Ligar só depois de configurar `BACKUP_CRON` e `STORAGE_BACKUP_BUCKET`** (bucket S3 dedicado, nunca o das imagens) — sem isso o job liga mas não tem para onde escrever. Crítico para produção standalone: já não há Node a fazer cópias de segurança desta base nova. |
| `po-robot` | `KIXIMA_JOB_PO_ROBOT_ENABLED` | **Decisão de negócio, não técnica.** É a automatização do add-on pago "Robô de PO" (`kixima.addons.po-robot`) — só liga se esse add-on estiver mesmo à venda/activo para alguma empresa; caso contrário deixa-se desligado sem perda de funcionalidade core. |

Decide isto **antes** de pôr este serviço em produção sozinho — ver também
`ProducaoStartupGuard`, que recusa arrancar em `prod` com `JWT_SECRET`,
armazenamento ou CORS/cookie mal configurados, mas **não** valida os jobs
(é uma escolha operacional, não uma falha de segurança).

## Produção standalone (sem Render)

Este backend não depende de nenhum serviço gerido específico (Render,
Railway, Fly.io, ...) — corre em qualquer host capaz de executar um JAR
Spring Boot ou uma imagem de contentor, ligado à sua própria base PostgreSQL.

### 1. Perfil de produção

Arranca sempre com `SPRING_PROFILES_ACTIVE=prod` (ou equivalente
`-Dspring.profiles.active=prod`). Com este perfil activo, `application-prod.yml`
remove os valores por omissão de `DATABASE_URL_JDBC`/`DATABASE_USER`/
`DATABASE_PASSWORD`/`JWT_SECRET` (a aplicação recusa-se a arrancar sem eles) e
`ProducaoStartupGuard` acrescenta verificações adicionais, falhando o
arranque — em vez de arrancar mal configurado — se:
- `JWT_SECRET` estiver vazio, for um valor de exemplo, ou tiver menos de 32 caracteres;
- `STORAGE_PROVIDER` não for `s3` (armazenamento local é efémero num contentor) ou faltarem credenciais S3;
- nenhuma origem web estiver configurada (`APP_URL` ou `CORS_ORIGINS` vazios);
- um perfil `dev`/`test` estiver activo ao lado de `prod` (reabriria CORS/cookie por acidente).

### 2. Base de dados PostgreSQL própria

Este serviço nunca partilha base de dados com o backend Node nem com
nenhum outro serviço — ver "Base de dados e schema" acima. Procedimento
completo, passo a passo, em **`docs/provisionamento-postgresql.md`**:
criar um PostgreSQL 13+ (role + base dedicados), apontar
`DATABASE_URL_JDBC`/`DATABASE_USER`/`DATABASE_PASSWORD` para lá, e ligar
`FLYWAY_ENABLED=true` para o `V1__baseline.sql` aplicar o schema completo
automaticamente no primeiro arranque contra uma base vazia. Depois desse
primeiro arranque, `ddl-auto: validate` garante, em todos os arranques
seguintes, que o schema na base bate certo com as 47 entidades JPA — se
alguém alterar a base por fora, a aplicação recusa-se a arrancar em vez de
servir pedidos contra um schema inesperado.

### 3. Imagem de contentor

O `Dockerfile` na raiz do repositório produz uma imagem standalone
(multi-stage: `maven:3.9-eclipse-temurin-21` para compilar,
`eclipse-temurin:21-jre-alpine` para correr, utilizador não-root) — sem
nenhuma referência ao Render ou a qualquer serviço gerido:

```bash
docker build -t kixima-backend-java .
docker run -p 4001:4001 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL_JDBC=jdbc:postgresql://<host>:5432/kixima_java \
  -e DATABASE_USER=kixima_java \
  -e DATABASE_PASSWORD=... \
  -e JWT_SECRET=... \
  -e APP_URL=https://... \
  -e STORAGE_PROVIDER=s3 -e STORAGE_BUCKET=... -e STORAGE_ACCESS_KEY=... -e STORAGE_SECRET_KEY=... \
  kixima-backend-java
```

Funciona da mesma forma sem Docker, com `mvn package` + `java -jar
target/kixima-backend-java-*.jar` e as mesmas variáveis de ambiente.

Não existe `render.yaml` neste repositório (de propósito — ver tabela
acima); a orquestração (systemd, Kubernetes, docker-compose, o painel de um
PaaS à tua escolha) fica fora do âmbito deste projecto.

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
