-- ============================================================================
-- V1__baseline.sql — KIXIMA, schema completo (baseline Flyway)
--
-- ORIGEM: pg_dump --schema-only --no-owner --no-privileges da base de testes
-- local (kixima_test), gerada pelas 53 migrações Prisma do backend Node em
-- 2026-09-29. É uma cópia FIEL da estrutura tal como existe hoje — nada foi
-- reescrito à mão, para não haver desvio entre o que este ficheiro diz e o
-- que a base realmente tem.
--
-- EXCLUÍDO DE PROPÓSITO: a tabela "_prisma_migrations" (bookkeeping do
-- Prisma — o Java nunca lhe toca e não faz sentido num schema gerido por
-- Flyway).
--
-- CONTEÚDO: 1 extensão (pg_trgm), 35 tipos ENUM nativos, 49 tabelas (47 com
-- entidade JPA + reference_counters + series_faturacao, ambas acedidas por
-- SQL nativo), 3 funções e 2 triggers (kixima_normalizar/products_search_text/
-- companies_search_text — mantêm a coluna search_text de products/companies
-- sempre atualizada; SEM ISTO a pesquisa fica muda em silêncio para qualquer
-- registo novo, porque nenhum código Java escreve nessa coluna), 163 índices
-- (49 chaves primárias, 31 índices únicos, os restantes de desempenho,
-- incluindo os 2 GIN de trigramas), 64 chaves estrangeiras.
--
-- VALIDADO (2026-09-29) contra uma base local DESCARTÁVEL, criada e apagada
-- só para este fim (nunca tocou em "kixima_test" nem em nenhuma base real):
-- aplicação limpa (0 erros), diff de schema estruturalmente idêntico ao
-- original, e a suite JUnit a arrancar com sucesso contra ela — Hibernate
-- em "ddl-auto: validate" confirmou as 47 entidades sem nenhuma diferença.
-- Nunca correu contra a base do Node nem contra nenhuma base de produção.
-- Destina-se a uma base de dados NOVA, vazia, criada especificamente para o
-- kixima-backend-java.
--
-- Sequências: nenhuma — todos os IDs são "text" (UUID gerado em aplicação,
-- nunca por omissão da base).
-- ============================================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 16.13 (Ubuntu 16.13-0ubuntu0.24.04.1)
-- Dumped by pg_dump version 16.13 (Ubuntu 16.13-0ubuntu0.24.04.1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--

-- *not* creating schema, since initdb creates it


--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS '';


--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: BillingPeriodicity; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."BillingPeriodicity" AS ENUM (
    'TRIMESTRAL',
    'SEMESTRAL'
);


--
-- Name: CanalCobranca; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CanalCobranca" AS ENUM (
    'TRANSFERENCIA_MANUAL',
    'EMIS_MULTICAIXA',
    'PAYPAY',
    'BAI',
    'BFA',
    'STANDARD_BANK_ANGOLA'
);


--
-- Name: CanalPagamento; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CanalPagamento" AS ENUM (
    'TRANSFERENCIA_MANUAL',
    'REFERENCIA_BANCARIA',
    'MULTICAIXA_EXPRESS',
    'ERP'
);


--
-- Name: CobrancaStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CobrancaStatus" AS ENUM (
    'PENDENTE',
    'COMPROVATIVO_ENVIADO',
    'CONFIRMADA',
    'CANCELADA'
);


--
-- Name: CompanyAddonStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CompanyAddonStatus" AS ENUM (
    'INATIVO',
    'ATIVO'
);


--
-- Name: CompanyErpSystem; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CompanyErpSystem" AS ENUM (
    'MANUAL',
    'PRIMAVERA',
    'SAP_S4HANA',
    'ORACLE_ERP_CLOUD',
    'SAP_ARIBA'
);


--
-- Name: CompanyPlan; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CompanyPlan" AS ENUM (
    'BASICO',
    'PRO',
    'BASE',
    'CORE'
);


--
-- Name: CompanySize; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CompanySize" AS ENUM (
    'MICRO',
    'PEQUENA',
    'MEDIA',
    'GRANDE'
);


--
-- Name: CompanyStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CompanyStatus" AS ENUM (
    'PENDENTE',
    'APROVADA',
    'REJEITADA',
    'SUSPENSA'
);


--
-- Name: CompanyType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."CompanyType" AS ENUM (
    'CLIENTE',
    'FORNECEDOR'
);


--
-- Name: ContractStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ContractStatus" AS ENUM (
    'ATIVO',
    'EXPIRADO',
    'ENCERRADO'
);


--
-- Name: ConversationStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ConversationStatus" AS ENUM (
    'ABERTA',
    'FECHADA'
);


--
-- Name: DocumentType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."DocumentType" AS ENUM (
    'CERTIDAO_COMERCIAL',
    'ALVARA_COMERCIAL',
    'LICENCA_ANPG'
);


--
-- Name: ErpSyncDirection; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ErpSyncDirection" AS ENUM (
    'OUTBOUND',
    'INBOUND'
);


--
-- Name: ErpSyncStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ErpSyncStatus" AS ENUM (
    'PENDING',
    'SUCCESS',
    'FAILED'
);


--
-- Name: FeedbackCategoria; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."FeedbackCategoria" AS ENUM (
    'FORNECEDOR',
    'PRODUTO',
    'SERVICO',
    'PEDIDO',
    'ENTREGA',
    'PAGAMENTO',
    'ATENDIMENTO',
    'EXPERIENCIA_GERAL'
);


--
-- Name: InviteStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."InviteStatus" AS ENUM (
    'PENDENTE',
    'ACEITO',
    'EXPIRADO',
    'CANCELADO'
);


--
-- Name: InvoiceStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."InvoiceStatus" AS ENUM (
    'PENDENTE',
    'PAGA',
    'VENCIDA',
    'CANCELADA'
);


--
-- Name: NotificationChannel; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."NotificationChannel" AS ENUM (
    'IN_APP',
    'EMAIL',
    'IN_APP_EMAIL'
);


--
-- Name: NotificationType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."NotificationType" AS ENUM (
    'PO_AGUARDA_APROVACAO',
    'PO_APROVADA',
    'PO_REJEITADA',
    'PO_RECEBIDA_FORNECEDOR',
    'FATURA_GERADA',
    'PAGAMENTO_PROCESSADO',
    'ENTREGA_DESPACHADA',
    'RECECAO_COM_DIVERGENCIA',
    'APOLICE_SUBMETIDA_APROVADA',
    'APOLICE_A_EXPIRAR',
    'CADASTRO_EMPRESA_APROVADO',
    'CADASTRO_EMPRESA_REJEITADO',
    'DIVERGENCIA_RESOLVIDA',
    'SUPPLIER_DEV_RECEBIDA',
    'SUBSCRICAO_COMPROVATIVO',
    'SUBSCRICAO_CONFIRMADA',
    'SUPORTE_MENSAGEM',
    'CHAT_COMERCIAL_MENSAGEM',
    'ALERTA_SEGURANCA',
    'PO_RECUSADA_FORNECEDOR',
    'PO_ENTREGUE',
    'PO_RECEBIDA_CONFORME',
    'PO_CONCLUIDA',
    'SUBSCRICAO_A_EXPIRAR',
    'NOTA_CREDITO_EMITIDA',
    'ESTOQUE_BAIXO'
);


--
-- Name: PaymentStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PaymentStatus" AS ENUM (
    'PROCESSADO',
    'FALHOU'
);


--
-- Name: PersonaRole; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PersonaRole" AS ENUM (
    'COMPRADOR',
    'COMPANY_ADMIN',
    'FORNECEDOR',
    'FINANCEIRO',
    'ADMIN_SISTEMA'
);


--
-- Name: PlatformFeeStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PlatformFeeStatus" AS ENUM (
    'PENDENTE',
    'COBRADO'
);


