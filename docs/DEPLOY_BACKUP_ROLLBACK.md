# Backup, recuperação e rollback (PostgreSQL 17 em Docker Swarm)

Guia para quem faz o deploy do backend. Nada aqui se executa sozinho: **cada passo é manual e é feito por quem
administra o servidor**. Os nomes assumem a stack de `docker-stack.swarm.yml` (stack `kixima`; serviços `db_kixima`, base
`kixima`, utilizador `kixima`; backend `kixima`).

> **Como foi validado.** Os passos de PostgreSQL (dump, verificação, restauro, `psql`) foram ensaiados contra um
> PostgreSQL 17.11 local com dados sintéticos: 51 tabelas, contagens e *hash* dos dados idênticos depois do restauro, histórico
> do Flyway restaurado. Os comandos `docker` (`exec`, `service scale`, `service update`) são os padrão do Docker e **não foram
> executados** neste ambiente (não há daemon Docker); confirme-os no servidor com um ensaio antes do dia do deploy.

## 0. Antes de começar

- O backup tem de ser feito **imediatamente antes** do deploy, com o backend parado, para que o backup seja exactamente o
  estado que a migração vai encontrar (nada se perde entre o backup e o arranque da nova versão).
- A V2 e a V3 correm **sozinhas** no primeiro arranque do JAR novo (`FLYWAY_ENABLED=true`, por omissão em `prod`). Algumas das
  alterações da V2 **não se desfazem** (ver §5). Sem backup verificado, não se faz o deploy.
- Guarde o backup **fora do servidor da base** (outra máquina ou armazenamento externo). Um backup só no mesmo disco não é backup.

## 1. Backup obrigatório antes do deploy

Corra no nó onde está o contentor da base (a stack usa um volume local, ver `placement` em `docker-stack.swarm.yml`).

```bash
set -euo pipefail
DB=$(docker ps -q -f name=kixima_db_kixima | head -n1)          # contentor da base
test -n "$DB" || { echo "contentor da base não encontrado"; exit 1; }

# 1) Parar as escritas: o backend fica a 0 réplicas até ao deploy (a base continua de pé).
docker service scale kixima_kixima=0

# 2) Dump em formato custom (comprimido, permite restauro selectivo). O pg_dump corre DENTRO do contentor da base:
#    é o cliente 17.11, a mesma versão do servidor, e liga por socket local (sem senha na linha de comandos).
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p /opt/kixima/backups && cd /opt/kixima/backups
docker exec "$DB" pg_dump -U kixima -d kixima -Fc --no-owner --no-privileges > "kixima-$STAMP.dump"

# 3) Impressão digital, para detectar corrupção na cópia para fora do servidor.
sha256sum "kixima-$STAMP.dump" | tee "kixima-$STAMP.dump.sha256"
ls -l "kixima-$STAMP.dump"
```

Depois copie `kixima-$STAMP.dump` e o `.sha256` para fora do servidor e **verifique lá** (§2). Mantenha pelo menos os 7
últimos diários e os 4 últimos semanais, e **nunca** apague o backup pré-deploy antes de a nova versão estar validada.

## 2. Verificação da integridade do backup

Um ficheiro que existe não é um backup que funciona. Faça os três passos, por esta ordem.

