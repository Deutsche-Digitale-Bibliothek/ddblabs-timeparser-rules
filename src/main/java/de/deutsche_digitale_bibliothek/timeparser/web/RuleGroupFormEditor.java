package de.deutsche_digitale_bibliothek.timeparser.web;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Erzeugt und verändert das verschachtelte Formularmodell für Regelgruppen.
 */
@Component
public class RuleGroupFormEditor {

    public RuleGroupForm newGroupForm() {
        RuleGroupForm form = new RuleGroupForm();
        form.getRules().add(newRuleVariant());
        return form;
    }

    public void addRule(RuleGroupForm form) {
        form.getRules().add(newRuleVariant());
    }

    public void removeRule(RuleGroupForm form, int ruleIndex) {
        if (form.getRules().size() > 1 && isValidIndex(ruleIndex, form.getRules())) {
            form.getRules().remove(ruleIndex);
        }
    }

    public void addTest(RuleGroupForm form, int ruleIndex) {
        if (isValidIndex(ruleIndex, form.getRules())) {
            form.getRules().get(ruleIndex).getTests().add(new RuleGroupForm.TestForm());
        }
    }

    public void removeTest(RuleGroupForm form, int ruleIndex, int testIndex) {
        if (!isValidIndex(ruleIndex, form.getRules())) {
            return;
        }
        List<RuleGroupForm.TestForm> tests = form.getRules().get(ruleIndex).getTests();
        if (tests.size() > 1 && isValidIndex(testIndex, tests)) {
            tests.remove(testIndex);
        }
    }

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
