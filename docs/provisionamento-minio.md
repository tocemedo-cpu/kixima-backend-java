# Provisionar o MinIO do `kixima-backend-java`

Procedimento para correr um MinIO auto-hospedado como armazenamento de
ficheiros em produção — sem contratar nenhum serviço de nuvem. `StorageService.java`
já fala o protocolo S3 nativamente; MinIO implementa esse mesmo protocolo, por
isso não é preciso alterar nenhum código, só apontar `STORAGE_ENDPOINT` para o
contentor.

**Isto é um guia — ninguém correu nada disto ainda.** Os nomes abaixo
(`kixima-ficheiros`, as credenciais) são sugestões; ajusta-os ao que
preferires, desde que uses os mesmos valores depois nas variáveis de
ambiente.

---

## 0. Antes de começar

- Precisas de um host capaz de correr contentores Docker (o mesmo onde corre
  o backend e o Postgres, ou outro — basta ser alcançável pela rede a partir
  do backend).
- MinIO precisa de **volume persistente próprio**, exactamente como o
  Postgres precisa do seu — perder esse volume é perder todos os ficheiros
  já enviados (documentos de credenciamento, comprovativos de pagamento,
  imagens de produto, ...).

---

## 1. Correr o contentor MinIO

```bash
docker volume create kixima_minio_data

docker run -d --name kixima-minio \
  --network kixima-net \
  -v kixima_minio_data:/data \
  -e MINIO_ROOT_USER=ESCOLHE_UM_UTILIZADOR_AQUI \
  -e MINIO_ROOT_PASSWORD=ESCOLHE_UMA_PASSWORD_FORTE_AQUI \
  -p 9000:9000 -p 9001:9001 \
  minio/minio server /data --console-address ":9001"
```

- `9000` é a porta da API S3 (é a que `STORAGE_ENDPOINT` vai usar).
- `9001` é só a consola web de administração — opcional, útil para
  confirmar visualmente que o bucket e os ficheiros estão lá.
- `--network kixima-net` liga o MinIO à mesma rede Docker do backend — ajusta
  ao nome real da tua rede/orquestrador.

---

## 2. Criar o bucket

### Via `mc` (cliente de linha de comandos do MinIO)

```bash
docker run --rm --network kixima-net \
  --entrypoint sh minio/mc -c "
    mc alias set kixima http://kixima-minio:9000 ESCOLHE_UM_UTILIZADOR_AQUI ESCOLHE_UMA_PASSWORD_FORTE_AQUI &&
    mc mb kixima/kixima-ficheiros
  "
```

### Via consola web

1. Abrir `http://<host>:9001`, entrar com `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`.
2. **Buckets** → **Create Bucket** → nome `kixima-ficheiros` → **Create**.

---

## 3. Variáveis de ambiente para o `kixima-backend-java`

Estas sete variáveis substituem as de armazenamento local (que
`ProducaoStartupGuard` recusa em produção — ver README):

| Variável | Valor |
|---|---|
| `STORAGE_PROVIDER` | `s3` (MinIO fala o mesmo protocolo — o código não distingue) |
| `STORAGE_BUCKET` | `kixima-ficheiros` |
| `STORAGE_ACCESS_KEY` | o `MINIO_ROOT_USER` escolhido no passo 1 |
| `STORAGE_SECRET_KEY` | o `MINIO_ROOT_PASSWORD` escolhido no passo 1 |
| `STORAGE_ENDPOINT` | `http://kixima-minio:9000` (nome do contentor na mesma rede) ou `http://<host>:9000` se o backend correr fora dessa rede |
| `STORAGE_REGION` | `us-east-1` (o MinIO ignora o valor, mas o SDK exige um) |
| `STORAGE_FORCE_PATH_STYLE` | `true` (já é o valor por omissão — o MinIO exige endereçamento por caminho, não por subdomínio) |

Exemplo completo:
```
STORAGE_PROVIDER=s3
STORAGE_BUCKET=kixima-ficheiros
STORAGE_ACCESS_KEY=ESCOLHE_UM_UTILIZADOR_AQUI
STORAGE_SECRET_KEY=ESCOLHE_UMA_PASSWORD_FORTE_AQUI
STORAGE_ENDPOINT=http://kixima-minio:9000
STORAGE_REGION=us-east-1
STORAGE_FORCE_PATH_STYLE=true
```

Se quiseres os ficheiros acessíveis directamente por URL pública (sem passar
pelo backend), também defines `STORAGE_PUBLIC_URL` a apontar para onde o
MinIO estiver exposto publicamente (ex.: atrás de um proxy reverso com TLS);
sem isso, `StorageService.publicUrlFor()` usa o próprio `STORAGE_ENDPOINT`
como base do URL.

---

## 4. Confirmar que correu bem

1. **No arranque da aplicação com `SPRING_PROFILES_ACTIVE=prod`**: sem
   `ERROR`/`IllegalStateException` do `ProducaoStartupGuard` sobre
   armazenamento — se as três credenciais (`STORAGE_BUCKET`/`STORAGE_ACCESS_KEY`/
   `STORAGE_SECRET_KEY`) estiverem vazias ou `STORAGE_PROVIDER` não for `s3`,
   o arranque falha explicitamente, com o nome da variável em falta.
2. **Upload de teste**: qualquer endpoint que aceite ficheiro (ex.: um
   documento de empresa) deve devolver um URL que começa por
   `STORAGE_ENDPOINT`/`STORAGE_PUBLIC_URL`, não por `/api/uploads/...` (esse
   prefixo é só do modo `local`).
3. **Na consola do MinIO** (`:9001`) ou via `mc ls kixima/kixima-ficheiros`:
   o ficheiro enviado no passo anterior deve aparecer lá.

---

## Resumo rápido (checklist)

- [ ] Volume Docker persistente criado para o MinIO
- [ ] Contentor MinIO a correr, com `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` definidos
- [ ] Bucket `kixima-ficheiros` criado
- [ ] `STORAGE_PROVIDER=s3` + `STORAGE_BUCKET`/`STORAGE_ACCESS_KEY`/`STORAGE_SECRET_KEY`/`STORAGE_ENDPOINT` definidas
- [ ] `STORAGE_REGION`/`STORAGE_FORCE_PATH_STYLE` definidas (ou confiar nos valores por omissão)
- [ ] Primeiro arranque da aplicação em `prod` → sem erro do `ProducaoStartupGuard` sobre armazenamento
- [ ] Upload de teste confirmado na consola do MinIO