```bash
cd /opt/kixima/backups            # (ou onde guardou a cópia)

# a) O ficheiro não mudou desde que foi criado
sha256sum -c "kixima-$STAMP.dump.sha256"                         # tem de dizer: OK

# b) O índice do dump lê-se e traz todas as tabelas
docker exec -i "$DB" pg_restore --list < "kixima-$STAMP.dump" | grep -c 'TABLE DATA'
docker exec "$DB" psql -U kixima -d kixima -tA -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'"
# os dois números têm de ser iguais (no ensaio: 51 e 51)

# c) Ensaio de restauro numa base descartável (não toca na base real)
docker exec "$DB" createdb -U kixima kixima_verificacao
docker exec -i "$DB" pg_restore -U kixima -d kixima_verificacao --no-owner --no-privileges --exit-on-error < "kixima-$STAMP.dump"
for T in users companies products purchase_orders flyway_schema_history; do
  A=$(docker exec "$DB" psql -U kixima -d kixima              -tA -c "SELECT count(*) FROM public.$T")
  B=$(docker exec "$DB" psql -U kixima -d kixima_verificacao  -tA -c "SELECT count(*) FROM public.$T")
  echo "$T  original=$A  restaurada=$B  $([ "$A" = "$B" ] && echo OK || echo DIFERENTE)"
done
docker exec "$DB" dropdb -U kixima kixima_verificacao
```

Se algum passo falhar, **não avance com o deploy**: refaça o backup.

## 3. Procedimento de recuperação

Use-o quando for preciso voltar ao estado do backup (dados corrompidos, migração com problemas, apagão). **Tudo o que foi
escrito na base depois do backup perde-se.** Com o backend parado:

```bash
DB=$(docker ps -q -f name=kixima_db_kixima | head -n1)
docker service scale kixima_kixima=0

# 1) Restaurar para uma base NOVA (a base actual fica intacta até confirmar)
docker exec "$DB" createdb -U kixima kixima_restaurada
docker exec -i "$DB" pg_restore -U kixima -d kixima_restaurada --no-owner --no-privileges --exit-on-error < "kixima-$STAMP.dump"

# 2) Conferir (contagens como em §2c) e só então trocar os nomes
docker exec "$DB" psql -U kixima -d postgres \
  -c "ALTER DATABASE kixima RENAME TO kixima_anterior" \
  -c "ALTER DATABASE kixima_restaurada RENAME TO kixima"
#    (se recusar por "being accessed by other users": confirme que o backend está a 0 réplicas e repita)

# 3) Arrancar o backend com a versão que corresponde ao backup (§4) e validar
docker service scale kixima_kixima=1
docker exec $(docker ps -q -f name=kixima_kixima | head -n1) wget -qO- http://127.0.0.1:4001/ready
#    -> {"status":"ok","database":"ok","tables":"ok",...}

# 4) Só depois de validado: apagar a base anterior
# docker exec "$DB" dropdb -U kixima kixima_anterior
```

**Backup feito pela própria aplicação** (`.sql.gz` no bucket de cópias, formato SQL com `--clean --if-exists`): descarregue-o
e restaure com `psql`; testado, também por cima de uma base existente.

```bash
gunzip -t kixima-AAAAMMDD-HHMMSS.sql.gz                          # o gzip está íntegro?
gunzip -c kixima-AAAAMMDD-HHMMSS.sql.gz | docker exec -i "$DB" psql -U kixima -d kixima_restaurada -v ON_ERROR_STOP=1 -q
```

O histórico do Flyway (`flyway_schema_history`) vem no dump: se o backup é anterior ao deploy, a base volta a V1/V2 e a nova
versão do backend volta a aplicar V2/V3 ao arrancar (é seguro, são idempotentes).

## 4. Rollback do backend

O rollback do **código** é independente do da **base de dados**. Escolha pelo sintoma:

| Situação | O que fazer |
|---|---|
| A nova versão tem um defeito, mas os dados estão bem | Voltar à imagem anterior (abaixo). A base **fica como está** (V3). |
| A migração estragou dados, ou é preciso voltar a antes da V2 | Parar o backend, **restaurar o backup** (§3) e arrancar a imagem anterior. |

```bash
# Voltar à especificação anterior do serviço (imagem e variáveis)
docker service update --rollback kixima_kixima

# ou, explicitamente, a uma imagem conhecida (use sempre etiquetas imutáveis, ex.: o SHA do commit — nunca `latest`)
docker service update --image <registo>/kixima-backend:<etiqueta-anterior> kixima_kixima
```

