-- Banners da homepage pública, geridos pelo Admin do Sistema (área «Operação da Plataforma»).
-- Contrato: docs/api-banners.md do repositório do frontend. Idempotente e sem apagar dados.
--
-- `imagem` é vazio até haver upload (um banner sem imagem nunca é publicado); `ativo` só pode ser true com imagem —
-- a regra vive no serviço, não aqui, para a mensagem de erro chegar ao ecrã por campo.
-- `ordem` é 1..n, mantida pelo serviço (sem UNIQUE: reordenar troca vários valores na mesma transacção).
CREATE TABLE IF NOT EXISTS public.banners (
    id text NOT NULL,
    etiqueta text,
    titulo text NOT NULL,
    texto text NOT NULL,
    cta text NOT NULL,
    href text NOT NULL,
    cta2 text,
    href2 text,
    imagem text DEFAULT '' NOT NULL,
    ativo boolean DEFAULT false NOT NULL,
    ordem integer NOT NULL,
    created_at timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp(3) without time zone,
    CONSTRAINT banners_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS banners_ativo_ordem_idx ON public.banners USING btree (ativo, ordem);

-- Os 4 banners que a homepage já mostrava. As imagens são servidas pelo próprio SPA (public/banners/ do frontend).
-- Só entram se a tabela estiver vazia: quem já geriu os seus banners não os vê repostos.
INSERT INTO public.banners (id, etiqueta, titulo, texto, cta, href, cta2, href2, imagem, ativo, ordem)
SELECT v.id, v.etiqueta, v.titulo, v.texto, v.cta, v.href, v.cta2, v.href2, v.imagem, true, v.ordem
FROM (VALUES
    ('bn-1', 'Oil & Gas · Offshore', 'Tudo o que a sua operação precisa, num só lugar',
     'Equipamentos e serviços técnicos de fornecedoras verificadas, com pagamento seguro e rastreável.',
     'Explorar catálogo', '/produtos', 'Pedir cotação', '/login', '/banners/kixima-offshore.jpg', 1),
    ('bn-2', 'Promoções da semana', 'EPI com até 20% de desconto',
     'Capacetes, luvas, coletes e óculos certificados — stock pronto em Luanda.',
     'Ver promoções', '/produtos?promo=true', NULL, NULL, '/banners/kixima-energy-mining.webp', 2),
    ('bn-3', 'Logística', 'Entrega rápida de Luanda ao Lobito',
     'Transporte de carga industrial com acompanhamento em tempo real.',
     'Ver serviços', '/produtos?kind=SERVICO', NULL, NULL, '/banners/kixima-logistics-agri.webp', 3),
    ('bn-4', 'Para empresas', 'Compre com aprovação e controlo de equipa',
     'Cotações, aprovações e pagamentos num fluxo único para toda a empresa.',
     'Criar conta grátis', '/cadastro', 'Ver planos', '/planos', '/banners/kixima-human-network.webp', 4)
) AS v(id, etiqueta, titulo, texto, cta, href, cta2, href2, imagem, ordem)
WHERE NOT EXISTS (SELECT 1 FROM public.banners);
