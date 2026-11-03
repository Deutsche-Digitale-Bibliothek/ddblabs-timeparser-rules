package de.deutsche_digitale_bibliothek.timeparser.model;

/**
 * Kompakte Darstellung gespeicherter Gruppenvorlagen für die Listenansicht.
 *
 * <p>Die Zähler beziehen sich auf gespeicherte Regel- und Testvorlagen. Eine
 * Platzhalterexpansion kann mehr konkrete Zeilen erzeugen; die Zähler sind
 * daher keine Export- oder Testabdeckungsstatistik.</p>
 *
 * @param id technische Gruppen-ID für Detail- und Änderungsrouten
 * @param name angezeigter Gruppenname
 * @param description ergänzende fachliche Beschreibung
 * @param ruleCount Anzahl gespeicherter Regelvarianten der Gruppe
 * @param testCount Anzahl gespeicherter Testvorlagen an diesen Varianten
 */
public record RuleGroupSummary(
        long id,
        String name,
        String description,
        long ruleCount,
        long testCount
) {
}
