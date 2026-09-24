package dev.pampa.pampanotes.core.db

/**
 * La sezione Registrazioni in SQL: le cartelle personali, sottocartelle comprese.
 *
 * Una sottoselect sola, da mettere dopo un `IN` o un `NOT IN` nelle query che la home e la sezione
 * contano ciascuna per conto suo (ore, parole, giorni, «Da fare»). Conta il tipo della **radice**:
 * una sottocartella sta dove sta la cartella di primo livello che la contiene, qualunque cosa dica
 * la sua colonna, perche' e' cosi' che la si vede — dentro Registrazioni o dentro una materia. La
 * stessa regola in Kotlin e' `PersonalScope.folderIds`, e i due devono dire la stessa cosa.
 *
 * Ricorsiva e non un `JOIN` a due livelli: le cartelle si annidano quanto si vuole. `UNION` e non
 * `UNION ALL`, cosi' un ciclo nei genitori portato da un sync fatto male non gira per sempre.
 */
object PersonalSql {
  const val FOLDER_IDS: String =
    "(WITH RECURSIVE personal_tree(id) AS (" +
      "SELECT id FROM folders WHERE parentId IS NULL AND kind = 'personal' " +
      "UNION SELECT f.id FROM folders f JOIN personal_tree t ON f.parentId = t.id" +
      ") SELECT id FROM personal_tree)"
}
