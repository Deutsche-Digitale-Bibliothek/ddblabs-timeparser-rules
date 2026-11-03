/**
 * Statische fachliche Prüfungen für Regelvorlagen und deren konkrete Varianten.
 *
 * <p>{@link de.deutsche_digitale_bibliothek.timeparser.validation.RuleGroupValidator}
 * prüft zuerst die Formulargrundstruktur und später die expandierten Testwerte.
 * {@link de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator}
 * untersucht konkrete Masken-/Musterpaare einschließlich der Variablenübertragung.
 * {@link de.deutsche_digitale_bibliothek.timeparser.validation.RuleMaskOverlap}
 * liefert ergänzende Beispiele möglicher Maskenüberschneidungen.</p>
 *
 * <p>Die Koordination liegt im Repository: Es löst Platzhalter auf, ordnet
 * Befunde den ursprünglichen Formularfeldern zu und trennt Speicherfehler von
 * unverbindlichen Hinweisen. Ein
 * {@link de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue}
 * trägt deshalb einen Feldpfad, einen Prüfcode und eine Meldung, aber keine
 * eigene Schweregradangabe.</p>
 *
 * <p>Alle Klassen prüfen Plausibilität. Sie führen die eingetragenen Testfälle
 * nicht im Timeparser aus und bestätigen weder tatsächliche Tokenisierung und
 * Regelauswahl noch Transformation oder Datumsinterpretation. Diese Grenze ist
 * auch bei leerer Befundliste zu beachten.</p>
 */
package de.deutsche_digitale_bibliothek.timeparser.validation;
