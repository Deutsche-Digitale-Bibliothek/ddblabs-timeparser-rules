package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.validation.BeanPropertyBindingResult;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuleGroupFormValidatorTest {

    @TempDir
    Path tempDir;

    private RuleGroupFormValidator validator;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("rules.sqlite"));
        var repository = new SqliteRuleRepository(
                new JdbcTemplate(dataSource),
                tempDir.resolve("missing-rules.csv").toString(),
                tempDir.resolve("missing-tests.csv").toString(),
                new CsvExportService()
        );
        repository.initialize();
        validator = new RuleGroupFormValidator(repository);
    }

    @Test
    void acceptsCompletePlausibleGroup() {
        RuleGroupForm form = completeForm();
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
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "groupForm");

        validator.validateForPreview(form, errors);

        assertThat(errors.getFieldError("rules[0].inputPattern")).isNotNull();
        assertThat(errors.getFieldError("rules[0].tests[0].timespan")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDuplicateRules(boolean preview) {
        RuleGroupForm form = completeForm();
        new RuleGroupFormEditor().duplicateRule(form, 0);

        BeanPropertyBindingResult errors = validate(form, preview);

        assertThat(errors.getFieldErrors("rules[1].inputMask"))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("rule.duplicate");
                    assertThat(error.getDefaultMessage()).isEqualTo(
                            "Diese Regel ist bereits in der Regelgruppe vorhanden. Die Kopie muss vor dem Speichern angepasst werden.");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDuplicateTests(boolean preview) {
        RuleGroupForm form = completeForm();
        new RuleGroupFormEditor().duplicateTest(form, 0, 0);

        BeanPropertyBindingResult errors = validate(form, preview);

        assertThat(errors.getFieldErrors("rules[0].tests[1].input"))
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("test.duplicate");
                    assertThat(error.getDefaultMessage()).isEqualTo(
                            "Dieser Test ist bereits vorhanden. Die Kopie muss vor dem Speichern angepasst werden.");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesTheSameErrorCodeOnDifferentFields(boolean preview) {
        RuleGroupForm form = completeForm();
        var editor = new RuleGroupFormEditor();
        editor.duplicateRule(form, 0);
        editor.duplicateRule(form, 0);

        BeanPropertyBindingResult errors = validate(form, preview);

        assertThat(errors.getFieldErrors("rules[1].inputMask"))
                .extracting(error -> error.getCode())
                .containsExactly("rule.duplicate");
        assertThat(errors.getFieldErrors("rules[2].inputMask"))
                .extracting(error -> error.getCode())
                .containsExactly("rule.duplicate");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesDifferentErrorCodesOnTheSameField(boolean preview) {
        RuleGroupForm form = completeForm();
        form.getRules().getFirst().getTests().getFirst().setInput(" 800 ");
        new RuleGroupFormEditor().duplicateTest(form, 0, 0);

        BeanPropertyBindingResult errors = validate(form, preview);

        assertThat(errors.getFieldErrors("rules[0].tests[1].input"))
                .extracting(error -> error.getCode())
                .containsExactlyInAnyOrder("test.duplicate", "csv.whitespace");
    }

    private BeanPropertyBindingResult validate(RuleGroupForm form, boolean preview) {
        var errors = new BeanPropertyBindingResult(form, "groupForm");
        if (preview) {
            validator.validateForPreview(form, errors);
        } else {
            validator.validateForSave(form, errors);
        }
        return errors;
    }

    private RuleGroupForm completeForm() {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Beispiel");
        RuleGroupForm.RuleVariantForm rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask("###");
        rule.setInputPattern("JJJ");
        rule.setOutputMask("0###");
        rule.setOutputPattern("0JJJ");
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
