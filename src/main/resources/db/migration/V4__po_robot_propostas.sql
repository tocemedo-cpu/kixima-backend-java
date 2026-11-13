-- Automatic PO Robot: previsões, propostas de compra e execuções (contrato: docs/api-po-robot.md do frontend).
--
-- O robot PREPARA propostas; nunca cria ordens de compra, nem aprova, nem paga. A PO só nasce quando uma pessoa
-- submete a proposta (PoRoboPropostaService.submeter), e nasce em AGUARDANDO_APROVACAO.
--
-- Aditiva e idempotente (CREATE ... IF NOT EXISTS / ADD COLUMN IF NOT EXISTS): não altera nem apaga dados existentes,
-- não toca nas migrações V1-V3 e não insere dados. Apagar uma regra mantém as propostas e previsões no histórico
-- (regra_id passa a NULL).

-- 1) Campos novos da regra. Opcionais: sem fonte fiável do stock do comprador, só ficam guardados e entram na previsão.
ALTER TABLE public.po_robo_regras ADD COLUMN IF NOT EXISTS stock_minimo integer;
ALTER TABLE public.po_robo_regras ADD COLUMN IF NOT EXISTS stock_seguranca integer;
ALTER TABLE public.po_robo_regras ADD COLUMN IF NOT EXISTS prazo_entrega_dias integer;
ALTER TABLE public.po_robo_regras ADD COLUMN IF NOT EXISTS ultima_execucao_em timestamp(3) without time zone;

-- 2) Execuções: uma por empresa em cada corrida agendada do job.
CREATE TABLE IF NOT EXISTS public.po_robo_execucoes (
    id text NOT NULL,
    company_id text NOT NULL,
    iniciada_em timestamp(3) without time zone NOT NULL,
    terminada_em timestamp(3) without time zone,
    estado text DEFAULT 'EM_CURSO' NOT NULL,
    origem text DEFAULT 'AGENDADA' NOT NULL,
    regras_avaliadas integer DEFAULT 0 NOT NULL,
    propostas_criadas integer DEFAULT 0 NOT NULL,
    erros text DEFAULT '[]' NOT NULL,
    CONSTRAINT po_robo_execucoes_pkey PRIMARY KEY (id),
    CONSTRAINT po_robo_execucoes_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT po_robo_execucoes_estado_check CHECK (estado IN ('EM_CURSO', 'CONCLUIDA', 'COM_ERROS', 'FALHOU')),
    CONSTRAINT po_robo_execucoes_origem_check CHECK (origem = 'AGENDADA'),
    CONSTRAINT po_robo_execucoes_contagens_check CHECK (regras_avaliadas >= 0 AND propostas_criadas >= 0)
);
CREATE INDEX IF NOT EXISTS po_robo_execucoes_company_id_iniciada_em_idx ON public.po_robo_execucoes USING btree (company_id, iniciada_em DESC);

