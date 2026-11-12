# Melhorias propostas para a cópia de segurança da aplicação

> **Estado: proposta. Nada deste documento está implementado no `BackupService`.** O mecanismo actual não foi alterado: cada
> melhoria abaixo só deve entrar com os testes de aceitação que a acompanham. O procedimento operacional de backup/restauro/rollback
> (independente da aplicação) está em [`DEPLOY_BACKUP_ROLLBACK.md`](DEPLOY_BACKUP_ROLLBACK.md).

## Como funciona hoje (verificado no código)

| Passo | Onde | O que faz |
|---|---|---|
| Dump | `BackupService.correrPgDump` | `pg_dump --no-owner --no-privileges --clean --if-exists <url>`; **lê todo o stdout para um `byte[]`** (`readAllBytes`) |
| Compressão | `BackupService.executar` | gzip nível 9 para um `ByteArrayOutputStream` (**segunda cópia em memória**) |
| Envio | `StorageService.saveFileComChave` | `RequestBody.fromBytes(buffer)` para o bucket privado `STORAGE_BACKUP_BUCKET` |
| Verificação | `BackupVerificationService` | `getObjectAsBytes` → `new String(gz.readAllBytes())` → regex `CREATE TABLE`/`COPY` → `sql.split("\n")` |
| Ensaio de restauro | — | **não existe** (a própria mensagem da verificação diz: «Isto NÃO é um ensaio de restauro») |

Resumo das fragilidades, por ordem de gravidade para a produção:

| # | Fragilidade | Evidência | Gravidade |
|---|---|---|---|
| 1 | A **senha vai na linha de comandos** do `pg_dump` | ensaiado (abaixo): aparece em `/proc/<pid>/cmdline` e no `ps` | Alta (segredo exposto) |
| 2 | Nunca se ensaiou um **restauro real** | não há código nem teste; só a contagem de `CREATE TABLE` | Alta (backup não provado) |
| 3 | O dump inteiro vive na **memória do JVM**, várias vezes | `readAllBytes`, `ByteArrayOutputStream`, `fromBytes`, `getObjectAsBytes`, `String`, `split` | Média (cresce com a base) |
| 4 | A **integridade** só verifica «abre e tem tabelas» | não regista hash; um dump truncado pelo `pg_dump` mas com gzip válido passa | Média |

---

## 1. Evitar carregar o dump completo na memória do JVM

**Problema.** No pico, a criação mantém o dump (`readAllBytes` cresce por duplicação, até ~2× transitoriamente) e a cópia comprimida;
a verificação mantém o ficheiro comprimido, o dump descomprimido em `byte[]`, a `String` e o vector de linhas do `split`. A ordem
de grandeza é **2 a 5× o tamanho do dump** *(estimativa por leitura do código, não medida)*. A stack não define limite de memória
e o heap por omissão do JVM em contentor é 25 % da RAM do contentor: acima de algumas centenas de MB de dump, um
`OutOfMemoryError` é plausível, e pode ocorrer a meio de uma noite de backup sem ninguém reparar.

**Desenho proposto (por ordem de preferência).**

1. **Criação em fluxo, com ficheiro temporário em disco.**
   `pg_dump` (stdout) → `GZIPOutputStream` → ficheiro temporário (`Files.createTempFile`, modo 0600, num volume com espaço
   ≥ tamanho do dump) através de um `DigestOutputStream` (SHA-256 calculado em simultâneo) → envio com
   `RequestBody.fromFile` (o SDK lê o ficheiro em blocos) → apagar o temporário em `finally`. Memória constante (poucos MB).
   O resultado é **byte-a-byte equivalente** ao actual (mesmo `pg_dump`, mesmo gzip), pelo que o formato e o restauro não mudam.
2. **Verificação em fluxo.** `getObject` (`ResponseInputStream`) → `GZIPInputStream` → `BufferedReader` linha a linha, a contar
   `CREATE TABLE`/`COPY` e a procurar a linha final `-- PostgreSQL database dump complete`. Nenhuma `String` com o dump inteiro.
3. *(Só se o disco não for aceitável)* envio *multipart* em fluxo sem ficheiro intermédio (`S3TransferManager`/`AsyncRequestBody`
   com o cliente assíncrono). Mais complexo e com mais dependências; não recomendado como primeiro passo.

