package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;

/**
 * Gemeinsam erzeugte Vorschau der expandierten Zeilen einer Regelgruppe.
 *
 * <p>Regeln und Tests werden mit demselben Satz von Platzhalterwerten erzeugt.
 * Die Tests werden dabei anhand der tokenisierten Werte passenden Regelvarianten
 * zugeordnet. Eine Vorschau ist keine Ausführung dieser Testfälle und entfernt
 * nicht bereits die Dubletten der späteren Exportaufbereitung.</p>
 *
 * <p>Der Record führt keine defensiven Listenkopien oder eigene Prüfungen durch.</p>
 *
 * @param rules erzeugte Regelzeilen in der vom Repository bestimmten Reihenfolge
 * @param tests dazu erzeugte Testzeilen mit konkreten Regelreferenzen
 */
public record RulePreview(
        List<Rule> rules,
        List<RuleTest> tests
) {
}
