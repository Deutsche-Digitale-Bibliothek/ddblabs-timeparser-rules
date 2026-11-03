/**
 * SQLite-Persistenz, Platzhalterexpansion und Zusammenführung fachlicher Befunde.
 *
 * <p>{@link de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository}
 * speichert Vorlagen, erzeugt daraus konkrete Varianten und stellt deren Herkunft für
 * Formularfeldpfade und Oberflächenlinks bereit. Speichern und Prüfen sind getrennte
 * Operationen: Die Controller bereinigen und validieren Formulare vor der Persistenz;
 * der initiale CSV-Import kann dagegen fachlich fehlerhafte Bestandsdaten übernehmen.</p>
 *
 * <p>Generierte Übersichtszeilen sind noch kein bereinigter Export. Für Downloads
 * verbindet ein {@link de.deutsche_digitale_bibliothek.timeparser.model.ExportSnapshot}
 * den gelesenen Datenstand mit kanonischen Regelreferenzen und Prüfbericht. Ein Befund
 * ist weder ein Exportverbot noch das Ergebnis eines tatsächlich ausgeführten Parsertests.</p>
 */
package de.deutsche_digitale_bibliothek.timeparser.repository;