Complementos: `deploy.resources.limits.memory` no serviço e `-XX:MaxRAMPercentage` explícito.

**Testes de aceitação.**
- Gerar um dump sintético de ≥ 1 GB (por um *stub* de `pg_dump`) com o JVM limitado a `-Xmx128m`: tem de concluir sem `OutOfMemoryError`.
- Comparar, para a mesma base, o conteúdo **descomprimido** do ficheiro novo com o do mecanismo actual: SHA-256 idêntico.
- `BackupServiceTest` (já corre `pg_dump` real) continua verde; verificar que o temporário é apagado também quando o `pg_dump` falha.

## 2. Evitar passar a senha na linha de comandos

**Problema (ensaiado).** `BackupService.urlDeDump()` constrói `postgresql://utilizador:SENHA@host/base` e passa-o como argumento. Verificado
com um `pg_dump` bloqueado por um *lock*:

```
/proc/<pid>/cmdline : pg_dump --no-owner --no-privileges --clean --if-exists postgresql://kixima:kixima@localhost:5432/kixima_test -f /dev/null
ps -eo args         : (a mesma linha, com a senha)
```

Os argumentos ficam visíveis a qualquer processo do contentor, ao `docker top` e aos agentes de monitorização do anfitrião, e vão
parar a mensagens de erro/registos que repitam o comando. Com variáveis de ambiente **só no processo filho**, a linha de comandos fica
limpa e o dump funciona igual (ensaiado: `cmdline` = `pg_dump --no-owner --no-privileges --clean --if-exists -f /dev/null`; 51 tabelas).

**Desenho proposto.**
- Decompor o URL (`DIRECT_URL`/JDBC) em `host`, `porta`, `base`, `utilizador`, `senha` e parâmetros (`sslmode`, …) e lançar o filho com
  `pb.environment().put("PGHOST"…"PGPORT"…"PGDATABASE"…"PGUSER"…"PGPASSWORD"…"PGSSLMODE"…)` e **sem URL** nos argumentos.
- Alternativa equivalente: `PGPASSFILE` apontando a um ficheiro 0600 criado a partir do segredo (`/run/secrets/DATABASE_PASSWORD`) e removido no fim.
- Garantir que **nenhum** texto do `stderr` ou da excepção devolvida ao administrador/ao Sentry contém a senha (filtrar o valor).
- O `directUrl()` já está documentado como «contém a senha»: nunca o registar nem devolver.

**Testes de aceitação.**
- Teste unitário: os argumentos do `ProcessBuilder` **não contêm** a senha nem `://…@`.
- Teste de integração (Linux): com o `pg_dump` bloqueado por um *lock*, `/proc/<pid>/cmdline` **não contém** a senha.
- Forçar um erro de autenticação e verificar que a mensagem devolvida e o registo **não contêm** a senha.

## 3. Validar a integridade dos backups

**Hoje.** Confirma-se que o ficheiro existe, descomprime, tem ≥ tantos `CREATE TABLE` como tabelas na base e tem blocos `COPY`.
Não há hash, nem se detecta um dump cortado a meio cujo gzip esteja bem fechado.

**Melhorias propostas.**
1. **SHA-256 e tamanho no momento da criação**, guardados no registo de auditoria (`COPIA_SEGURANCA_CONCLUIDA`, campo `detail`) e como
   metadados do objecto; activar também o *checksum* adicional do S3 (`ChecksumAlgorithm.SHA256` no `PutObject`), que o S3 valida no servidor.
   Na verificação, recalcular o SHA-256 em fluxo e **comparar**.
2. **Marcador de fim de dump.** Um dump completo termina com `-- PostgreSQL database dump complete` (ensaiado: presente no completo e ausente num truncado).
   Exigi-lo na verificação.
3. **Alertas.** A Prontidão já avisa quando a última cópia é antiga; acrescentar «a última verificação falhou».
4. **Imutabilidade no bucket** (versionamento + bloqueio de objectos/retenção, ou política de ciclo de vida com um mínimo de N cópias), contra apagamentos acidentais ou maliciosos.
5. **Cópia fora do ecossistema**: pelo menos uma cópia mensal noutro fornecedor/região (o backup do §1 de `DEPLOY_BACKUP_ROLLBACK.md` já o prevê).

