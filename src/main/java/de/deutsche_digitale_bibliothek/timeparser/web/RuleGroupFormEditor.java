package de.deutsche_digitale_bibliothek.timeparser.web;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Erzeugt und verändert das verschachtelte Formularmodell für Regelgruppen.
 *
 * <p>Alle Methoden arbeiten direkt auf dem übergebenen Formular und greifen
 * nicht auf die Datenbank zu. Listenindizes gehören zum aktuellen Formular,
 * nicht zu gespeicherten IDs. Ungültige Indizes werden bei Zeilenaktionen
 * ignoriert, damit eine überholte Aktion keinen Indexfehler verursacht.</p>
 *
 * <p>Kopien erhalten keine Regel- oder Test-IDs, damit sie beim späteren
 * Speichern als neue Zeilen behandelt werden. Die Entfernung einer Zeile über
 * die Oberfläche lässt mindestens eine Regel bzw. einen Test übrig. Die
 * abschließende Bereinigung darf dagegen alle vollständig leeren neuen Zeilen
 * entfernen; anschließend prüft die fachliche Validierung die Mindestanzahl.</p>
 */
@Component
public class RuleGroupFormEditor {

    /**
     * Erstellt einen leeren Entwurf mit einer Regel und einem Test.
     *
     * @return neues, noch nicht gespeichertes Formular
     */
    public RuleGroupForm newGroupForm() {
        RuleGroupForm form = new RuleGroupForm();
        form.getRules().add(newRuleVariant());
        return form;
    }

    /**
     * Hängt eine neue Regel mit einer leeren Testzeile an.
     *
     * @param form direkt zu änderndes Formular
     */
    public void addRule(RuleGroupForm form) {
        form.getRules().add(newRuleVariant());
    }

    /**
     * Fügt eine inhaltliche Kopie samt Tests unmittelbar hinter der Quelle ein.
     *
     * @param form zu änderndes Formular
     * @param ruleIndex nullbasierter Index der Quellregel; ungültig bedeutet keine Änderung
     */
    public void duplicateRule(RuleGroupForm form, int ruleIndex) {
        if (!isValidIndex(ruleIndex, form.getRules())) {
            return;
        }
        form.getRules().add(ruleIndex + 1, copyOf(form.getRules().get(ruleIndex)));
    }

    /**
     * Entfernt eine Regel, wenn danach mindestens eine Regel verbleibt.
     *
     * @param form zu änderndes Formular
     * @param ruleIndex nullbasierter Index der zu entfernenden Regel
     */
    public void removeRule(RuleGroupForm form, int ruleIndex) {
        if (form.getRules().size() > 1 && isValidIndex(ruleIndex, form.getRules())) {
            form.getRules().remove(ruleIndex);
        }
    }

    /**
     * Ergänzt einen leeren Test an einer vorhandenen Formularregel.
     *
     * @param form zu änderndes Formular
     * @param ruleIndex nullbasierter Index der Zielregel
     */
    public void addTest(RuleGroupForm form, int ruleIndex) {
        if (isValidIndex(ruleIndex, form.getRules())) {
            form.getRules().get(ruleIndex).getTests().add(new RuleGroupForm.TestForm());
        }
    }

    /**
     * Fügt eine Kopie ohne Test-ID unmittelbar hinter dem Quelltest ein.
     *
     * @param form zu änderndes Formular
     * @param ruleIndex nullbasierter Index der Zielregel
     * @param testIndex nullbasierter Index des Quelltests
     */
    public void duplicateTest(RuleGroupForm form, int ruleIndex, int testIndex) {
        if (!isValidIndex(ruleIndex, form.getRules())) {
            return;
        }
        List<RuleGroupForm.TestForm> tests = form.getRules().get(ruleIndex).getTests();
        if (isValidIndex(testIndex, tests)) {
            tests.add(testIndex + 1, copyOf(tests.get(testIndex)));
        }
    }

    /**
     * Entfernt einen Test, wenn seine Regel mindestens einen Test behält.
     *
     * @param form zu änderndes Formular
     * @param ruleIndex nullbasierter Index der Regel
     * @param testIndex nullbasierter Index des Tests
     */
    public void removeTest(RuleGroupForm form, int ruleIndex, int testIndex) {
        if (!isValidIndex(ruleIndex, form.getRules())) {
            return;
        }
        List<RuleGroupForm.TestForm> tests = form.getRules().get(ruleIndex).getTests();
        if (tests.size() > 1 && isValidIndex(testIndex, tests)) {
            tests.remove(testIndex);
        }
    }

    /**
     * Entfernt zunächst vollständig leere neue Tests und danach leere neue
     * Regeln. Zeilen mit einer ID bleiben auch bei leeren Nutzfeldern erhalten,
     * damit bestehende Daten nicht durch bloßes Leeren automatisch verschwinden.
     * Teilweise ausgefüllte Zeilen bleiben für konkrete Pflichtfeldfehler stehen.
     *
     * @param form vor dem Speichern oder der Vorschau zu bereinigendes Formular
     */
    public void removeBlankRows(RuleGroupForm form) {
        for (RuleGroupForm.RuleVariantForm rule : form.getRules()) {
            rule.getTests().removeIf(this::isBlank);
        }
        form.getRules().removeIf(this::isBlank);
    }

    private RuleGroupForm.RuleVariantForm newRuleVariant() {
        RuleGroupForm.RuleVariantForm rule = new RuleGroupForm.RuleVariantForm();
        rule.getTests().add(new RuleGroupForm.TestForm());
        return rule;
    }

    /** Kopiert auch die Testobjekte, damit Änderungen der Kopie das Original nicht verändern. */
    private RuleGroupForm.RuleVariantForm copyOf(RuleGroupForm.RuleVariantForm source) {
        RuleGroupForm.RuleVariantForm copy = new RuleGroupForm.RuleVariantForm();
        copy.setInputMask(source.getInputMask());
        copy.setInputPattern(source.getInputPattern());
        copy.setOutputMask(source.getOutputMask());
        copy.setOutputPattern(source.getOutputPattern());
        source.getTests().stream().map(this::copyOf).forEach(copy.getTests()::add);
        return copy;
    }

    private RuleGroupForm.TestForm copyOf(RuleGroupForm.TestForm source) {
        RuleGroupForm.TestForm copy = new RuleGroupForm.TestForm();
        copy.setInput(source.getInput());
        copy.setTokenized(source.getTokenized());
        copy.setOutput(source.getOutput());
        copy.setTimespan(source.getTimespan());
        return copy;
    }

    private boolean isBlank(RuleGroupForm.RuleVariantForm rule) {
        return isBlank(rule.getId())
                && isBlank(rule.getInputMask())
                && isBlank(rule.getInputPattern())
                && isBlank(rule.getOutputMask())
                && isBlank(rule.getOutputPattern())
                && rule.getTests().isEmpty();
    }

    private boolean isBlank(RuleGroupForm.TestForm test) {
        return isBlank(test.getId())
                && isBlank(test.getInput())
                && isBlank(test.getTokenized())
                && isBlank(test.getOutput())
                && isBlank(test.getTimespan());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isValidIndex(int index, List<?> values) {
        return index >= 0 && index < values.size();
    }
}