-- 3) Previsões: calculadas pelo servidor em cada execução, com a justificação passo a passo (JSON em texto).
--    stock_actual é sempre NULL: não existe fonte fiável do inventário do comprador (e o stock do fornecedor nunca o substitui).
CREATE TABLE IF NOT EXISTS public.po_robo_previsoes (
    id text NOT NULL,
    company_id text NOT NULL,
    regra_id text,
    product_id text NOT NULL,
    execucao_id text,
    gerada_em timestamp(3) without time zone NOT NULL,
    consumo_medio_mensal numeric(14,3) NOT NULL,
    stock_actual integer,
    stock_minimo integer,
    stock_seguranca integer,
    prazo_entrega_dias integer,
    ponto_encomenda numeric(14,3),
    quantidade_recomendada integer NOT NULL,
    origem_dados text NOT NULL,
    justificacao text DEFAULT '[]' NOT NULL,
    CONSTRAINT po_robo_previsoes_pkey PRIMARY KEY (id),
    CONSTRAINT po_robo_previsoes_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT po_robo_previsoes_regra_id_fkey FOREIGN KEY (regra_id) REFERENCES public.po_robo_regras(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT po_robo_previsoes_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT po_robo_previsoes_execucao_id_fkey FOREIGN KEY (execucao_id) REFERENCES public.po_robo_execucoes(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT po_robo_previsoes_origem_dados_check CHECK (origem_dados IN ('HISTORICO_COMPRAS', 'ERP', 'MANUAL')),
    CONSTRAINT po_robo_previsoes_quantidade_check CHECK (quantidade_recomendada >= 0)
);
CREATE INDEX IF NOT EXISTS po_robo_previsoes_company_id_gerada_em_idx ON public.po_robo_previsoes USING btree (company_id, gerada_em DESC);
CREATE INDEX IF NOT EXISTS po_robo_previsoes_regra_id_gerada_em_idx ON public.po_robo_previsoes USING btree (regra_id, gerada_em DESC);

-- 4) Propostas de compra. A quantidade é sempre > 0 (sem unidades que caibam no limite, não há proposta).
CREATE TABLE IF NOT EXISTS public.po_robo_propostas (
    id text NOT NULL,
    company_id text NOT NULL,
    regra_id text,
    previsao_id text,
    execucao_id text,
    product_id text NOT NULL,
    supplier_id text NOT NULL,
    quantidade integer NOT NULL,
    quantidade_original integer NOT NULL,
    preco_unitario numeric(14,2),
    moeda text NOT NULL,
    valor_total numeric(14,2),
    estado text DEFAULT 'PENDENTE_REVISAO' NOT NULL,
    criada_em timestamp(3) without time zone NOT NULL,
    expirada_em timestamp(3) without time zone,
    motivo_expiracao text,
    justificacao text DEFAULT '[]' NOT NULL,
    limite_aplicado boolean DEFAULT false NOT NULL,
    purchase_order_id text,
    purchase_order_reference text,
    motivo_rejeicao text,
    revista_por_id text,
    revista_em timestamp(3) without time zone,
    updated_at timestamp(3) without time zone NOT NULL,
    CONSTRAINT po_robo_propostas_pkey PRIMARY KEY (id),
    CONSTRAINT po_robo_propostas_company_id_fkey FOREIGN KEY (company_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT po_robo_propostas_regra_id_fkey FOREIGN KEY (regra_id) REFERENCES public.po_robo_regras(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT po_robo_propostas_previsao_id_fkey FOREIGN KEY (previsao_id) REFERENCES public.po_robo_previsoes(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT po_robo_propostas_execucao_id_fkey FOREIGN KEY (execucao_id) REFERENCES public.po_robo_execucoes(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT po_robo_propostas_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT po_robo_propostas_supplier_id_fkey FOREIGN KEY (supplier_id) REFERENCES public.companies(id) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT po_robo_propostas_purchase_order_id_fkey FOREIGN KEY (purchase_order_id) REFERENCES public.purchase_orders(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT po_robo_propostas_revista_por_id_fkey FOREIGN KEY (revista_por_id) REFERENCES public.users(id) ON UPDATE CASCADE ON DELETE SET NULL,
    CONSTRAINT po_robo_propostas_estado_check CHECK (estado IN ('PENDENTE_REVISAO', 'SUBMETIDA', 'REJEITADA', 'EXPIRADA')),
    CONSTRAINT po_robo_propostas_quantidade_check CHECK (quantidade > 0 AND quantidade_original > 0)
);
CREATE INDEX IF NOT EXISTS po_robo_propostas_company_id_criada_em_idx ON public.po_robo_propostas USING btree (company_id, criada_em DESC);
CREATE INDEX IF NOT EXISTS po_robo_propostas_regra_id_idx ON public.po_robo_propostas USING btree (regra_id);
-- No máximo uma proposta pendente por regra: uma nova expira a anterior (feito pelo serviço) e a base garante-o.
CREATE UNIQUE INDEX IF NOT EXISTS po_robo_propostas_regra_pendente_uq ON public.po_robo_propostas USING btree (regra_id)
    WHERE estado = 'PENDENTE_REVISAO' AND regra_id IS NOT NULL;
