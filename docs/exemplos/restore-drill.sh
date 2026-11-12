#!/bin/bash
# Ensaio de restauro do KIXIMA: restaura um backup numa base DESCARTÁVEL e compara-a com a origem.
#
# Uso:    restore-drill.sh <backup.dump | backup.sql.gz> <base_de_origem> [base_de_ensaio]
# Saída:  0 = PASS, 1 = FAIL (o motivo vem escrito), 2 = pedido recusado.
#
# Segurança: a base de ensaio TEM de se chamar kixima_restore_*  (nunca a de produção) e é apagada no fim (KEEP=1 mantém-na).
# Ligação: por omissão psql/pg_restore directos (PGHOST, PGUSER, PGPASSWORD... do ambiente). No Swarm, redirecciona-se para o
#   contentor da base, sem senha na linha de comandos (socket local):
#       DB=$(docker ps -q -f name=kixima_db_kixima | head -n1)
#       PSQL="docker exec -i $DB psql -U kixima" PGRESTORE="docker exec -i $DB pg_restore -U kixima" ./restore-drill.sh ...
# STRICT=1: exige contagens IGUAIS (use com o backend parado, p. ex. no backup pré-deploy). Sem STRICT, a base de ensaio pode ter
#   MENOS linhas do que a origem actual (a origem pode ter crescido desde o backup), nunca mais.
set -uo pipefail

FICHEIRO=${1:-}; ORIGEM=${2:-}; ENSAIO=${3:-kixima_restore_drill}
PSQL=${PSQL:-psql}; PGRESTORE=${PGRESTORE:-pg_restore}
falha() { echo "FAIL: $*"; limpar; exit 1; }
recusa() { echo "RECUSADO: $*" >&2; exit 2; }
limpar() { [ "${KEEP:-0}" = 1 ] || $PSQL -d postgres -qAt -c "DROP DATABASE IF EXISTS $ENSAIO" >/dev/null 2>&1; }

[ -f "$FICHEIRO" ] && [ -n "$ORIGEM" ] || recusa "uso: $0 <backup> <base_de_origem> [base_de_ensaio]"
case "$ENSAIO" in kixima_restore_*) ;; *) recusa "a base de ensaio tem de se chamar kixima_restore_* (recebi '$ENSAIO')";; esac
[ "$ENSAIO" != "$ORIGEM" ] || recusa "a base de ensaio não pode ser a de origem"
case "$FICHEIRO" in *.dump) FORMATO=custom;; *.sql.gz) FORMATO=sql;; *) recusa "formato desconhecido (use .dump ou .sql.gz)";; esac

T0=$(date +%s)
echo "== 1) integridade do ficheiro ($FORMATO, $(stat -c %s "$FICHEIRO") B)"
if [ -f "$FICHEIRO.sha256" ]; then
  (cd "$(dirname "$FICHEIRO")" && sha256sum -c "$(basename "$FICHEIRO").sha256" >/dev/null 2>&1) || falha "o SHA-256 não confere (ficheiro alterado ou corrompido)"
  echo "   sha256 confere"
else echo "   (sem .sha256 ao lado: não verificado)"; fi
if [ "$FORMATO" = sql ]; then
  gunzip -t "$FICHEIRO" 2>/dev/null || falha "o gzip está corrompido/truncado"
  gunzip -c "$FICHEIRO" | grep -q 'PostgreSQL database dump complete' || falha "o dump não termina em 'PostgreSQL database dump complete' (truncado)"
else
  $PGRESTORE --list < "$FICHEIRO" >/dev/null 2>&1 || falha "pg_restore --list não consegue ler o índice do dump"
fi
echo "   íntegro"

echo "== 2) restauro para a base descartável $ENSAIO"
limpar
$PSQL -d postgres -qAt -c "CREATE DATABASE $ENSAIO" >/dev/null 2>&1 || falha "não foi possível criar $ENSAIO (falta o privilégio CREATEDB?)"
T1=$(date +%s)
if [ "$FORMATO" = custom ]; then
  $PGRESTORE -d "$ENSAIO" --no-owner --no-privileges --exit-on-error < "$FICHEIRO" >/dev/null 2>"/tmp/restore-drill.$$.err" || { head -c 300 "/tmp/restore-drill.$$.err"; rm -f "/tmp/restore-drill.$$.err"; falha "pg_restore terminou com erro"; }
else
  gunzip -c "$FICHEIRO" | $PSQL -d "$ENSAIO" -v ON_ERROR_STOP=1 -q -o /dev/null 2>"/tmp/restore-drill.$$.err" || { head -c 300 "/tmp/restore-drill.$$.err"; rm -f "/tmp/restore-drill.$$.err"; falha "psql terminou com erro"; }
fi
rm -f "/tmp/restore-drill.$$.err"
echo "   restaurado em $(( $(date +%s) - T1 )) s"

echo "== 3) comparação com a origem ($ORIGEM)"
TAB='select tablename from pg_tables where schemaname='"'public'"' order by 1'
N_O=$($PSQL -d "$ORIGEM" -qAt -c "$TAB" | wc -l); N_E=$($PSQL -d "$ENSAIO" -qAt -c "$TAB" | wc -l)
[ "$N_O" = "$N_E" ] || falha "tabelas: origem=$N_O, restaurada=$N_E"
echo "   tabelas: $N_E = $N_O"
PROBLEMAS=0
for t in $($PSQL -d "$ORIGEM" -qAt -c "$TAB"); do
  A=$($PSQL -d "$ORIGEM" -qAt -c "select count(*) from public.\"$t\""); B=$($PSQL -d "$ENSAIO" -qAt -c "select count(*) from public.\"$t\"")
  if [ "${STRICT:-0}" = 1 ]; then [ "$A" = "$B" ] || { echo "   linhas diferentes em $t: origem=$A restaurada=$B"; PROBLEMAS=$((PROBLEMAS+1)); }
  else [ "$B" -le "$A" ] || { echo "   a restaurada tem MAIS linhas em $t: origem=$A restaurada=$B"; PROBLEMAS=$((PROBLEMAS+1)); }; fi
done
[ "$PROBLEMAS" = 0 ] || falha "$PROBLEMAS tabela(s) com contagens inconsistentes"
echo "   contagens por tabela coerentes ($( [ "${STRICT:-0}" = 1 ] && echo iguais || echo 'restaurada <= origem'))"
FO=$($PSQL -d "$ORIGEM" -qAt -c "select string_agg(version,',' order by installed_rank) from flyway_schema_history" 2>/dev/null)
FE=$($PSQL -d "$ENSAIO" -qAt -c "select string_agg(version,',' order by installed_rank) from flyway_schema_history" 2>/dev/null)
[ "$FE" = "$FO" ] || echo "   aviso: histórico Flyway difere (origem=$FO restaurada=$FE) — esperado se a origem migrou depois do backup"
echo "   histórico Flyway restaurado: ${FE:-<vazio>}"

limpar
echo "PASS ($(( $(date +%s) - T0 )) s no total)"
