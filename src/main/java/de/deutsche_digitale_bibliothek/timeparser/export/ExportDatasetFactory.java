package de.deutsche_digitale_bibliothek.timeparser.export;

import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vereinheitlicht expandierte Regeln und Tests vor sämtlichen CSV-Exportwegen.
 *
 * <p>Als Regeldublette gilt die vollständige Kombination aus Eingabemaske,
 * Eingabemuster, Ausgabemaske und Ausgabemuster. Die zuerst übergebene Regel
 * bleibt mit ihrer ID erhalten. Alle Tests einer entfernten Dublette werden auf
 * diese kanonische ID umgehängt, bevor gleiche Testwerte entfernt werden.</p>
 *
 * <p>Tests werden je kanonischer Regel dedupliziert. Identische Beispiele an zwei
 * unterschiedlichen Regeln bleiben erhalten, damit deren getrennte Testabdeckung
 * nicht verloren geht. Gleiche Eingabemasken mit unterschiedlichen Transformationen
 * bleiben ebenfalls erhalten; der entsprechende Konflikthinweis ist Aufgabe der
 * Plausibilitätsprüfung im Repository.</p>
 *
 * <p>Diese Klasse verändert keine Eingabe-Beans und führt weder Validierung noch
 * Parser-Verarbeitung aus. Die Ausgabe enthält neu erzeugte Regel- und Test-Beans.</p>
 */
public final class ExportDatasetFactory {

    private ExportDatasetFactory() {
    }

    /**
     * Erstellt einen gemeinsamen Exportbestand und erhält die Reihenfolge der ersten Vorkommen.
     *
     * <p>Ein Test ohne zuordenbare Regel wird ausgelassen und mit seiner Test- und
     * Regel-ID in {@link ExportDataset#warnings()} gemeldet. Die IDs übernommener
     * Tests bleiben unverändert; nur ihre Regelreferenz kann auf eine kanonische
     * Regel umgeschrieben werden.</p>
     *
     * @param rules expandierte Regeln mit eindeutigen IDs; ihre Reihenfolge bestimmt
     *              den Vertreter jeder Gruppe identischer Regelwerte
     * @param tests expandierte Tests in gewünschter Exportreihenfolge
     * @return kopierte, deduplizierte Zeilen samt Meldungen zu verwaisten Referenzen
     */
    public static ExportDataset create(List<Rule> rules, List<RuleTest> tests) {
        // LinkedHashMap erhält den ersten Vertreter und die angelieferte Regelreihenfolge.
        Map<RuleValues, Rule> canonicalRules = new LinkedHashMap<>();
        // Auch entfernte Dubletten benötigen einen Eintrag: Ihre Tests werden umgehängt.
        Map<String, String> canonicalIds = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Rule rule : rules) {
            Rule canonical = canonicalRules.computeIfAbsent(new RuleValues(rule.getInputMask(), rule.getInputPattern(),
                    rule.getOutputMask(), rule.getOutputPattern()), ignored -> copy(rule));
            canonicalIds.put(rule.getId(), canonical.getId());
        }

        List<RuleTest> exportedTests = new ArrayList<>();
        // Der Regelbezug gehört zur Identität eines Tests, nicht nur seine vier Werte.
        Set<TestValues> seenTests = new HashSet<>();
        for (RuleTest test : tests) {
            String ruleId = canonicalIds.get(test.getRuleId());
            if (ruleId == null) {
                warnings.add("Test " + test.getId() + " verweist auf die nicht exportierte Regel "
                        + test.getRuleId() + " und wurde ausgelassen.");
                continue;
            }
            if (seenTests.add(new TestValues(ruleId, test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan()))) {
                exportedTests.add(new RuleTest(test.getId(), ruleId, test.getInput(), test.getTokenized(),
                        test.getOutput(), test.getTimespan()));
            }
        }
        return new ExportDataset(new ArrayList<>(canonicalRules.values()), exportedTests, warnings);
    }

    /** Trennt die später veränderliche Export-Bean von der angelieferten Regel. */
    private static Rule copy(Rule rule) {
        return new Rule(rule.getId(), rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern());
    }

    /** Fachliche Regelidentität ohne technische ID; Leerzeichen bleiben Bestandteil der Werte. */
    private record RuleValues(String inputMask, String inputPattern, String outputMask, String outputPattern) {
    }

    /** Testidentität nach der Zuordnung zur kanonischen Regel, unabhängig von der Test-ID. */
    private record TestValues(String ruleId, String input, String tokenized, String output, String timespan) {
    }
}
