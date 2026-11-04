package de.deutsche_digitale_bibliothek.timeparser.validation;

import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Statische Grundprüfungen für Regelgruppen und bereits expandierte Testwerte.
 *
 * <p>Die Klasse trennt zwei Ebenen: {@link #validate(RuleGroupForm)} untersucht
 * das bearbeitbare Formular einschließlich seiner noch nicht aufgelösten
 * Platzhalter. {@link #validateTest(String, String, String, String, String)}
 * prüft dagegen einen konkreten Test nach der Platzhalterexpansion. Die Auswahl
 * und Expansion der Varianten sowie Vergleiche mit anderen gespeicherten
 * Gruppen übernimmt das Repository.</p>
 *
 * <p>Alle Ergebnisse sind Feldbefunde vom Typ {@link Issue}. Hier entstehen
 * Fehler, die der Formularadapter beim Speichern als Feldfehler behandelt.
 * Unverbindliche Hinweise, etwa fehlende Tests einzelner erzeugter Varianten,
 * werden separat im Repository ermittelt. Keine dieser Prüfungen führt einen
 * Testfall im Timeparser aus oder vergleicht dessen tatsächliche Ausgabe mit
 * dem erwarteten Zeitraum.</p>
 */
public final class RuleGroupValidator {

    private RuleGroupValidator() {
    }

    /**
     * Prüft Pflichtfelder, die Mindeststruktur und Dubletten innerhalb eines Formulars.
     *
     * <p>Erforderlich sind ein Gruppenname, mindestens eine Regel, deren vier
     * Masken-/Musterfelder sowie mindestens ein Test je Regel. Ein Test benötigt
     * Eingabe, tokenisierte Eingabe und Ausgabe; sein Zeitraum ist optional.
     * Identische Regeln werden innerhalb der Gruppe erkannt. Identische Tests
     * werden nur innerhalb derselben Regelvariante beanstandet, weil dasselbe
     * Beispiel für verschiedene Regeln fachlich sinnvoll sein kann.</p>
     *
     * <p>Die Prüfung verändert das Formular nicht und expandiert keine
     * {@code ~name}-Platzhalter. Die ausgegebenen Feldpfade sind bereits relativ
     * zum vollständigen Gruppenformular, z. B.
     * {@code rules[0].tests[1].input}.</p>
     *
     * @param form zu prüfendes Gruppenformular; darf nicht {@code null} sein
     * @return neue, veränderbare Liste der Befunde in Formularreihenfolge;
     *         leer, wenn diese Grundprüfungen keinen Fehler finden
     */
    public static List<Issue> validate(RuleGroupForm form) {
        List<Issue> issues = new ArrayList<>();
        required(issues, "name", form.getName(), "Der Name ist erforderlich.");
        if (form.getRules().isEmpty()) {
            issues.add(new Issue("rules", "rules.required", "Mindestens eine Regel ist erforderlich."));
        }
        Set<List<String>> rules = new HashSet<>();
        for (int r = 0; r < form.getRules().size(); r++) {
            Set<List<String>> tests = new HashSet<>();
            var rule = form.getRules().get(r);
            String prefix = "rules[" + r + "].";
            required(issues, prefix + "inputMask", rule.getInputMask(), "Die Eingabemaske ist erforderlich.");
            required(issues, prefix + "inputPattern", rule.getInputPattern(), "Das Eingabemuster ist erforderlich.");
            required(issues, prefix + "outputMask", rule.getOutputMask(), "Die Ausgabemaske ist erforderlich.");
            required(issues, prefix + "outputPattern", rule.getOutputPattern(), "Das Ausgabemuster ist erforderlich.");
            if (!rules.add(signature(rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern()))) {
                issues.add(new Issue(prefix + "inputMask", "rule.duplicate", "Diese Regel ist bereits in der Regelgruppe vorhanden. Die Kopie muss vor dem Speichern angepasst werden."));
            }
            if (rule.getTests().isEmpty()) {
                issues.add(new Issue(prefix + "tests", "tests.required", "Mindestens ein Test ist erforderlich."));
            }
            for (int t = 0; t < rule.getTests().size(); t++) {
                var test = rule.getTests().get(t);
                String testPrefix = prefix + "tests[" + t + "].";
                required(issues, testPrefix + "input", test.getInput(), "Die Eingabe ist erforderlich.");
                required(issues, testPrefix + "tokenized", test.getTokenized(), "Der tokenisierte Wert ist erforderlich.");
                required(issues, testPrefix + "output", test.getOutput(), "Die Ausgabe ist erforderlich.");
                if (!tests.add(signature(test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan()))) {
                    issues.add(new Issue(testPrefix + "input", "test.duplicate", "Dieser Test ist bereits vorhanden. Die Kopie muss vor dem Speichern angepasst werden."));
                }
            }
        }
        return issues;
    }

    /**
     * Prüft konkrete Testwerte gegen ihre effektiven Masken und einen optionalen ISO-Zeitraum.
     *
     * <p>Die Platzhalter müssen vorher aufgelöst sein. Die Eingabemaske wird mit
     * der bereits tokenisierten Eingabe verglichen. Als Ausgabemaske ist die von
     * {@link RulePatternValidator#outputTestMask(String, String, String, String)}
     * abgeleitete Prüfmaske zu übergeben: Ein Monatswert wird beispielsweise als
     * zwei Ziffern ausgegeben, obwohl seine Eingabemaske {@code MM} enthält.</p>
     *
     * <p>Die Maskenprüfung verwendet UTF-16-Längen und akzeptiert für {@code #}
     * ausschließlich ASCII-Ziffern {@code 0} bis {@code 9}. Alle anderen
     * Maskenzeichen müssen exakt übereinstimmen. Fehlen Maske oder Wert oder sind
     * sie leer, wird dieser Vergleich ausgelassen; Pflichtfelder prüft
     * {@link #validate(RuleGroupForm)}. Äußere CSV-Leerzeichen werden unabhängig
     * davon gemeldet.</p>
     *
     * <p>Ein nicht leerer Zeitraum muss genau zwei durch {@code /} getrennte,
     * von {@link LocalDate#parse(CharSequence)} akzeptierte ISO-Daten enthalten.
     * Damit gelten die Grenzen von {@link LocalDate}; das Startdatum darf nicht
     * nach dem Enddatum liegen. Ob der Zeitraum aus der erwarteten Ausgabe
     * tatsächlich folgt, wird nicht geprüft.</p>
     *
     * @param inputMask expandierte Maske der tokenisierten Eingabe; {@code null} überspringt den Vergleich
     * @param outputMask effektive Prüfmaske der Ausgabe; {@code null} überspringt den Vergleich
     * @param tokenized expandierte tokenisierte Testeingabe
     * @param output expandierte erwartete Ausgabe
     * @param timespan optionaler ISO-Zeitraum, z. B. {@code 1950-01-01/1950-12-31}
     * @return neue, veränderbare Befundliste mit testrelativen Feldern
     *         {@code tokenized}, {@code output} und {@code timespan}
     */
    public static List<Issue> validateTest(String inputMask, String outputMask,
                                          String tokenized, String output, String timespan) {
        List<Issue> issues = new ArrayList<>();
        issues.addAll(validateCsvWhitespace(new String[]{"tokenized", "output", "timespan"}, tokenized, output, timespan));
        matches(issues, "tokenized", inputMask, tokenized, "Der tokenisierte Testwert", "Eingabemaske");
        matches(issues, "output", outputMask, output, "Die erwartete Ausgabe", "Ausgabemaske");
        if (timespan != null && !timespan.isBlank()) {
            String[] dates = timespan.split("/", -1);
            if (dates.length != 2) {
                issues.add(new Issue("timespan", "timespan.format", "Der Zeitraum muss zwei ISO-Daten im Format Start/Ende enthalten."));
            } else {
                try {
                    LocalDate start = LocalDate.parse(dates[0]);
                    LocalDate end = LocalDate.parse(dates[1]);
                    if (start.isAfter(end)) {
                        issues.add(new Issue("timespan", "timespan.order", "Der Zeitraum darf nicht vor seinem Start enden."));
                    }
                } catch (DateTimeParseException exception) {
                    issues.add(new Issue("timespan", "timespan.format", "Der Zeitraum muss ISO-Daten im Format YYYY-MM-DD/YYYY-MM-DD verwenden."));
                }
            }
        }
        return issues;
    }

    /**
     * Meldet äußere Zeichen, die beim CSV-Import des Timeparsers verloren gehen würden.
     *
     * <p>Der Vergleich folgt ausdrücklich {@link String#trim()} und damit den
     * Zeichen bis einschließlich {@code U+0020}, nicht allen Unicode-Leerzeichen.
     * Werte werden weder verändert noch normalisiert; {@code null}-Werte werden
     * ausgelassen. Die Feldnamen und Werte müssen positionsgleich übergeben
     * werden, da der jeweilige Feldname unverändert in den Befund eingeht.</p>
     *
     * @param fields Feldnamen oder bereits zusammengesetzte Feldpfade; nicht {@code null}
     * @param values zugehörige Werte; mindestens so viele Einträge wie {@code fields}
     * @return neue, veränderbare Liste mit dem Code {@code csv.whitespace} je betroffenem Feld
     */
    public static List<Issue> validateCsvWhitespace(String[] fields, String... values) {
        List<Issue> issues = new ArrayList<>();
        for (int i = 0; i < fields.length; i++) {
            if (values[i] != null && !values[i].equals(values[i].trim())) {
                issues.add(new Issue(fields[i], "csv.whitespace",
                        "Führende oder abschließende Leerzeichen werden vom Timeparser beim CSV-Import entfernt."));
            }
        }
        return issues;
    }

    /**
     * Ergänzt höchstens einen Maskenfehler je Wert. Nach einer Längenabweichung
     * ist ein Positionsvergleich nicht sinnvoll; bei gleicher Länge genügt die
     * erste abweichende Position als konkrete Korrekturhilfe.
     */
    private static void matches(List<Issue> issues, String field, String mask, String value,
                                 String label, String maskLabel) {
        if (mask == null || value == null || mask.isBlank() || value.isBlank()) {
            return;
        }
        if (mask.length() != value.length()) {
            issues.add(new Issue(field, "length.mismatch", label + " muss zur " + maskLabel + " gleich lang sein."
                    + monthMaskHint(field, mask, value)));
            return;
        }
        for (int i = 0; i < mask.length(); i++) {
            char expected = mask.charAt(i);
            char actual = value.charAt(i);
            if (expected == '#' ? actual < '0' || actual > '9' : expected != actual) {
                issues.add(new Issue(field, "mask.mismatch", label + " passt an Position " + (i + 1) + " nicht zur " + maskLabel + "."
                        + monthMaskHint(field, mask, value)));
                return;
            }
        }
    }

    /** Erläutert Monatsmarker nur bei fehlerhaften tokenisierten Eingaben, nicht bei Ausgabewerten. */
    private static String monthMaskHint(String field, String mask, String value) {
        if (!"tokenized".equals(field)) {
            return "";
        }
        if (mask.contains("MM")) {
            return " MM in der Eingabemaske steht für einen tokenisierten Monatsnamen. Im Feld Tokenisiert steht dafür MM, z. B. MM 2010 für März 2010. Eine Monatszahl wie 03 benötigt stattdessen ## in der Eingabemaske.";
        }
        if (value.contains("MM")) {
            return " MM im tokenisierten Testwert steht für einen Monatsnamen und benötigt MM in der Eingabemaske. ## in der Eingabemaske erwartet dagegen zwei Ziffern. MM im Muster ändert diese Bedeutung nicht.";
        }
        return "";
    }

    private static void required(List<Issue> issues, String field, String value, String message) {
        if (value == null || value.isBlank()) {
            issues.add(new Issue(field, "field.required", message));
        }
    }

    /**
     * Vergleicht Feldtupel ohne Trimmung; {@code null} wird wie ein leerer Wert
     * behandelt. Die Liste erhält Feldgrenzen auch dann, wenn ein eingegebener
     * Wert Steuerzeichen oder andere mögliche Trennzeichen enthält.
     */
    private static List<String> signature(String... values) {
        return java.util.Arrays.stream(values).map(value -> value == null ? "" : value).toList();
    }
}
