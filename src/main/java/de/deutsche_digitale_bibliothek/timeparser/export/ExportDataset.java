package de.deutsche_digitale_bibliothek.timeparser.export;

import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;

import java.util.List;

/**
 * Aufbereitete CSV-Zeilen für einen zusammengehörigen Regel- und Testexport.
 *
 * <p>{@link ExportDatasetFactory} führt exakte Regeldubletten zusammen, ordnet ihre
 * Tests der verbleibenden Regel-ID zu und liefert diesen Datenbestand an die
 * Serialisierung. Ein direkt erzeugtes {@code ExportDataset} führt diese Schritte
 * selbst nicht aus und prüft auch keine Referenzen.</p>
 *
 * <p>Die Listen werden strukturell unveränderlich kopiert. Ihre Elemente
 * {@link Rule} und {@link RuleTest} bleiben jedoch veränderliche Java-Beans;
 * der Datensatz ist daher keine tiefe, unveränderliche Kopie beliebiger Eingaben.</p>
 *
 * @param rules ausgewählte Regeln in Exportreihenfolge
 * @param tests ausgewählte Tests mit den für diesen Export gültigen Regel-IDs
 * @param warnings Meldungen der Aufbereitung, etwa zu ausgelassenen verwaisten Tests;
 *                 die vollständigen Plausibilitätsbefunde stehen im ExportSnapshot
 * @see de.deutsche_digitale_bibliothek.timeparser.model.ExportSnapshot
 */
public record ExportDataset(List<Rule> rules, List<RuleTest> tests, List<String> warnings) {

    /**
     * Übernimmt den Inhalt in unveränderliche Listen, ohne die enthaltenen Beans zu kopieren.
     *
     * @param rules zu kopierende Regelliste
     * @param tests zu kopierende Testliste
     * @param warnings zu kopierende Meldungsliste
     * @throws NullPointerException wenn eine Liste oder eines ihrer Elemente {@code null} ist
     */
    public ExportDataset {
        rules = List.copyOf(rules);
        tests = List.copyOf(tests);
        warnings = List.copyOf(warnings);
    }

    /**
     * Erzeugt einen Datensatz ohne Meldungen der Exportaufbereitung.
     *
     * @param rules bereits ausgewählte Regeln
     * @param tests bereits ausgewählte Tests und ihre Regelreferenzen
     */
    public ExportDataset(List<Rule> rules, List<RuleTest> tests) {
        this(rules, tests, List.of());
    }
}
