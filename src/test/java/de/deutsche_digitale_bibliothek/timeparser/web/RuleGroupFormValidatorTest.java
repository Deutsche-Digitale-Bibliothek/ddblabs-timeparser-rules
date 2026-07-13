package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleGroupFormValidatorTest {

    private final SqliteRuleRepository repository = mock(SqliteRuleRepository.class);
    private final RuleGroupFormValidator validator = new RuleGroupFormValidator(repository);

    @Test
    void acceptsCompletePlausibleGroup() {
        RuleGroupForm form = completeForm();
        when(repository.unknownTokenNames(form)).thenReturn(List.of());
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "groupForm");

        validator.validateForSave(form, errors);

        assertThat(errors.getAllErrors()).isEmpty();
    }

    @Test
    void reportsLengthAndTimespanErrorsOnTheirFields() {
        RuleGroupForm form = completeForm();
        RuleGroupForm.RuleVariantForm rule = form.getRules().getFirst();
        rule.setInputPattern("JJJJ");
        rule.getTests().getFirst().setTimespan("2010-12-31/2010-01-01");
        when(repository.unknownTokenNames(form)).thenReturn(List.of());
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "groupForm");

        validator.validateForPreview(form, errors);

        assertThat(errors.getFieldError("rules[0].inputPattern")).isNotNull();
        assertThat(errors.getFieldError("rules[0].tests[0].timespan")).isNotNull();
    }

    @Test
    void rejectsDuplicateRules() {
        RuleGroupForm form = completeForm();
        new RuleGroupFormEditor().duplicateRule(form, 0);
        when(repository.unknownTokenNames(form)).thenReturn(List.of());
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "groupForm");

        validator.validateForSave(form, errors);

        assertThat(errors.getFieldError("rules[1].inputMask"))
                .extracting(error -> error.getCode())
                .isEqualTo("rule.duplicate");
    }

    @Test
    void rejectsDuplicateTests() {
        RuleGroupForm form = completeForm();
        new RuleGroupFormEditor().duplicateTest(form, 0, 0);
        when(repository.unknownTokenNames(form)).thenReturn(List.of());
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "groupForm");

        validator.validateForSave(form, errors);

        assertThat(errors.getFieldError("rules[0].tests[1].input"))
                .extracting(error -> error.getCode())
                .isEqualTo("test.duplicate");
    }

    private RuleGroupForm completeForm() {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Beispiel");
        RuleGroupForm.RuleVariantForm rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask("###");
        rule.setInputPattern("JJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        RuleGroupForm.TestForm test = new RuleGroupForm.TestForm();
        test.setInput("800");
        test.setTokenized("800");
        test.setOutput("0800");
        test.setTimespan("0790-01-01/0810-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }
}
