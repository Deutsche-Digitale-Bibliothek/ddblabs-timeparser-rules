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

    @Test
    void duplicatesRuleAsIndependentDraftWithoutTechnicalIds() {
        RuleGroupForm form = editor.newGroupForm();
        RuleGroupForm.RuleVariantForm original = form.getRules().getFirst();
        original.setId("R1");
        original.setInputMask("###");
        original.setInputPattern("JJJ");
        original.setOutputMask("####");
        original.setOutputPattern("JJJJ");
        original.getTests().getFirst().setId("T1");
        original.getTests().getFirst().setInput("800");

        editor.duplicateRule(form, 0);

        RuleGroupForm.RuleVariantForm copy = form.getRules().get(1);
        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getId()).isEmpty();
        assertThat(copy.getInputMask()).isEqualTo(original.getInputMask());
        assertThat(copy.getTests().getFirst()).isNotSameAs(original.getTests().getFirst());
        assertThat(copy.getTests().getFirst().getId()).isEmpty();
        assertThat(copy.getTests().getFirst().getInput()).isEqualTo("800");

        editor.addTest(form, 1);
        assertThat(copy.getTests()).hasSize(2);
    }

    @Test
    void duplicatesTestAsIndependentDraftWithoutTechnicalId() {
        RuleGroupForm form = editor.newGroupForm();
        RuleGroupForm.TestForm original = form.getRules().getFirst().getTests().getFirst();
        original.setId("T1");
        original.setInput("800");
        original.setTokenized("800");
        original.setOutput("0800");

        editor.duplicateTest(form, 0, 0);

        RuleGroupForm.TestForm copy = form.getRules().getFirst().getTests().get(1);
        assertThat(copy).isNotSameAs(original);
        assertThat(copy.getId()).isEmpty();
        assertThat(copy.getInput()).isEqualTo(original.getInput());
        assertThat(copy.getTokenized()).isEqualTo(original.getTokenized());
        assertThat(copy.getOutput()).isEqualTo(original.getOutput());
    }
}
