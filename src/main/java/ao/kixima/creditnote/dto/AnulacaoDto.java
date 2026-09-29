package ao.kixima.creditnote.dto;

/** Espelha `{ creditNote, agtSubmission }` devolvido por creditNoteService.anular. */
public record AnulacaoDto(CreditNoteDto creditNote, Object agtSubmission) {
}
