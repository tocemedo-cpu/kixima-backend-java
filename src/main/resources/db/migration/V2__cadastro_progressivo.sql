-- Cadastro empresarial em etapas: conta primeiro (email confirmado), dados e documentos da empresa depois.
--
-- 1. Novo estado de cadastro: RASCUNHO = a empresa existe (tem tipo e administrador) mas o perfil ainda não foi
--    submetido. Fica antes de PENDENTE (em análise), APROVADA e REJEITADA, que não mudam.
ALTER TYPE public."CompanyStatus" ADD VALUE IF NOT EXISTS 'RASCUNHO' BEFORE 'PENDENTE';

-- 2. Nome e NIF só são conhecidos quando o utilizador preenche o perfil; num rascunho ficam por preencher (NULL —
--    nunca um valor inventado). A submissão volta a exigi-los. UNIQUE mantém-se: o Postgres admite vários NULL.
ALTER TABLE public.companies ALTER COLUMN name DROP NOT NULL;
ALTER TABLE public.companies ALTER COLUMN tax_id DROP NOT NULL;

-- 3. Dados do fluxo de aprovação: quando foi submetido, porque foi rejeitado e os dados da apólice (Prestadoras)
--    guardados em rascunho — a apólice só passa a registo próprio (supplier_to_kixima_policies) na submissão.
ALTER TABLE public.companies ADD COLUMN IF NOT EXISTS submitted_at timestamp(3) without time zone;
ALTER TABLE public.companies ADD COLUMN IF NOT EXISTS rejection_reason text;
ALTER TABLE public.companies ADD COLUMN IF NOT EXISTS onboarding_draft text;

-- 4. Confirmação do email da conta. As contas que já existem contam como confirmadas (não perdem o acesso);
--    só as criadas pelo novo registo nascem por confirmar.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS email_verified_at timestamp(3) without time zone;
UPDATE public.users SET email_verified_at = created_at WHERE email_verified_at IS NULL;
-- Quem insere utilizadores sem conhecer a coluna (o backend Node que partilha a base, scripts, fixtures de testes) cria
-- contas confirmadas, como sempre foi. Só o registo em dois passos escreve NULL de propósito, para exigir o link.
ALTER TABLE public.users ALTER COLUMN email_verified_at SET DEFAULT CURRENT_TIMESTAMP;
