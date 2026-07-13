package de.deutsche_digitale_bibliothek.timeparser.model;

/**
 * Kompakte Darstellung einer Regelgruppe für die Listenansicht.
 */
public record RuleGroupSummary(
        long id,
        String name,
        String description,
        long ruleCount,
        long testCount
) {
}