Atenção: um novo `docker stack deploy` volta a aplicar o ficheiro e anula o rollback. Altere `BACKEND_IMAGE` para a etiqueta
anterior antes de voltar a fazer deploy.

**Ensaiado:** o JAR anterior (`a597408`, que só conhece V1 e V2) arrancou normalmente, em perfil `prod`, contra uma base PostgreSQL
17 já migrada para a V3, tanto com `FLYWAY_ENABLED=true` como com `false`. Não toca na tabela `banners` nem no histórico. O que
se perde é só a funcionalidade nova: a lista pública `/api/public/banners` e a gestão `/api/admin/banners` deixam de existir
(verificado no front: se o pedido falhar, `Banners.vue` fica com a lista vazia e não mostra o carrossel).

## 5. Limitações do rollback depois da V2 e da V3

O Flyway Community **não tem "undo"**: não há migração inversa automática.

**V2 (cadastro progressivo) — parte não se desfaz sem backup**

- O valor `RASCUNHO` do tipo `CompanyStatus` **não pode ser removido** (o PostgreSQL não retira valores de um enum).
- `companies.name` e `companies.tax_id` deixam de ser `NOT NULL`. Depois do deploy, novos registos podem ter esses campos vazios
  (empresas em rascunho): repor o `NOT NULL` **falha** enquanto existirem, e código anterior à V2 não os espera.
- `users.email_verified_at` foi preenchido para todos os utilizadores existentes (contas já confirmadas). Não se distingue depois
  o que era `NULL` antes.
- Não foi possível ensaiar código **anterior à V2** (não há JAR dessa fase): o risco conhecido é uma empresa em `RASCUNHO`, criada
  depois do deploy, que esse código não sabe ler (valor de enum desconhecido). Se a produção actual for anterior à V2, o rollback
  seguro para essa versão é **restaurar o backup pré-deploy**, aceitando perder o que foi escrito desde então.

**V3 (banners) — aditiva, mas sem reverso limpo**

- Cria a tabela `banners` e semeia 4 linhas; o código anterior ignora-a. Apagá-la à mão (`DROP TABLE banners`) deixa o histórico do
  Flyway a dizer que a V3 está aplicada, e a nova versão **não a recria**. Não o faça: se for preciso voltar atrás, restaure o backup.

**Sempre**

- Voltar a um backup perde **tudo** o que foi escrito depois dele (registos, ordens, pagamentos). Por isso o backup é feito com o
  backend parado (§1) e o deploy segue-se logo a seguir.
- Nunca use `docker volume rm kixima_pgdata`, `docker stack rm` seguido de limpeza de volumes, nem `flyway clean` na produção.

## 6. O `pg_dump` da aplicação (investigação) e solução recomendada

**O que a aplicação faz.** O botão «Fazer cópia agora» e o agendamento `BACKUP_CRON` correm `pg_dump --no-owner --no-privileges
--clean --if-exists <url>` **dentro do contentor do backend**, comprimem o resultado em memória (gzip) e enviam-no para o bucket
privado `STORAGE_BACKUP_BUCKET` (ficheiro `kixima-AAAAMMDD-HHMMSS.sql.gz`). A verificação lê o ficheiro de volta e confere
que descomprime e que traz todas as tabelas (`CREATE TABLE`/`COPY`); **não é um ensaio de restauro**.

**Porque falha em produção.** A imagem do backend (`Dockerfile`, estágio `runtime`) parte de `eclipse-temurin:21-jre-alpine` e
**não instala o cliente PostgreSQL**. O ecrã «Prontidão para produção» já assinala `pg_dump: Não está instalada nesta imagem` e
com o agendamento ligado (`BACKUP_CRON`) a cópia falha todas as noites e o botão falha sempre. O `pg_dump` também não pode ser mais antigo do que o servidor (17), e a Prontidão verifica-o.

**Recomendação (duas camadas, por esta ordem de importância)**

