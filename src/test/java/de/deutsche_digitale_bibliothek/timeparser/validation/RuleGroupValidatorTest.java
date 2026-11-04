package de.deutsche_digitale_bibliothek.timeparser.validation;

import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleGroupValidatorTest {

    @Test
    void keepsRuleFieldsDistinctWhenValuesContainTheFormerSeparator() {
        RuleGroupForm form = form();
        form.getRules().add(rule("x\u001fx", "x"));
        form.getRules().add(rule("x", "x\u001fx"));

        assertThat(RuleGroupValidator.validate(form)).noneMatch(issue -> issue.code().equals("rule.duplicate"));

        form.getRules().add(rule("x\u001fx", "x"));
        assertThat(RuleGroupValidator.validate(form)).filteredOn(issue -> issue.code().equals("rule.duplicate"))
                .singleElement().satisfies(issue -> assertThat(issue.field()).isEqualTo("rules[2].inputMask"));
    }

    @Test
    void keepsTestFieldsDistinctWhenValuesContainTheFormerSeparator() {
        RuleGroupForm form = form();
        var rule = rule("x", "x");
        rule.getTests().clear();
        rule.getTests().add(test("x\u001fx", "x"));
        rule.getTests().add(test("x", "x\u001fx"));
        form.getRules().add(rule);

        assertThat(RuleGroupValidator.validate(form)).noneMatch(issue -> issue.code().equals("test.duplicate"));

        rule.getTests().add(test("x\u001fx", "x"));
        assertThat(RuleGroupValidator.validate(form)).filteredOn(issue -> issue.code().equals("test.duplicate"))
                .singleElement().satisfies(issue -> assertThat(issue.field()).isEqualTo("rules[0].tests[2].input"));
    }

    private RuleGroupForm form() {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Feldgrenzen");
        return form;
    }

    private RuleGroupForm.RuleVariantForm rule(String input, String output) {
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask(input);
        rule.setInputPattern(input);
        rule.setOutputMask(output);
        rule.setOutputPattern(output);
        rule.getTests().add(test(input, output));
        return rule;
    }

    private RuleGroupForm.TestForm test(String input, String output) {
        var test = new RuleGroupForm.TestForm();
        test.setInput(input);
        test.setTokenized("x");
        test.setOutput(output);
        return test;
    }
}
