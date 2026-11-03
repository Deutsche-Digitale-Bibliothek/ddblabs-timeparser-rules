package de.deutsche_digitale_bibliothek.timeparser.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Formularmodell der Regelgruppen-Detailseite.
 *
 * <p>Eine Gruppe enthält geordnete Regelvorlagen mit jeweils geordneten
 * Testvorlagen. Die Zeichenketten dürfen Platzhalter wie {@code ~approx}
 * enthalten; sie sind deshalb nicht mit den bereits expandierten Zeilen des
 * CSV-Exports gleichzusetzen.</p>
 *
 * <p>IDs werden als Hidden Fields übertragen und erhalten die Zuordnung beim
 * Bearbeiten. Das ist eine Eigenschaft der Oberfläche, keine Vertrauensgrenze:
 * Request-Daten bleiben überprüfbar, und die Controller setzen die Gruppen-ID
 * aus dem URL-Pfad. Neue Regel- und Test-IDs vergibt das Repository erst beim
 * Speichern.</p>
 *
 * <p>Die Listen-Getter geben die bearbeitbaren Listen selbst zurück und ersetzen
 * {@code null} durch leere Listen. Die fachlichen Pflichtfelder und Beziehungen
 * prüft der gemeinsame Formvalidator; {@link Valid} leitet die Bean-Validation
 * in die verschachtelten Formularobjekte weiter.</p>
 */
@Getter
@Setter
public class RuleGroupForm {

    /** Gespeicherte Gruppen-ID oder {@code null} für einen neuen Entwurf. */
    private Long id;

    @NotBlank
    private String name = "";

    private String description = "";

    /** Regelreihenfolge der Oberfläche; Listenindizes bilden auch die Fehlerpfade. */
    private List<@Valid RuleVariantForm> rules = new ArrayList<>();

    /**
     * Liefert die veränderbare Regelliste und normalisiert einen fehlenden Wert.
     *
     * @return stets eine nicht-null Liste, keine defensive Kopie
     */
    public List<RuleVariantForm> getRules() {
        if (rules == null) {
            rules = new ArrayList<>();
        }
        return rules;
    }

    /**
     * Übernimmt die gebundene Liste ohne Kopie; {@code null} bedeutet leer.
     *
     * @param rules neue Regelliste oder {@code null}
     */
    public void setRules(List<RuleVariantForm> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    /**
     * Bearbeitbare Vorlage einer Regelvariante innerhalb einer Regelgruppe.
     * Ein Platzhalter kann daraus mehrere konkrete Exportregeln erzeugen.
     * Eingabe- und Ausgabemasken beschreiben Zeichenkategorien, während die
     * zugehörigen Muster die Variablennamen und unveränderten Literale festlegen.
     */
    @Getter
    @Setter
    public static class RuleVariantForm {

        /** Bisherige Regel-ID; eine Kopie oder neue Zeile beginnt ohne ID. */
        private String id = "";

        /** Maske der erwarteten tokenisierten Eingabe, z. B. {@code MM ####}. */
        private String inputMask = "";

        /** Variablendefinition passend zur Eingabemaske, z. B. {@code MM JJJJ}. */
        private String inputPattern = "";

        /** Zeichenkategorien der erzeugten Parser-Eingabe, z. B. {@code ####-##}. */
        private String outputMask = "";

        /** Verwendung der Eingabevariablen in der Ausgabe, z. B. {@code JJJJ-MM}. */
        private String outputPattern = "";

        private List<@Valid TestForm> tests = new ArrayList<>();

        /**
         * Liefert die veränderbare Testliste und normalisiert {@code null}.
         *
         * @return niemals {@code null}; Änderungen wirken direkt auf das Formular
         */
        public List<TestForm> getTests() {
            if (tests == null) {
                tests = new ArrayList<>();
            }
            return tests;
        }

        /**
         * Übernimmt die Testliste; {@code null} wird als leere Liste behandelt.
         *
         * @param tests neue Testvorlagen oder {@code null}
         */
        public void setTests(List<TestForm> tests) {
            this.tests = tests == null ? new ArrayList<>() : tests;
        }
    }

    /**
     * Bearbeitbare Testvorlage zu einer Regelvariante.
     * Die Werte sind Erwartungen für einen späteren Parserlauf. Der Editor
     * prüft ihre statische Plausibilität, führt den Test aber nicht aus.
     */
    @Getter
    @Setter
    public static class TestForm {

        private String id = "";

        /** Ursprüngliches Beispiel vor der Monats-/Wochentags-Tokenisierung. */
        private String input = "";

        /** Erwartete tokenisierte Form; passende {@code #}-Stellen können aus der Eingabe stammen. */
        private String tokenized = "";

        /** Erwartete transformierte Parser-Eingabe, noch kein endgültiger Zeitraum. */
        private String output = "";

        /** Optionaler erwarteter ISO-Datumsbereich im Format {@code Start/Ende}. */
        private String timespan = "";
    }
}