1. **Backup operacional independente da aplicação** — o procedimento do §1, com o `pg_dump` 17.11 do próprio contentor da base.
   É o que vale antes de cada deploy e não depende da imagem do backend.
2. **Cliente PostgreSQL 17 na imagem do backend** (aplicado no `Dockerfile`), para o backup agendado e o botão funcionarem:
   base fixada em `eclipse-temurin:21-jre-alpine-3.24` e `apk add --no-cache postgresql17-client`, seguido de uma verificação que
   **falha o build** se o `pg_dump` instalado não for o 17.

   - **Confirmado** (Docker Hub e receitas oficiais do Alpine, ramo `3.24-stable`): a etiqueta `21-jre-alpine` é hoje o Alpine 3.24;
     o pacote `postgresql17-client` existe no repositório `main` na versão **17.11-r0** (a do servidor); inclui `pg_dump`,
     `pg_restore` e `psql`; um *trigger* do `postgresql-common` liga-os a `/usr/bin`, onde a aplicação os procura.
   - **Não confirmado** (sem Docker, e os espelhos do Alpine estão bloqueados no ambiente de preparação): a instalação real com
     `apk` e a saída de `pg_dump --version` dentro da imagem. **Confirme-o no primeiro build:** o log mostra a versão exacta
     (`pg_dump (PostgreSQL) 17.x`) e, depois, `docker run --rm --entrypoint pg_dump <imagem> --version`; no ecrã «Prontidão»,
     a linha «Ferramenta de cópia (pg_dump)» tem de ficar OK. Para o tamanho: `docker image ls` antes e depois (só se instala o `-client`).
   - **Se o build falhar** (pacote ausente numa versão futura do Alpine, ou versão diferente da 17): não use o `postgresql-client`
     genérico (instalaria outro major) nem `postgresql18-client` (um `pg_dump` 18 produz SQL que o servidor 17 pode não aceitar ao
     restaurar). Alternativas: (a) manter o backup operacional do §1 e desligar o da aplicação (`KIXIMA_JOB_BACKUP_ENABLED=false`);
     (b) um serviço Swarm com a imagem `postgres:17.11` que corre o §1 por agendamento; (c) base Debian (`eclipse-temurin:21-jre`) com o
     `postgresql-client-17` do repositório PGDG (imagem maior).

**Limitações da implementação a ter em conta** (não alteradas; as melhorias propostas e os seus testes de aceitação estão em [`BACKUP_MELHORIAS.md`](BACKUP_MELHORIAS.md))

- O dump inteiro e a cópia comprimida ficam **em memória** no JVM: sem limite de memória no serviço (`docker-stack.swarm.yml`),
  uma base grande pode matar o contentor. Defina `deploy.resources.limits.memory` com folga (≥ 3× o tamanho do dump).
- O URL passado ao `pg_dump` leva a **senha na linha de comandos** (visível em `/proc/<pid>/cmdline` dentro do contentor). Melhor:
  passar `PGPASSWORD` por variável de ambiente do processo.
- O formato é SQL em texto (restaura-se com `psql`), não o formato custom: não permite restauro selectivo nem paralelo.
- Uma **segunda cópia, feita pelo §1**, continua a ser necessária: a da aplicação vive no mesmo ecossistema e não está ensaiada.

## 7. Lista de verificação do dia do deploy

1. [ ] Backup feito com o backend a 0 réplicas (§1) e **verificado** (§2), com cópia fora do servidor.
2. [ ] Etiqueta da imagem anterior anotada (para o rollback do §4).
3. [ ] Secrets e variáveis criados (`docker-stack.swarm.yml`, cabeçalho) e `APP_URL` igual à origem pública.
4. [ ] Deploy; observar o log: `Successfully applied 2 migrations … now at version v3`, depois `Started KiximaApplication`.
5. [ ] `/ready` com `status: ok`; abrir o site; Prontidão para produção sem falhas.
6. [ ] Só depois: manter o backup pré-deploy durante pelo menos 7 dias.