**Nota sobre o restauro dos dumps actuais.** Com o cliente 17.11, o `pg_dump` escreve `\restrict`/`\unrestrict` (comandos do `psql`; vistos
nos dumps ensaiados). Restaurar com `psql` exige a versão 17.6 ou posterior; um `psql` mais antigo falha nessa linha. Em Swarm use sempre o `psql` do contentor da base (17.11).

**Testes de aceitação.** Cópia truncada (gzip fechado, sem marcador final) → verificação falha; ficheiro adulterado (1 byte) → hash não confere;
ficheiro apagado do bucket → verificação falha com mensagem clara.

## 4. Garantir testes reais de restauro

**Problema.** Um backup só existe quando se restaurou com sucesso. Hoje a plataforma não restaura nada: o texto da verificação remete para
`npm run backup:restore-test`, que é do projecto Node e **não existe neste repositório**.

**Proposta.** Um **ensaio de restauro** regular, **fora da base de produção**, numa base descartável chamada `kixima_restore_*`:

1. Verificar a integridade do ficheiro (hash, gzip, marcador final / `pg_restore --list`).
2. Restaurar numa base nova (`pg_restore --exit-on-error` ou `psql -v ON_ERROR_STOP=1`).
3. Comparar com a origem: número de tabelas, contagem de linhas por tabela e histórico do Flyway.
4. *(Recomendado)* arrancar o backend contra a base restaurada: com `ddl-auto: validate` o Hibernate confirma que o schema é utilizável,
   e `/ready` confirma o acesso por tabela.
5. Registar resultado e duração (isto mede o **RTO** real) e alertar se falhar.

Exemplo **já testado** em PostgreSQL 17.11, nos dois formatos: [`exemplos/restore-drill.sh`](exemplos/restore-drill.sh).

```bash
# Backup do §1 de DEPLOY_BACKUP_ROLLBACK.md (formato custom), com o backend parado: contagens têm de ser IGUAIS
DB=$(docker ps -q -f name=kixima_db_kixima | head -n1)
STRICT=1 PSQL="docker exec -i $DB psql -U kixima" PGRESTORE="docker exec -i $DB pg_restore -U kixima" \
  docs/exemplos/restore-drill.sh /opt/kixima/backups/kixima-AAAAMMDD-HHMMSS.dump kixima
# Backup da própria aplicação (.sql.gz): sem STRICT (a origem pode ter crescido desde a cópia)
PSQL="docker exec -i $DB psql -U kixima" docs/exemplos/restore-drill.sh kixima-AAAAMMDD-HHMMSS.sql.gz kixima
```

**O que o ensaio rejeita (testado).** `.sql.gz` truncado; `.dump` com 1 byte alterado e o `.sha256` original; nome de base fora de `kixima_restore_*`
(nunca toca em `kixima`); restaurada com **mais** linhas do que a origem; com `STRICT=1`, qualquer diferença de contagem.

**Onde correr.** Na infra (um *cron* no nó ou um serviço Swarm com a imagem `postgres:17.11`), **nunca no CI público** (o repositório é
público e o backup tem dados reais) e nunca contra a base de produção. Frequência sugerida: mensal, e **depois de cada migração** (V4, V5…).

**Testes de aceitação.** O ensaio passa com um backup bom e falha, com mensagem clara, nos quatro casos acima; um restauro real do
backup mais recente dura menos do que o RTO acordado.

---

## Ordem de implementação sugerida

1. **Senha fora da linha de comandos** (§2): alteração pequena, ganho imediato de segurança, fácil de testar.
2. **Ensaio de restauro** (§4) com o script do exemplo, executado à mão e depois agendado: prova que os backups servem.
3. **Hash, marcador de fim e verificação em fluxo** (§3 e §1.2).
4. **Criação em fluxo** (§1.1), com o teste de memória.

## O que NÃO se deve mudar já

- O **formato** (SQL em texto, `--clean --if-exists`): só depois de existir o ensaio de restauro do §4 (porque trocar de formato altera a
  verificação, o restauro e a retenção). O formato *custom* (`-Fc`) é melhor, mas só entra com os testes.
- O mecanismo de **agendamento e retenção** (`BACKUP_CRON`, `STORAGE_BACKUP_BUCKET` privado obrigatório): funciona e tem testes.
