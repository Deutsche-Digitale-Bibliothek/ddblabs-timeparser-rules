package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class RuleControllerValidationTest {

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;
    private RuleController controller;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("rules.sqlite"));
        repository = new SqliteRuleRepository(
                new JdbcTemplate(dataSource),
                tempDir.resolve("missing-rules.csv").toString(),
                tempDir.resolve("missing-tests.csv").toString(),
                new CsvExportService()
        );
        repository.initialize();
        controller = new RuleController(repository, new RuleGroupFormEditor(), new RuleGroupFormValidator(repository));
    }

    @Test
    void rejectsRepeatedInputVariablesBeforeCreatingAGroupAndInPreview() {
        assertRejectedForSaveAndPreview(
                form -> form.getRules().getFirst().setInputPattern("JJJJ/JJJJ"),
                "rules[0].inputPattern"
        );
    }

    @Test
    void acceptsRepeatedOutputVariablesForSaveAndPreview() {
        RuleGroupForm form = yearRange();
        var rule = form.getRules().getFirst();
        rule.setOutputPattern("JJJJ/JJJJ");
        rule.getTests().getFirst().setOutput("1900/1900");
        rule.getTests().getFirst().setTimespan("1900-01-01/1900-12-31");
        var previewErrors = new BeanPropertyBindingResult(form, "groupForm");

        assertThat(controller.previewNewGroup(form, previewErrors, new ExtendedModelMap())).isEqualTo("rules/detail");
        assertThat(previewErrors.getAllErrors()).isEmpty();

        var saveErrors = new BeanPropertyBindingResult(form, "groupForm");
        String view = controller.createGroup(form, saveErrors, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertThat(saveErrors.getAllErrors()).isEmpty();
        long groupId = repository.findGroupSummaries().getFirst().id();
        assertThat(view).isEqualTo("redirect:/rules/" + groupId);
        assertThat(repository.findGroupForm(groupId).getRules().getFirst().getOutputPattern()).isEqualTo("JJJJ/JJJJ");
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
    }

    @Test
    void checksRequiredFieldsForSaveAndPreview() {
        assertRejectedForSaveAndPreview(
                form -> form.getRules().getFirst().setInputPattern(""),
                "rules[0].inputPattern"
        );
    }

    @Test
    void requiresAtLeastOneRuleForSaveAndPreview() {
        assertRejectedForSaveAndPreview(form -> form.getRules().clear(), "rules");
    }

    @Test
    void requiresAtLeastOneTestForSaveAndPreview() {
        assertRejectedForSaveAndPreview(
                form -> form.getRules().getFirst().getTests().clear(),
                "rules[0].tests"
        );
    }

    @Test
    void createsAndPreviewsAYearRangeWithDistinctVariables() {
        RuleGroupForm form = yearRange();
        var previewErrors = new BeanPropertyBindingResult(form, "groupForm");
        var previewModel = new ExtendedModelMap();

        assertThat(controller.previewNewGroup(form, previewErrors, previewModel)).isEqualTo("rules/detail");
        assertThat(previewErrors.getAllErrors()).isEmpty();
        assertThat(repository.findGroupSummaries()).isEmpty();

        var saveErrors = new BeanPropertyBindingResult(form, "groupForm");
        var redirect = new RedirectAttributesModelMap();
        String view = controller.createGroup(form, saveErrors, new ExtendedModelMap(), redirect);

        assertThat(saveErrors.getAllErrors()).isEmpty();
        assertThat(repository.findGroupSummaries()).hasSize(1);
        long groupId = repository.findGroupSummaries().getFirst().id();
        assertThat(view).isEqualTo("redirect:/rules/" + groupId);
        assertThat(repository.findGroupForm(groupId).getRules().getFirst().getInputPattern()).isEqualTo("JJJJ/ZZZZ");
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
    }

    private void assertRejectedForSaveAndPreview(Consumer<RuleGroupForm> change, String field) {
        RuleGroupForm saveForm = yearRange();
        change.accept(saveForm);
        var saveErrors = new BeanPropertyBindingResult(saveForm, "groupForm");

        String saveView = controller.createGroup(
                saveForm, saveErrors, new ExtendedModelMap(), new RedirectAttributesModelMap()
        );

        assertThat(saveView).isEqualTo("rules/detail");
        assertThat(saveErrors.hasFieldErrors(field)).isTrue();
        assertThat(repository.findGroupSummaries()).isEmpty();

        RuleGroupForm previewForm = yearRange();
        change.accept(previewForm);
        var previewErrors = new BeanPropertyBindingResult(previewForm, "groupForm");

        assertThat(controller.previewNewGroup(previewForm, previewErrors, new ExtendedModelMap()))
                .isEqualTo("rules/detail");
        assertThat(previewErrors.getAllErrors()).isEqualTo(saveErrors.getAllErrors());
        assertThat(repository.findGroupSummaries()).isEmpty();
    }

    private RuleGroupForm yearRange() {
        var form = new RuleGroupForm();
        form.setName("Jahresbereich");
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask("####/####");
        rule.setInputPattern("JJJJ/ZZZZ");
        rule.setOutputMask("####/####");
        rule.setOutputPattern("JJJJ/ZZZZ");
        var test = new RuleGroupForm.TestForm();
        test.setInput("1900/2000");
        test.setTokenized("1900/2000");
        test.setOutput("1900/2000");
        test.setTimespan("1900-01-01/2000-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }
}