--
-- Name: PoRoboMediaOrigem; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PoRoboMediaOrigem" AS ENUM (
    'IA',
    'MANUAL'
);


--
-- Name: PoRoboPeriodicidade; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PoRoboPeriodicidade" AS ENUM (
    'SEMANAL',
    'QUINZENAL',
    'MENSAL'
);


--
-- Name: PoStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PoStatus" AS ENUM (
    'AGUARDANDO_APROVACAO',
    'APROVADA',
    'REJEITADA',
    'ACEITE_FORNECEDOR',
    'RECUSADA_FORNECEDOR',
    'AGUARDANDO_PAGAMENTO',
    'PAGA',
    'EM_EXECUCAO',
    'ENTREGUE',
    'RECEBIDA_CONFORME',
    'RECEBIDA_COM_DIVERGENCIA',
    'CONCLUIDA'
);


--
-- Name: PolicyStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."PolicyStatus" AS ENUM (
    'SUBMETIDA',
    'APROVADA',
    'REJEITADA',
    'EXPIRADA'
);


--
-- Name: ProductDocType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ProductDocType" AS ENUM (
    'FICHA_TECNICA',
    'DATASHEET',
    'MANUAL',
    'CATALOGO',
    'CERTIFICADO',
    'DESENHO_TECNICO'
);


--
-- Name: ProductKind; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."ProductKind" AS ENUM (
    'PRODUTO',
    'SERVICO'
);


--
-- Name: QuoteStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."QuoteStatus" AS ENUM (
    'ABERTA',
    'RESPONDIDA',
    'FECHADA'
);


--
-- Name: RiskAlertStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."RiskAlertStatus" AS ENUM (
    'ABERTO',
    'EM_ANALISE',
    'FALSO_POSITIVO',
    'RESOLVIDO'
);


--
-- Name: RiskLevel; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."RiskLevel" AS ENUM (
    'LOW',
    'MEDIUM',
    'HIGH',
    'CRITICAL'
);


--
-- Name: StockMovementType; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."StockMovementType" AS ENUM (
    'ENTRADA',
    'SAIDA'
);


--
-- Name: SupplierDevStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."SupplierDevStatus" AS ENUM (
    'RECEBIDA',
    'EM_ANALISE',
    'EM_ACOMPANHAMENTO',
    'CONCLUIDA',
    'REJEITADA'
);


--
-- Name: SupplierDevTrack; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."SupplierDevTrack" AS ENUM (
    'BUROCRACIA',
    'PARCERIA',
    'AMBOS'
);


--
-- Name: SupportStatus; Type: TYPE; Schema: public; Owner: -
--

CREATE TYPE public."SupportStatus" AS ENUM (
    'ABERTO',
    'EM_ANDAMENTO',
    'AGUARDANDO_RESPOSTA',
    'RESOLVIDO',
    'FECHADO'
);


