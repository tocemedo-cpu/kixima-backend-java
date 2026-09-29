package ao.kixima.common.persistence;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Base para entidades cujo {@code id} é atribuído do lado da aplicação —
 * `text`, não `@GeneratedValue` (ver Company.java: o Prisma gera o UUID no
 * cliente, Java tem de fazer o mesmo). Sem isto, o {@code isNew()} por
 * omissão do Spring Data JPA considera "não-novo" qualquer entidade com ID
 * não-nulo — e como o ID já vem preenchido ANTES do primeiro
 * {@code repository.save()}, `SimpleJpaRepository.save()` chamaria
 * {@code entityManager.merge(entity)} em vez de {@code persist(entity)}.
 * `merge()` devolve uma CÓPIA gerida diferente da instância passada; uma
 * mutação feita na variável original DEPOIS do save() (ex.:
 * {@code invoice.setReferenciaPagamento(...)} em ConciliacaoService, com o
 * `invoice` que PoService continua a usar) fica nessa cópia descartada —
 * nunca chega à base, silenciosamente. `Persistable` com uma bandeira
 * transitória corrige isto na origem: a entidade é "nova" até ao primeiro
 * persist ou load, tal como o INSERT desta linha no Node/Prisma.
 */
@MappedSuperclass
public abstract class AbstractPersistableEntity<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void marcarComoPersistida() {
        this.isNew = false;
    }
}
