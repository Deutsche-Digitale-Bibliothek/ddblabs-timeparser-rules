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
 * <p>IDs werden angezeigt und als Hidden Fields übertragen, bleiben für Nutzer
 * aber nicht bearbeitbar. Neue IDs vergibt das Repository beim Speichern.</p>
 */
@Getter
@Setter
public class RuleGroupForm {

    private Long id;

    @NotBlank
    private String name = "";

    private String description = "";

    private List<@Valid RuleVariantForm> rules = new ArrayList<>();

    public List<RuleVariantForm> getRules() {
        if (rules == null) {
            rules = new ArrayList<>();
        }
        return rules;
    }

    public void setRules(List<RuleVariantForm> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    /**
     * Bearbeitbare Vorlage einer Regelvariante innerhalb einer Regelgruppe.
     */
    @Getter
    @Setter
    public static class RuleVariantForm {

        private String id = "";

        private String inputMask = "";

        private String inputPattern = "";

        private String outputMask = "";

        private String outputPattern = "";

        private List<@Valid TestForm> tests = new ArrayList<>();

        public List<TestForm> getTests() {
            if (tests == null) {
                tests = new ArrayList<>();
            }
            return tests;
        }

        public void setTests(List<TestForm> tests) {
            this.tests = tests == null ? new ArrayList<>() : tests;
        }
    }

    /**
     * Bearbeitbare Testvorlage zu einer Regelvariante.
     */
    @Getter
    @Setter
    public static class TestForm {

        private String id = "";

        private String input = "";

        private String tokenized = "";

        private String output = "";

        private String timespan = "";
    }
}