--
-- Name: companies_search_text(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.companies_search_text() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW."search_text" := kixima_normalizar(
    concat_ws(' ', NEW."name", NEW."city", NEW."country")
  );
  RETURN NEW;
END;
$$;


--
-- Name: kixima_normalizar(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.kixima_normalizar(txt text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$
  SELECT translate(
    lower(coalesce(txt, '')),
    'áàâãäéèêëíìîïóòôõöúùûüçñÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑ',
    'aaaaaeeeeiiiiooooouuuucnaaaaaeeeeiiiiooooouuuucn'
  );
$$;


--
-- Name: products_search_text(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.products_search_text() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  NEW."search_text" := kixima_normalizar(
    concat_ws(' ',
      NEW."name", NEW."description", NEW."category", NEW."specialty",
      NEW."brand", NEW."model", NEW."unspsc_title", NEW."city", NEW."keywords"
    )
  );
  RETURN NEW;
END;
$$;


SET default_table_access_method = heap;

--
-- Name: addon_cobrancas; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.addon_cobrancas (
    id text NOT NULL,
    referencia text NOT NULL,
    company_id text NOT NULL,
    addon_key text NOT NULL,
    valor_usd numeric(12,2) NOT NULL,
    periodo text NOT NULL,
    meses integer NOT NULL,
    status public."CobrancaStatus" DEFAULT 'PENDENTE'::public."CobrancaStatus" NOT NULL,
    comprovativo_url text,
    submetido_em timestamp(3) without time zone,
    confirmada_por text,
    confirmada_em timestamp(3) without time zone,
    valido_ate timestamp(3) without time zone,
    notas text,
    canal public."CanalCobranca" DEFAULT 'TRANSFERENCIA_MANUAL'::public."CanalCobranca" NOT NULL,
    referencia_externa text,
    telemovel text,
    created_by_id text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: agtseriesfe; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agtseriesfe (
    id text NOT NULL,
    ano integer NOT NULL,
    tipo_documento text NOT NULL,
    establishment_number text NOT NULL,
    tax_registration_number text NOT NULL,
    series_code text,
    submission_uuid text NOT NULL,
    request_id text,
    result_code text NOT NULL,
    solicitado_por_id text,
    solicitado_por_nome text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    authorized_quantity text,
    first_document_no text,
    last_document_no text,
    ultimo_numero integer DEFAULT 0 NOT NULL
);


--
-- Name: api_keys; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_keys (
    id text NOT NULL,
    company_id text NOT NULL,
    nome text NOT NULL,
    prefixo text NOT NULL,
    hash text NOT NULL,
    criada_por text,
    ultimo_uso timestamp(3) without time zone,
    revogada_em timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id text NOT NULL,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id text,
    entity_ref text,
    actor_id text,
    actor_name text,
    actor_role text,
    company_id text,
    ip text,
    detail jsonb,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: budget_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.budget_limits (
    id text NOT NULL,
    company_id text NOT NULL,
    period_monthly numeric(14,2) NOT NULL,
    currency text DEFAULT 'AOA'::text NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: companies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.companies (
    id text NOT NULL,
    name text NOT NULL,
    tax_id text NOT NULL,
    type public."CompanyType" NOT NULL,
    status public."CompanyStatus" DEFAULT 'PENDENTE'::public."CompanyStatus" NOT NULL,
    contact_email text NOT NULL,
    contact_phone text,
    address text,
    verified boolean DEFAULT false NOT NULL,
    logo_url text,
    city text,
    province text,
    country text DEFAULT 'Angola'::text,
    settings jsonb,
    bank_name text,
    iban text,
    swift text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    approved_at timestamp(3) without time zone,
    rejected_at timestamp(3) without time zone,
    terms_accepted_at timestamp(3) without time zone,
    employees integer,
    annual_revenue_usd numeric(16,2),
    size public."CompanySize" DEFAULT 'PEQUENA'::public."CompanySize" NOT NULL,
    plan public."CompanyPlan" DEFAULT 'BASICO'::public."CompanyPlan" NOT NULL,
    seat_price_usd numeric(10,2) DEFAULT 100 NOT NULL,
    plan_notes text,
    search_rank integer DEFAULT 0 NOT NULL,
    plano_valido_ate timestamp(3) without time zone,
    search_text text,
    ultimo_aviso_subscricao_tier text,
    serie_fiscal text,
    data_adesao_facturacao_electronica timestamp(3) without time zone
);


--
-- Name: company_addons; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_addons (
    id text NOT NULL,
    company_id text NOT NULL,
    addon_key text NOT NULL,
    status public."CompanyAddonStatus" DEFAULT 'INATIVO'::public."CompanyAddonStatus" NOT NULL,
    activated_at timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    valido_ate timestamp(3) without time zone
);


--
-- Name: company_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_documents (
    id text NOT NULL,
    company_id text NOT NULL,
    type public."DocumentType" NOT NULL,
    file_url text NOT NULL,
    original_name text NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: company_erp_config_audits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_erp_config_audits (
    id text NOT NULL,
    company_id text NOT NULL,
    action text NOT NULL,
    from_erp public."CompanyErpSystem",
    to_erp public."CompanyErpSystem",
    actor_user_id text,
    actor_name text,
    result text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: company_erp_configs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.company_erp_configs (
    id text NOT NULL,
    company_id text NOT NULL,
    erp public."CompanyErpSystem" DEFAULT 'MANUAL'::public."CompanyErpSystem" NOT NULL,
    config_enc text,
    last_test_at timestamp(3) without time zone,
    last_test_ok boolean,
    last_test_message text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: contracts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contracts (
    id text NOT NULL,
    reference text NOT NULL,
    client_company_id text NOT NULL,
    supplier_company_id text NOT NULL,
    categories_covered text[],
    total_value numeric(14,2) NOT NULL,
    currency text DEFAULT 'AOA'::text NOT NULL,
    used_value numeric(14,2) DEFAULT 0 NOT NULL,
    billing_periodicity public."BillingPeriodicity" NOT NULL,
    payment_term_days integer NOT NULL,
    status public."ContractStatus" DEFAULT 'ATIVO'::public."ContractStatus" NOT NULL,
    valid_from timestamp(3) without time zone NOT NULL,
    valid_until timestamp(3) without time zone NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: conversation_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversation_messages (
    id text NOT NULL,
    conversation_id text NOT NULL,
    sender_id text NOT NULL,
    sender_company_id text NOT NULL,
    body text NOT NULL,
    attachment_url text,
    attachment_name text,
    read_at timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversations (
    id text NOT NULL,
    buyer_company_id text NOT NULL,
    supplier_company_id text NOT NULL,
    context_type text,
    context_id text,
    status public."ConversationStatus" DEFAULT 'ABERTA'::public."ConversationStatus" NOT NULL,
    created_by_id text NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: credit_notes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.credit_notes (
    id text NOT NULL,
    reference text NOT NULL,
    invoice_id text NOT NULL,
    motivo text NOT NULL,
    amount numeric(14,2) NOT NULL,
    net_amount numeric(14,2),
    tax_amount numeric(14,2),
    currency text DEFAULT 'AOA'::text NOT NULL,
    serie text,
    numero_na_serie integer,
    hash_documento text,
    hash_anterior text,
    assinada_em timestamp(3) without time zone,
    issued_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by_id text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    agt_document_no text,
    agt_request_id text,
    agt_result_code text,
    agt_erro jsonb,
    agt_estado jsonb
);


--
-- Name: discount_thresholds; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.discount_thresholds (
    id text NOT NULL,
    min_volume_usd numeric(16,2) NOT NULL,
    discount_percent numeric(5,2) NOT NULL,
    ativo boolean DEFAULT true NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: employee_invites; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.employee_invites (
    id text NOT NULL,
    company_id text,
    name text NOT NULL,
    email text NOT NULL,
    role public."PersonaRole" NOT NULL,
    token text NOT NULL,
    status public."InviteStatus" DEFAULT 'PENDENTE'::public."InviteStatus" NOT NULL,
    expires_at timestamp(3) without time zone NOT NULL,
    accepted_at timestamp(3) without time zone,
    created_by_id text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    admin_areas text[] DEFAULT '{}'::text[] NOT NULL
);


--
-- Name: erp_sync_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.erp_sync_logs (
    id text NOT NULL,
    purchase_order_id text NOT NULL,
    direction public."ErpSyncDirection" NOT NULL,
    event_type text NOT NULL,
    status public."ErpSyncStatus" NOT NULL,
    external_id text,
    error_message text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: favorites; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.favorites (
    id text NOT NULL,
    user_id text NOT NULL,
    product_id text NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.feedback (
    id text NOT NULL,
    rating integer NOT NULL,
    message text NOT NULL,
    approved boolean DEFAULT false NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    categoria public."FeedbackCategoria" NOT NULL,
    company_id text NOT NULL,
    target_id text,
    target_label text,
    user_id text NOT NULL,
    verified boolean DEFAULT true NOT NULL
);


--
-- Name: invoice_lines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoice_lines (
    id text NOT NULL,
    invoice_id text NOT NULL,
    line_number integer NOT NULL,
    product_code text NOT NULL,
    description text NOT NULL,
    quantity numeric(14,3) NOT NULL,
    unit_price numeric(14,2) NOT NULL,
    discount numeric(14,2) DEFAULT 0 NOT NULL,
    net_amount numeric(14,2) NOT NULL,
    iva_amount numeric(14,2) DEFAULT 0 NOT NULL,
    iec_amount numeric(14,2) DEFAULT 0 NOT NULL,
    is_amount numeric(14,2) DEFAULT 0 NOT NULL,
    iva_tax_code text DEFAULT 'NOR'::text NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: invoices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invoices (
    id text NOT NULL,
    reference text NOT NULL,
    purchase_order_id text,
    contract_id text,
    consolidated_po_ids text[] DEFAULT ARRAY[]::text[],
    amount numeric(14,2) NOT NULL,
    net_amount numeric(14,2),
    tax_amount numeric(14,2),
    withholding_amount numeric(14,2),
    currency text DEFAULT 'AOA'::text NOT NULL,
    status public."InvoiceStatus" DEFAULT 'PENDENTE'::public."InvoiceStatus" NOT NULL,
    issued_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    due_at timestamp(3) without time zone NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    serie text,
    numero_na_serie integer,
    hash_documento text,
    hash_anterior text,
    assinada_em timestamp(3) without time zone,
    referencia_pagamento text,
    agt_document_no text,
    agt_erro jsonb,
    agt_request_id text,
    agt_result_code text,
    agt_estado jsonb
);


--
-- Name: kit_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kit_items (
    id text NOT NULL,
    kit_id text NOT NULL,
    product_id text NOT NULL,
    quantity integer DEFAULT 1 NOT NULL
);


--
-- Name: kits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kits (
    id text NOT NULL,
    supplier_id text NOT NULL,
    name text NOT NULL,
    description text,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: kixima_to_client_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.kixima_to_client_policies (
    id text NOT NULL,
    company_id text NOT NULL,
    policy_number text NOT NULL,
    insurer text NOT NULL,
    coverage_amount numeric(14,2) NOT NULL,
    currency text DEFAULT 'AOA'::text NOT NULL,
    status public."PolicyStatus" DEFAULT 'APROVADA'::public."PolicyStatus" NOT NULL,
    issued_by_id text NOT NULL,
    valid_from timestamp(3) without time zone NOT NULL,
    valid_until timestamp(3) without time zone NOT NULL,
    expiry_alert_sent_at timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: linhas_extrato; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.linhas_extrato (
    id text NOT NULL,
    id_no_banco text NOT NULL,
    data_valor timestamp(3) without time zone NOT NULL,
    montante numeric(14,2) NOT NULL,
    moeda text DEFAULT 'AOA'::text NOT NULL,
    descricao text,
    referencia text,
    estado text DEFAULT 'POR_CONCILIAR'::text NOT NULL,
    invoice_id text,
    motivo text,
    importada_em timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    conciliada_em timestamp(3) without time zone
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id text NOT NULL,
    user_id text,
    company_id text,
    type public."NotificationType" NOT NULL,
    channel public."NotificationChannel" DEFAULT 'IN_APP'::public."NotificationChannel" NOT NULL,
    title text NOT NULL,
    message text NOT NULL,
    read_at timestamp(3) without time zone,
    related_entity_type text,
    related_entity_id text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payments (
    id text NOT NULL,
    invoice_id text NOT NULL,
    amount numeric(14,2) NOT NULL,
    currency text DEFAULT 'AOA'::text NOT NULL,
    status public."PaymentStatus" DEFAULT 'PROCESSADO'::public."PaymentStatus" NOT NULL,
    processed_by_id text,
    reference text NOT NULL,
    processed_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    proof_name text,
    proof_url text,
    received_at timestamp(3) without time zone,
    received_by_id text,
    canal public."CanalPagamento" DEFAULT 'TRANSFERENCIA_MANUAL'::public."CanalPagamento" NOT NULL,
    serie text,
    numero_na_serie integer,
    hash_documento text,
    hash_anterior text,
    assinada_em timestamp(3) without time zone,
    agt_document_no text
);


--
-- Name: plano_cobrancas; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plano_cobrancas (
    id text NOT NULL,
    referencia text NOT NULL,
    company_id text NOT NULL,
    plano_atual public."CompanyPlan" NOT NULL,
    plano_novo public."CompanyPlan" NOT NULL,
    valor_usd numeric(12,2) NOT NULL,
    periodo text NOT NULL,
    meses integer NOT NULL,
    status public."CobrancaStatus" DEFAULT 'PENDENTE'::public."CobrancaStatus" NOT NULL,
    comprovativo_url text,
    submetido_em timestamp(3) without time zone,
    confirmada_por text,
    confirmada_em timestamp(3) without time zone,
    valido_ate timestamp(3) without time zone,
    notas text,
    created_by_id text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    canal public."CanalCobranca" DEFAULT 'TRANSFERENCIA_MANUAL'::public."CanalCobranca" NOT NULL,
    referencia_externa text,
    telemovel text
);


--
-- Name: platform_fees; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.platform_fees (
    id text NOT NULL,
    company_id text NOT NULL,
    invoice_id text NOT NULL,
    po_count integer NOT NULL,
    per_po numeric(14,2) NOT NULL,
    per_invoice numeric(14,2) NOT NULL,
    amount numeric(14,2) NOT NULL,
    currency text DEFAULT 'USD'::text NOT NULL,
    status public."PlatformFeeStatus" DEFAULT 'PENDENTE'::public."PlatformFeeStatus" NOT NULL,
    charged_at timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    basis text,
    po_value_usd numeric(16,2),
    fx_rate numeric(14,4)
);


--
-- Name: po_robo_regras; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.po_robo_regras (
    id text NOT NULL,
    company_id text NOT NULL,
    product_id text NOT NULL,
    media_origem public."PoRoboMediaOrigem" DEFAULT 'IA'::public."PoRoboMediaOrigem" NOT NULL,
    media_mensal numeric(14,3) NOT NULL,
    periodicidade public."PoRoboPeriodicidade" DEFAULT 'MENSAL'::public."PoRoboPeriodicidade" NOT NULL,
    quantidade integer,
    ativo boolean DEFAULT true NOT NULL,
    limite_maximo_usd numeric(14,2),
    proxima_execucao_em timestamp(3) without time zone NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: product_documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_documents (
    id text NOT NULL,
    product_id text NOT NULL,
    type public."ProductDocType" NOT NULL,
    file_url text NOT NULL,
    original_name text NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: product_images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.product_images (
    id text NOT NULL,
    product_id text NOT NULL,
    url text NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: products; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.products (
    id text NOT NULL,
    supplier_id text NOT NULL,
    name text NOT NULL,
    sku text,
    manufacturer_code text,
    category text NOT NULL,
    subcategory text,
    brand text,
    manufacturer text,
    model text,
    country_of_origin text,
    description text,
    full_description text,
    applications text,
    benefits text,
    keywords text,
    unspsc_code text,
    unspsc_title text,
    unspsc_segment text,
    unspsc_family text,
    unspsc_class text,
    key_spec text,
    standard text,
    warranty text,
    supplier_notes text,
    material text,
    weight text,
    height text,
    width text,
    length text,
    pressure text,
    temperature text,
    power text,
    voltage text,
    measurement_unit text,
    unit_price numeric(14,2) NOT NULL,
    promo_price numeric(14,2),
    currency text DEFAULT 'AOA'::text NOT NULL,
    min_quantity integer,
    max_quantity integer,
    stock_quantity integer,
    warehouse text,
    lead_time_days integer,
    availability text,
    min_stock integer,
    slug text,
    kind public."ProductKind" DEFAULT 'PRODUTO'::public."ProductKind" NOT NULL,
    specialty text,
    city text,
    province text,
    country text DEFAULT 'Angola'::text,
    certifications text[] DEFAULT ARRAY[]::text[],
    tags text[] DEFAULT ARRAY[]::text[],
    active boolean DEFAULT true NOT NULL,
    rating double precision,
    review_count integer DEFAULT 0 NOT NULL,
    view_count integer DEFAULT 0 NOT NULL,
    image_url text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    incoterm text,
    search_text text
);


--
-- Name: purchase_order_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_order_items (
    id text NOT NULL,
    purchase_order_id text NOT NULL,
    product_id text NOT NULL,
    quantity integer NOT NULL,
    unit_price numeric(14,2) NOT NULL,
    line_total numeric(14,2) NOT NULL
);


--
-- Name: purchase_orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.purchase_orders (
    id text NOT NULL,
    reference text NOT NULL,
    buyer_company_id text NOT NULL,
    supplier_company_id text NOT NULL,
    created_by_id text NOT NULL,
    approved_by_id text,
    status public."PoStatus" DEFAULT 'AGUARDANDO_APROVACAO'::public."PoStatus" NOT NULL,
    total_amount numeric(14,2) NOT NULL,
    currency text DEFAULT 'AOA'::text NOT NULL,
    is_call_off boolean DEFAULT false NOT NULL,
    contract_id text,
    accepted_at timestamp(3) without time zone,
    payment_due_at timestamp(3) without time zone,
    paid_at timestamp(3) without time zone,
    dispatched_at timestamp(3) without time zone,
    delivered_at timestamp(3) without time zone,
    received_at timestamp(3) without time zone,
    reception_status text,
    approved_at timestamp(3) without time zone,
    rejected_at timestamp(3) without time zone,
    rejection_reason text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    divergence_resolution text,
    divergence_resolution_notes text,
    divergence_resolved_at timestamp(3) without time zone,
    net_amount numeric(14,2),
    tax_amount numeric(14,2),
    withholding_amount numeric(14,2),
    refused_at timestamp(3) without time zone,
    refusal_reason text,
    erp_managed boolean DEFAULT false NOT NULL,
    erp_external_id text,
    erp_approval_requested_at timestamp(3) without time zone,
    created_by_source text DEFAULT 'HUMANO'::text NOT NULL,
    consolidated_invoice_id text
);


--
-- Name: quote_items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quote_items (
    id text NOT NULL,
    quote_request_id text NOT NULL,
    product_id text NOT NULL,
    quantity integer DEFAULT 1 NOT NULL
);


--
-- Name: quote_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quote_requests (
    id text NOT NULL,
    buyer_company_id text NOT NULL,
    supplier_company_id text NOT NULL,
    created_by_id text NOT NULL,
    status public."QuoteStatus" DEFAULT 'ABERTA'::public."QuoteStatus" NOT NULL,
    note text,
    response_price numeric(14,2),
    response_lead_days integer,
    response_note text,
    responded_at timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: reference_counters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reference_counters (
    key text NOT NULL,
    value integer DEFAULT 0 NOT NULL
);


--
-- Name: reviews; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.reviews (
    id text NOT NULL,
    product_id text NOT NULL,
    user_id text NOT NULL,
    rating integer NOT NULL,
    comment text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: risk_alerts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.risk_alerts (
    id text NOT NULL,
    conversation_id text NOT NULL,
    message_id text,
    level public."RiskLevel" NOT NULL,
    reason text NOT NULL,
    signals jsonb,
    context jsonb,
    status public."RiskAlertStatus" DEFAULT 'ABERTO'::public."RiskAlertStatus" NOT NULL,
    reviewed_by_id text,
    reviewed_at timestamp(3) without time zone,
    decision text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: saved_searches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.saved_searches (
    id text NOT NULL,
    user_id text NOT NULL,
    label text NOT NULL,
    query text NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: series_faturacao; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.series_faturacao (
    id text NOT NULL,
    codigo text NOT NULL,
    ano integer NOT NULL,
    ultimo_numero integer DEFAULT 0 NOT NULL,
    ultimo_hash text,
    ativa boolean DEFAULT true NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: stock_movements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_movements (
    id text NOT NULL,
    product_id text NOT NULL,
    type public."StockMovementType" NOT NULL,
    quantity integer NOT NULL,
    note text,
    created_by_id text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: supplier_dev_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_dev_requests (
    id text NOT NULL,
    reference text NOT NULL,
    company_id text,
    company_name text NOT NULL,
    tax_id text,
    contact_name text NOT NULL,
    contact_email text NOT NULL,
    contact_phone text,
    province text,
    sector text,
    employees integer,
    track public."SupplierDevTrack" DEFAULT 'AMBOS'::public."SupplierDevTrack" NOT NULL,
    needs text,
    status public."SupplierDevStatus" DEFAULT 'RECEBIDA'::public."SupplierDevStatus" NOT NULL,
    admin_notes text,
    handled_by_id text,
    handled_at timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    access_fee_usd numeric(10,2),
    custom_pricing boolean DEFAULT true NOT NULL,
    fee_status public."PlatformFeeStatus" DEFAULT 'PENDENTE'::public."PlatformFeeStatus" NOT NULL,
    fee_paid_at timestamp(3) without time zone,
    program_fee_usd numeric(12,2)
);


--
-- Name: supplier_to_kixima_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.supplier_to_kixima_policies (
    id text NOT NULL,
    company_id text NOT NULL,
    policy_number text NOT NULL,
    insurer text NOT NULL,
    coverage_amount numeric(14,2) NOT NULL,
    currency text DEFAULT 'AOA'::text NOT NULL,
    status public."PolicyStatus" DEFAULT 'SUBMETIDA'::public."PolicyStatus" NOT NULL,
    valid_from timestamp(3) without time zone NOT NULL,
    valid_until timestamp(3) without time zone NOT NULL,
    document_url text,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: support_category_images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_category_images (
    key text NOT NULL,
    image_url text NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL
);


--
-- Name: support_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_messages (
    id text NOT NULL,
    ticket_id text NOT NULL,
    author_id text NOT NULL,
    author_role public."PersonaRole" NOT NULL,
    body text NOT NULL,
    attachment_url text,
    attachment_name text,
    read_at timestamp(3) without time zone,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: support_tickets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_tickets (
    id text NOT NULL,
    reference text NOT NULL,
    user_id text NOT NULL,
    company_id text,
    subject text NOT NULL,
    category text NOT NULL,
    message text NOT NULL,
    status public."SupportStatus" DEFAULT 'ABERTO'::public."SupportStatus" NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    assigned_to_id text
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id text NOT NULL,
    name text NOT NULL,
    email text NOT NULL,
    password_hash text NOT NULL,
    role public."PersonaRole" NOT NULL,
    approval_cap numeric(14,2),
    company_id text,
    avatar_url text,
    active boolean DEFAULT true NOT NULL,
    token_version integer DEFAULT 0 NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone NOT NULL,
    terms_accepted_at timestamp(3) without time zone,
    totp_secret text,
    totp_enabled_at timestamp(3) without time zone,
    locale text,
    mfa_method text,
    mfa_code_hash text,
    mfa_code_expira_em timestamp(3) without time zone,
    mfa_code_tentativas integer DEFAULT 0 NOT NULL,
    mfa_code_enviado_em timestamp(3) without time zone,
    falhas_seguidas integer DEFAULT 0 NOT NULL,
    ultima_falha_em timestamp(3) without time zone,
    bloqueado_ate timestamp(3) without time zone,
    aviso_bloqueio_em timestamp(3) without time zone,
    admin_areas text[] DEFAULT '{}'::text[] NOT NULL
);


--
-- Name: addon_cobrancas addon_cobrancas_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.addon_cobrancas
    ADD CONSTRAINT addon_cobrancas_pkey PRIMARY KEY (id);


--
-- Name: agtseriesfe agtseriesfe_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agtseriesfe
    ADD CONSTRAINT agtseriesfe_pkey PRIMARY KEY (id);


--
-- Name: api_keys api_keys_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: budget_limits budget_limits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_limits
    ADD CONSTRAINT budget_limits_pkey PRIMARY KEY (id);


--
-- Name: companies companies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.companies
    ADD CONSTRAINT companies_pkey PRIMARY KEY (id);


--
-- Name: company_addons company_addons_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_addons
    ADD CONSTRAINT company_addons_pkey PRIMARY KEY (id);


--
-- Name: company_documents company_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_documents
    ADD CONSTRAINT company_documents_pkey PRIMARY KEY (id);


--
-- Name: company_erp_config_audits company_erp_config_audits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_erp_config_audits
    ADD CONSTRAINT company_erp_config_audits_pkey PRIMARY KEY (id);


--
-- Name: company_erp_configs company_erp_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_erp_configs
    ADD CONSTRAINT company_erp_configs_pkey PRIMARY KEY (id);


--
-- Name: contracts contracts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contracts
    ADD CONSTRAINT contracts_pkey PRIMARY KEY (id);


--
-- Name: conversation_messages conversation_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_messages
    ADD CONSTRAINT conversation_messages_pkey PRIMARY KEY (id);


--
-- Name: conversations conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_pkey PRIMARY KEY (id);


--
-- Name: credit_notes credit_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_notes
    ADD CONSTRAINT credit_notes_pkey PRIMARY KEY (id);


--
-- Name: discount_thresholds discount_thresholds_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.discount_thresholds
    ADD CONSTRAINT discount_thresholds_pkey PRIMARY KEY (id);


--
-- Name: employee_invites employee_invites_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_invites
    ADD CONSTRAINT employee_invites_pkey PRIMARY KEY (id);


--
-- Name: erp_sync_logs erp_sync_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.erp_sync_logs
    ADD CONSTRAINT erp_sync_logs_pkey PRIMARY KEY (id);


--
-- Name: favorites favorites_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.favorites
    ADD CONSTRAINT favorites_pkey PRIMARY KEY (id);


--
-- Name: feedback feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);


--
-- Name: invoice_lines invoice_lines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoice_lines
    ADD CONSTRAINT invoice_lines_pkey PRIMARY KEY (id);


--
-- Name: invoices invoices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);


--
-- Name: kit_items kit_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kit_items
    ADD CONSTRAINT kit_items_pkey PRIMARY KEY (id);


--
-- Name: kits kits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kits
    ADD CONSTRAINT kits_pkey PRIMARY KEY (id);


--
-- Name: kixima_to_client_policies kixima_to_client_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kixima_to_client_policies
    ADD CONSTRAINT kixima_to_client_policies_pkey PRIMARY KEY (id);


--
-- Name: linhas_extrato linhas_extrato_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.linhas_extrato
    ADD CONSTRAINT linhas_extrato_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- Name: plano_cobrancas plano_cobrancas_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plano_cobrancas
    ADD CONSTRAINT plano_cobrancas_pkey PRIMARY KEY (id);


--
-- Name: platform_fees platform_fees_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_fees
    ADD CONSTRAINT platform_fees_pkey PRIMARY KEY (id);


--
-- Name: po_robo_regras po_robo_regras_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.po_robo_regras
    ADD CONSTRAINT po_robo_regras_pkey PRIMARY KEY (id);


--
-- Name: product_documents product_documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT product_documents_pkey PRIMARY KEY (id);


--
-- Name: product_images product_images_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_images
    ADD CONSTRAINT product_images_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: purchase_order_items purchase_order_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_pkey PRIMARY KEY (id);


--
-- Name: purchase_orders purchase_orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_pkey PRIMARY KEY (id);


--
-- Name: quote_items quote_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_items
    ADD CONSTRAINT quote_items_pkey PRIMARY KEY (id);


--
-- Name: quote_requests quote_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_requests
    ADD CONSTRAINT quote_requests_pkey PRIMARY KEY (id);


--
-- Name: reference_counters reference_counters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reference_counters
    ADD CONSTRAINT reference_counters_pkey PRIMARY KEY (key);


--
-- Name: reviews reviews_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_pkey PRIMARY KEY (id);


--
-- Name: risk_alerts risk_alerts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_alerts
    ADD CONSTRAINT risk_alerts_pkey PRIMARY KEY (id);


--
-- Name: saved_searches saved_searches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_searches
    ADD CONSTRAINT saved_searches_pkey PRIMARY KEY (id);


--
-- Name: series_faturacao series_faturacao_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.series_faturacao
    ADD CONSTRAINT series_faturacao_pkey PRIMARY KEY (id);


--
-- Name: stock_movements stock_movements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT stock_movements_pkey PRIMARY KEY (id);


--
-- Name: supplier_dev_requests supplier_dev_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_dev_requests
    ADD CONSTRAINT supplier_dev_requests_pkey PRIMARY KEY (id);


--
-- Name: supplier_to_kixima_policies supplier_to_kixima_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_to_kixima_policies
    ADD CONSTRAINT supplier_to_kixima_policies_pkey PRIMARY KEY (id);


--
-- Name: support_category_images support_category_images_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_category_images
    ADD CONSTRAINT support_category_images_pkey PRIMARY KEY (key);


--
-- Name: support_messages support_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT support_messages_pkey PRIMARY KEY (id);


--
-- Name: support_tickets support_tickets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_tickets
    ADD CONSTRAINT support_tickets_pkey PRIMARY KEY (id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: addon_cobrancas_canal_referencia_externa_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX addon_cobrancas_canal_referencia_externa_idx ON public.addon_cobrancas USING btree (canal, referencia_externa);


--
-- Name: addon_cobrancas_company_id_addon_key_aberta_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX addon_cobrancas_company_id_addon_key_aberta_key ON public.addon_cobrancas USING btree (company_id, addon_key) WHERE (status = ANY (ARRAY['PENDENTE'::public."CobrancaStatus", 'COMPROVATIVO_ENVIADO'::public."CobrancaStatus"]));


--
-- Name: addon_cobrancas_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX addon_cobrancas_company_id_idx ON public.addon_cobrancas USING btree (company_id);


--
-- Name: addon_cobrancas_referencia_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX addon_cobrancas_referencia_key ON public.addon_cobrancas USING btree (referencia);


--
-- Name: addon_cobrancas_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX addon_cobrancas_status_idx ON public.addon_cobrancas USING btree (status);


--
-- Name: agtseriesfe_ano_tipo_documento_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agtseriesfe_ano_tipo_documento_idx ON public.agtseriesfe USING btree (ano, tipo_documento);


--
-- Name: api_keys_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX api_keys_company_id_idx ON public.api_keys USING btree (company_id);


--
-- Name: api_keys_prefixo_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX api_keys_prefixo_key ON public.api_keys USING btree (prefixo);


--
-- Name: audit_logs_actor_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX audit_logs_actor_id_idx ON public.audit_logs USING btree (actor_id);


--
-- Name: audit_logs_company_id_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX audit_logs_company_id_created_at_idx ON public.audit_logs USING btree (company_id, created_at);


--
-- Name: audit_logs_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX audit_logs_created_at_idx ON public.audit_logs USING btree (created_at);


--
-- Name: audit_logs_entity_type_entity_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX audit_logs_entity_type_entity_id_idx ON public.audit_logs USING btree (entity_type, entity_id);


--
-- Name: budget_limits_company_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX budget_limits_company_id_key ON public.budget_limits USING btree (company_id);


--
-- Name: companies_search_rank_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX companies_search_rank_idx ON public.companies USING btree (search_rank);


--
-- Name: companies_search_text_trgm_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX companies_search_text_trgm_idx ON public.companies USING gin (search_text public.gin_trgm_ops);


--
-- Name: companies_tax_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX companies_tax_id_key ON public.companies USING btree (tax_id);


--
-- Name: company_addons_company_id_addon_key_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX company_addons_company_id_addon_key_key ON public.company_addons USING btree (company_id, addon_key);


--
-- Name: company_documents_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX company_documents_company_id_idx ON public.company_documents USING btree (company_id);


--
-- Name: company_erp_config_audits_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX company_erp_config_audits_company_id_idx ON public.company_erp_config_audits USING btree (company_id);


--
-- Name: company_erp_configs_company_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX company_erp_configs_company_id_key ON public.company_erp_configs USING btree (company_id);


--
-- Name: contracts_client_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contracts_client_company_id_idx ON public.contracts USING btree (client_company_id);


--
-- Name: contracts_reference_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX contracts_reference_key ON public.contracts USING btree (reference);


--
-- Name: contracts_supplier_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contracts_supplier_company_id_idx ON public.contracts USING btree (supplier_company_id);


--
-- Name: conversation_messages_conversation_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversation_messages_conversation_id_idx ON public.conversation_messages USING btree (conversation_id);


--
-- Name: conversations_buyer_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversations_buyer_company_id_idx ON public.conversations USING btree (buyer_company_id);


--
-- Name: conversations_context_type_context_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversations_context_type_context_id_idx ON public.conversations USING btree (context_type, context_id);


--
-- Name: conversations_supplier_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversations_supplier_company_id_idx ON public.conversations USING btree (supplier_company_id);


--
-- Name: credit_notes_invoice_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX credit_notes_invoice_id_idx ON public.credit_notes USING btree (invoice_id);


--
-- Name: credit_notes_reference_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX credit_notes_reference_key ON public.credit_notes USING btree (reference);


--
-- Name: credit_notes_serie_numero_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX credit_notes_serie_numero_idx ON public.credit_notes USING btree (serie, numero_na_serie);


--
-- Name: credit_notes_serie_numero_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX credit_notes_serie_numero_key ON public.credit_notes USING btree (serie, numero_na_serie) WHERE (serie IS NOT NULL);


--
-- Name: discount_thresholds_ativo_min_volume_usd_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX discount_thresholds_ativo_min_volume_usd_idx ON public.discount_thresholds USING btree (ativo, min_volume_usd);


--
-- Name: employee_invites_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX employee_invites_company_id_idx ON public.employee_invites USING btree (company_id);


--
-- Name: employee_invites_email_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX employee_invites_email_idx ON public.employee_invites USING btree (email);


--
-- Name: employee_invites_token_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX employee_invites_token_key ON public.employee_invites USING btree (token);


--
-- Name: erp_sync_logs_purchase_order_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX erp_sync_logs_purchase_order_id_idx ON public.erp_sync_logs USING btree (purchase_order_id);


--
-- Name: favorites_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX favorites_product_id_idx ON public.favorites USING btree (product_id);


--
-- Name: favorites_user_id_product_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX favorites_user_id_product_id_key ON public.favorites USING btree (user_id, product_id);


--
-- Name: feedback_approved_created_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX feedback_approved_created_at_idx ON public.feedback USING btree (approved, created_at);


--
-- Name: feedback_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX feedback_company_id_idx ON public.feedback USING btree (company_id);


--
-- Name: feedback_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX feedback_user_id_idx ON public.feedback USING btree (user_id);


--
-- Name: invoice_lines_invoice_id_line_number_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX invoice_lines_invoice_id_line_number_key ON public.invoice_lines USING btree (invoice_id, line_number);


--
-- Name: invoices_contract_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX invoices_contract_id_idx ON public.invoices USING btree (contract_id);


--
-- Name: invoices_due_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX invoices_due_at_idx ON public.invoices USING btree (due_at);


--
-- Name: invoices_issued_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX invoices_issued_at_idx ON public.invoices USING btree (issued_at);


--
-- Name: invoices_purchase_order_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX invoices_purchase_order_id_key ON public.invoices USING btree (purchase_order_id);


--
-- Name: invoices_reference_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX invoices_reference_key ON public.invoices USING btree (reference);


--
-- Name: invoices_referencia_pagamento_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX invoices_referencia_pagamento_key ON public.invoices USING btree (referencia_pagamento) WHERE (referencia_pagamento IS NOT NULL);


--
-- Name: invoices_serie_numero_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX invoices_serie_numero_idx ON public.invoices USING btree (serie, numero_na_serie);


--
-- Name: invoices_serie_numero_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX invoices_serie_numero_key ON public.invoices USING btree (serie, numero_na_serie) WHERE (serie IS NOT NULL);


--
-- Name: invoices_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX invoices_status_idx ON public.invoices USING btree (status);


--
-- Name: kit_items_kit_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX kit_items_kit_id_idx ON public.kit_items USING btree (kit_id);


--
-- Name: kit_items_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX kit_items_product_id_idx ON public.kit_items USING btree (product_id);


--
-- Name: kits_supplier_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX kits_supplier_id_idx ON public.kits USING btree (supplier_id);


--
-- Name: kixima_to_client_policies_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX kixima_to_client_policies_company_id_idx ON public.kixima_to_client_policies USING btree (company_id);


--
-- Name: linhas_extrato_estado_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX linhas_extrato_estado_idx ON public.linhas_extrato USING btree (estado);


--
-- Name: linhas_extrato_id_no_banco_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX linhas_extrato_id_no_banco_key ON public.linhas_extrato USING btree (id_no_banco);


--
-- Name: linhas_extrato_referencia_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX linhas_extrato_referencia_idx ON public.linhas_extrato USING btree (referencia);


--
-- Name: notifications_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notifications_company_id_idx ON public.notifications USING btree (company_id);


--
-- Name: notifications_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX notifications_user_id_idx ON public.notifications USING btree (user_id);


--
-- Name: payments_invoice_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX payments_invoice_id_key ON public.payments USING btree (invoice_id);


--
-- Name: payments_reference_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX payments_reference_key ON public.payments USING btree (reference);


--
-- Name: payments_serie_numero_na_serie_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX payments_serie_numero_na_serie_idx ON public.payments USING btree (serie, numero_na_serie);


--
-- Name: plano_cobrancas_canal_referencia_externa_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX plano_cobrancas_canal_referencia_externa_idx ON public.plano_cobrancas USING btree (canal, referencia_externa);


--
-- Name: plano_cobrancas_company_id_aberta_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX plano_cobrancas_company_id_aberta_key ON public.plano_cobrancas USING btree (company_id) WHERE (status = ANY (ARRAY['PENDENTE'::public."CobrancaStatus", 'COMPROVATIVO_ENVIADO'::public."CobrancaStatus"]));


--
-- Name: plano_cobrancas_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX plano_cobrancas_company_id_idx ON public.plano_cobrancas USING btree (company_id);


--
-- Name: plano_cobrancas_referencia_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX plano_cobrancas_referencia_key ON public.plano_cobrancas USING btree (referencia);


--
-- Name: plano_cobrancas_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX plano_cobrancas_status_idx ON public.plano_cobrancas USING btree (status);


--
-- Name: platform_fees_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_fees_company_id_idx ON public.platform_fees USING btree (company_id);


--
-- Name: platform_fees_invoice_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX platform_fees_invoice_id_key ON public.platform_fees USING btree (invoice_id);


--
-- Name: platform_fees_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX platform_fees_status_idx ON public.platform_fees USING btree (status);


--
-- Name: po_robo_regras_ativo_proxima_execucao_em_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX po_robo_regras_ativo_proxima_execucao_em_idx ON public.po_robo_regras USING btree (ativo, proxima_execucao_em);


--
-- Name: po_robo_regras_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX po_robo_regras_company_id_idx ON public.po_robo_regras USING btree (company_id);


--
-- Name: product_documents_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX product_documents_product_id_idx ON public.product_documents USING btree (product_id);


--
-- Name: product_images_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX product_images_product_id_idx ON public.product_images USING btree (product_id);


--
-- Name: products_category_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX products_category_idx ON public.products USING btree (category);


--
-- Name: products_kind_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX products_kind_idx ON public.products USING btree (kind);


--
-- Name: products_search_text_trgm_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX products_search_text_trgm_idx ON public.products USING gin (search_text public.gin_trgm_ops);


--
-- Name: products_slug_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX products_slug_key ON public.products USING btree (slug);


--
-- Name: products_supplier_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX products_supplier_id_idx ON public.products USING btree (supplier_id);


--
-- Name: purchase_order_items_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_order_items_product_id_idx ON public.purchase_order_items USING btree (product_id);


--
-- Name: purchase_order_items_purchase_order_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_order_items_purchase_order_id_idx ON public.purchase_order_items USING btree (purchase_order_id);


--
-- Name: purchase_orders_approved_by_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_orders_approved_by_id_idx ON public.purchase_orders USING btree (approved_by_id);


--
-- Name: purchase_orders_buyer_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_orders_buyer_company_id_idx ON public.purchase_orders USING btree (buyer_company_id);


--
-- Name: purchase_orders_consolidated_invoice_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_orders_consolidated_invoice_id_idx ON public.purchase_orders USING btree (consolidated_invoice_id);


--
-- Name: purchase_orders_contract_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_orders_contract_id_idx ON public.purchase_orders USING btree (contract_id);


--
-- Name: purchase_orders_created_by_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_orders_created_by_id_idx ON public.purchase_orders USING btree (created_by_id);


--
-- Name: purchase_orders_reference_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX purchase_orders_reference_key ON public.purchase_orders USING btree (reference);


--
-- Name: purchase_orders_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_orders_status_idx ON public.purchase_orders USING btree (status);


--
-- Name: purchase_orders_supplier_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_orders_supplier_company_id_idx ON public.purchase_orders USING btree (supplier_company_id);


--
-- Name: quote_items_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX quote_items_product_id_idx ON public.quote_items USING btree (product_id);


--
-- Name: quote_items_quote_request_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX quote_items_quote_request_id_idx ON public.quote_items USING btree (quote_request_id);


--
-- Name: quote_requests_buyer_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX quote_requests_buyer_company_id_idx ON public.quote_requests USING btree (buyer_company_id);


--
-- Name: quote_requests_supplier_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX quote_requests_supplier_company_id_idx ON public.quote_requests USING btree (supplier_company_id);


--
-- Name: reviews_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reviews_product_id_idx ON public.reviews USING btree (product_id);


--
-- Name: reviews_product_id_user_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX reviews_product_id_user_id_key ON public.reviews USING btree (product_id, user_id);


--
-- Name: reviews_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reviews_user_id_idx ON public.reviews USING btree (user_id);


--
-- Name: risk_alerts_conversation_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX risk_alerts_conversation_id_idx ON public.risk_alerts USING btree (conversation_id);


--
-- Name: risk_alerts_message_id_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX risk_alerts_message_id_key ON public.risk_alerts USING btree (message_id);


--
-- Name: risk_alerts_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX risk_alerts_status_idx ON public.risk_alerts USING btree (status);


--
-- Name: saved_searches_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX saved_searches_user_id_idx ON public.saved_searches USING btree (user_id);


--
-- Name: series_faturacao_codigo_ano_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX series_faturacao_codigo_ano_key ON public.series_faturacao USING btree (codigo, ano);


--
-- Name: stock_movements_product_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX stock_movements_product_id_idx ON public.stock_movements USING btree (product_id);


--
-- Name: supplier_dev_requests_reference_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX supplier_dev_requests_reference_key ON public.supplier_dev_requests USING btree (reference);


--
-- Name: supplier_dev_requests_status_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX supplier_dev_requests_status_idx ON public.supplier_dev_requests USING btree (status);


--
-- Name: supplier_to_kixima_policies_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX supplier_to_kixima_policies_company_id_idx ON public.supplier_to_kixima_policies USING btree (company_id);


--
-- Name: support_messages_ticket_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX support_messages_ticket_id_idx ON public.support_messages USING btree (ticket_id);


--
-- Name: support_tickets_assigned_to_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX support_tickets_assigned_to_id_idx ON public.support_tickets USING btree (assigned_to_id);


--
-- Name: support_tickets_reference_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX support_tickets_reference_key ON public.support_tickets USING btree (reference);


--
-- Name: support_tickets_user_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX support_tickets_user_id_idx ON public.support_tickets USING btree (user_id);


--
-- Name: users_bloqueado_ate_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX users_bloqueado_ate_idx ON public.users USING btree (bloqueado_ate);


--
-- Name: users_company_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX users_company_id_idx ON public.users USING btree (company_id);


--
-- Name: users_email_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX users_email_key ON public.users USING btree (email);


--
-- Name: companies companies_search_text_trg; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER companies_search_text_trg BEFORE INSERT OR UPDATE ON public.companies FOR EACH ROW EXECUTE FUNCTION public.companies_search_text();


--
-- Name: products products_search_text_trg; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER products_search_text_trg BEFORE INSERT OR UPDATE ON public.products FOR EACH ROW EXECUTE FUNCTION public.products_search_text();


--
-- Name: addon_cobrancas addon_cobrancas_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.addon_cobrancas
    ADD CONSTRAINT addon_cobrancas_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: api_keys api_keys_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_keys
    ADD CONSTRAINT api_keys_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: budget_limits budget_limits_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.budget_limits
    ADD CONSTRAINT budget_limits_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: company_addons company_addons_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_addons
    ADD CONSTRAINT company_addons_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: company_documents company_documents_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_documents
    ADD CONSTRAINT company_documents_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: company_erp_config_audits company_erp_config_audits_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_erp_config_audits
    ADD CONSTRAINT company_erp_config_audits_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: company_erp_configs company_erp_configs_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.company_erp_configs
    ADD CONSTRAINT company_erp_configs_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: contracts contracts_client_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contracts
    ADD CONSTRAINT contracts_client_company_id_fkey FOREIGN KEY (client_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: contracts contracts_supplier_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contracts
    ADD CONSTRAINT contracts_supplier_company_id_fkey FOREIGN KEY (supplier_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: conversation_messages conversation_messages_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_messages
    ADD CONSTRAINT conversation_messages_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: conversation_messages conversation_messages_sender_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversation_messages
    ADD CONSTRAINT conversation_messages_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: conversations conversations_buyer_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_buyer_company_id_fkey FOREIGN KEY (buyer_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: conversations conversations_supplier_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_supplier_company_id_fkey FOREIGN KEY (supplier_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: credit_notes credit_notes_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credit_notes
    ADD CONSTRAINT credit_notes_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: employee_invites employee_invites_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.employee_invites
    ADD CONSTRAINT employee_invites_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: erp_sync_logs erp_sync_logs_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.erp_sync_logs
    ADD CONSTRAINT erp_sync_logs_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: favorites favorites_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.favorites
    ADD CONSTRAINT favorites_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: favorites favorites_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.favorites
    ADD CONSTRAINT favorites_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: feedback feedback_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: feedback feedback_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT feedback_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: invoice_lines invoice_lines_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoice_lines
    ADD CONSTRAINT invoice_lines_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: invoices invoices_contract_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_contract_id_fkey FOREIGN KEY (contract_id) REFERENCES public.contracts(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: invoices invoices_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invoices
    ADD CONSTRAINT invoices_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: kit_items kit_items_kit_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kit_items
    ADD CONSTRAINT kit_items_kit_id_fkey FOREIGN KEY (kit_id) REFERENCES public.kits(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: kit_items kit_items_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kit_items
    ADD CONSTRAINT kit_items_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: kits kits_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kits
    ADD CONSTRAINT kits_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: kixima_to_client_policies kixima_to_client_policies_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.kixima_to_client_policies
    ADD CONSTRAINT kixima_to_client_policies_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: linhas_extrato linhas_extrato_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.linhas_extrato
    ADD CONSTRAINT linhas_extrato_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(id);


--
-- Name: notifications notifications_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: notifications notifications_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: payments payments_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: plano_cobrancas plano_cobrancas_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plano_cobrancas
    ADD CONSTRAINT plano_cobrancas_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: platform_fees platform_fees_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_fees
    ADD CONSTRAINT platform_fees_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: platform_fees platform_fees_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.platform_fees
    ADD CONSTRAINT platform_fees_invoice_id_fkey FOREIGN KEY (invoice_id) REFERENCES public.invoices(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: po_robo_regras po_robo_regras_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.po_robo_regras
    ADD CONSTRAINT po_robo_regras_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: po_robo_regras po_robo_regras_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.po_robo_regras
    ADD CONSTRAINT po_robo_regras_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: product_documents product_documents_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_documents
    ADD CONSTRAINT product_documents_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: product_images product_images_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.product_images
    ADD CONSTRAINT product_images_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: products products_supplier_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: purchase_order_items purchase_order_items_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: purchase_order_items purchase_order_items_purchase_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_order_items
    ADD CONSTRAINT purchase_order_items_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: purchase_orders purchase_orders_approved_by_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_approved_by_id_fkey FOREIGN KEY (approved_by_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: purchase_orders purchase_orders_buyer_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_buyer_company_id_fkey FOREIGN KEY (buyer_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: purchase_orders purchase_orders_consolidated_invoice_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_consolidated_invoice_id_fkey FOREIGN KEY (consolidated_invoice_id) REFERENCES public.invoices(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: purchase_orders purchase_orders_contract_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_contract_id_fkey FOREIGN KEY (contract_id) REFERENCES public.contracts(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: purchase_orders purchase_orders_created_by_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: purchase_orders purchase_orders_supplier_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.purchase_orders
    ADD CONSTRAINT purchase_orders_supplier_company_id_fkey FOREIGN KEY (supplier_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: quote_items quote_items_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_items
    ADD CONSTRAINT quote_items_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: quote_items quote_items_quote_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_items
    ADD CONSTRAINT quote_items_quote_request_id_fkey FOREIGN KEY (quote_request_id) REFERENCES public.quote_requests(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: quote_requests quote_requests_buyer_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_requests
    ADD CONSTRAINT quote_requests_buyer_company_id_fkey FOREIGN KEY (buyer_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: quote_requests quote_requests_supplier_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quote_requests
    ADD CONSTRAINT quote_requests_supplier_company_id_fkey FOREIGN KEY (supplier_company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: reviews reviews_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: reviews reviews_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.reviews
    ADD CONSTRAINT reviews_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: risk_alerts risk_alerts_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_alerts
    ADD CONSTRAINT risk_alerts_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: risk_alerts risk_alerts_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_alerts
    ADD CONSTRAINT risk_alerts_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.conversation_messages(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: risk_alerts risk_alerts_reviewed_by_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.risk_alerts
    ADD CONSTRAINT risk_alerts_reviewed_by_id_fkey FOREIGN KEY (reviewed_by_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: saved_searches saved_searches_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.saved_searches
    ADD CONSTRAINT saved_searches_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: stock_movements stock_movements_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_movements
    ADD CONSTRAINT stock_movements_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: supplier_dev_requests supplier_dev_requests_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_dev_requests
    ADD CONSTRAINT supplier_dev_requests_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: supplier_to_kixima_policies supplier_to_kixima_policies_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.supplier_to_kixima_policies
    ADD CONSTRAINT supplier_to_kixima_policies_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: support_messages support_messages_author_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT support_messages_author_id_fkey FOREIGN KEY (author_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE RESTRICT;


--
-- Name: support_messages support_messages_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_messages
    ADD CONSTRAINT support_messages_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.support_tickets(id) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: support_tickets support_tickets_assigned_to_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_tickets
    ADD CONSTRAINT support_tickets_assigned_to_id_fkey FOREIGN KEY (assigned_to_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- Name: users users_company_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE SET NULL;


--
-- PostgreSQL database dump complete
--


