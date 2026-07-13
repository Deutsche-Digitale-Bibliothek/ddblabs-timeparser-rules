package de.deutsche_digitale_bibliothek.timeparser.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleGroupFormEditorTest {

    private final RuleGroupFormEditor editor = new RuleGroupFormEditor();

    @Test
    void createsGroupWithOneRuleAndOneTest() {
        RuleGroupForm form = editor.newGroupForm();

        assertThat(form.getRules()).hasSize(1);
        assertThat(form.getRules().getFirst().getTests()).hasSize(1);
    }

    @Test
    void removesOnlyCompletelyBlankRows() {
        RuleGroupForm form = editor.newGroupForm();
        RuleGroupForm.RuleVariantForm existingRule = form.getRules().getFirst();
        existingRule.setInputMask("###");
        existingRule.setInputPattern("JJJ");
        existingRule.setOutputMask("####");
        existingRule.setOutputPattern("JJJJ");
        existingRule.getTests().getFirst().setInput("800");
        existingRule.getTests().getFirst().setTokenized("800");
        existingRule.getTests().getFirst().setOutput("0800");
        editor.addRule(form);

        editor.removeBlankRows(form);

        assertThat(form.getRules()).containsExactly(existingRule);
        assertThat(existingRule.getTests()).hasSize(1);
    }
}
