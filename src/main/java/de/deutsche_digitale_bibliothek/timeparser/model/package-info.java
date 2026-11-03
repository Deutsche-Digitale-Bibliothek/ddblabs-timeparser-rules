/**
 * Datenmodelle für gespeicherte Übersichten, konkrete CSV-Zeilen, Vorschauen und Prüfbefunde.
 *
 * <p>Bearbeitbare Regel- und Testvorlagen liegen im Web-Formularmodell. Die Klassen
 * {@link de.deutsche_digitale_bibliothek.timeparser.model.Rule} und
 * {@link de.deutsche_digitale_bibliothek.timeparser.model.RuleTest} repräsentieren
 * flache Import-, Vorschau- und Exportzeilen und sind veränderliche Beans.</p>
 *
 * <p>Die Records dieses Pakets transportieren Ergebnisse, führen aber keine
 * Parser-Verarbeitung aus. Ein Record garantiert nicht automatisch tiefe
 * Unveränderlichkeit: Ob Listen kopiert werden, ist am jeweiligen Modell
 * dokumentiert. Datenbank- und Transaktionsgrenzen gehören zum Repository.</p>
 */
package de.deutsche_digitale_bibliothek.timeparser.model;
