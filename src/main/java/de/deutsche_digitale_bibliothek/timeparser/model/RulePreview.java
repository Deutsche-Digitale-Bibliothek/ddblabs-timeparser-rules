package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;

/**
 * Gemeinsam erzeugte Vorschau einer Regelgruppe.
 */
public record RulePreview(
        List<Rule> rules,
        List<RuleTest> tests
) {
}
