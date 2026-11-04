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
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuleInvalidSaveTest {

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;
    private RuleController controller;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))), tempDir.resolve("missing-rules.csv").toString(),
                tempDir.resolve("missing-tests.csv").toString(), new CsvExportService());
        repository.initialize();
        controller = new RuleController(repository, new RuleGroupFormEditor(), new RuleGroupFormValidator(repository));
    }

    @Test
    void validatesStoredRowsOnOpeningWithoutRemovingBlankTemplates() {
        RuleGroupForm form = validForm();
        var rule = form.getRules().getFirst();
        rule.setInputMask("");
        rule.setInputPattern("");
        rule.setOutputMask("");
        rule.setOutputPattern("");
        var test = rule.getTests().getFirst();
        test.setInput("");
        test.setTokenized("");
        test.setOutput("");
        long groupId = repository.createGroup(form);
        String storedRuleId = repository.findGroupForm(groupId).getRules().getFirst().getId();
        var model = new ExtendedModelMap();

        assertThat(controller.showGroup(groupId, model)).isEqualTo("rules/detail");

        RuleGroupForm loaded = (RuleGroupForm) model.get("groupForm");
        BindingResult errors = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "groupForm");
        assertThat(errors.hasFieldErrors("rules[0].inputMask")).isTrue();
        assertThat(errors.hasFieldErrors("rules[0].tests[0].input")).isTrue();
        assertThat(loaded.getRules()).singleElement().satisfies(stored -> {
            assertThat(stored.getId()).isEqualTo(storedRuleId);
            assertThat(stored.getInputMask()).isEmpty();
            assertThat(stored.getTests()).hasSize(1);
        });
        assertConsentReset(loaded);
        assertThat(review(model)).isNotBlank();
        assertThat(repository.findGroupForm(groupId).getRules()).hasSize(1);
    }

    @Test
    void keepsValidCreateAndUpdateOnTheNormalSavePath() {
        RuleGroupForm form = validForm();
        var createErrors = errors(form);
        var created = new RedirectAttributesModelMap();

        assertThat(controller.createGroup(form, createErrors, new ExtendedModelMap(), created))
                .startsWith("redirect:/rules/");
        assertThat(createErrors.hasErrors()).isFalse();
        assertThat(created.getFlashAttributes()).containsKey("message").doesNotContainKey("saveWarning");
        long groupId = repository.findGroupSummaries().getFirst().id();
        RuleGroupForm edit = repository.findGroupForm(groupId);
        edit.setDescription("Aktualisierte Beschreibung");
        var updateErrors = errors(edit);
        var updated = new RedirectAttributesModelMap();

        assertThat(controller.saveGroup(groupId, edit, updateErrors, new ExtendedModelMap(), updated))
                .isEqualTo("redirect:/rules/{groupId}");
        assertThat(updateErrors.hasErrors()).isFalse();
        assertThat(updated.getFlashAttributes()).containsKey("message").doesNotContainKey("saveWarning");
        assertThat(repository.findGroupForm(groupId).getDescription()).isEqualTo("Aktualisierte Beschreibung");
    }

    @Test
    void requiresBothTheReviewedStateAndExplicitConsentBeforeCreatingInvalidRules() {
        RuleGroupForm form = invalidForm();
        var firstModel = new ExtendedModelMap();
        var firstErrors = errors(form);

        assertThat(controller.createGroup(form, firstErrors, firstModel, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");
        assertThat(firstErrors.hasFieldErrors("rules[0].inputPattern")).isTrue();
        assertThat(repository.findGroupSummaries()).isEmpty();
        String token = review(firstModel);

        form.setValidationReview(token);
        var tokenOnlyModel = new ExtendedModelMap();
        assertThat(controller.createGroup(form, errors(form), tokenOnlyModel, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");
        assertThat(repository.findGroupSummaries()).isEmpty();
        assertConsentReset(form);

        form.setSaveInvalid(true);
        var flagOnlyModel = new ExtendedModelMap();
        assertThat(controller.createGroup(form, errors(form), flagOnlyModel, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");
        assertThat(repository.findGroupSummaries()).isEmpty();
        assertConsentReset(form);

        approve(form, review(flagOnlyModel));
        var confirmed = new RedirectAttributesModelMap();
        assertThat(controller.createGroup(form, errors(form), new ExtendedModelMap(), confirmed))
                .startsWith("redirect:/rules/");
        assertThat(repository.findGroupSummaries()).hasSize(1);
        assertThat(confirmed.getFlashAttributes()).containsKey("saveWarning").doesNotContainKey("message");
        long groupId = repository.findGroupSummaries().getFirst().id();
        assertThat(repository.findGroupForm(groupId).getRules().getFirst().getInputPattern()).isEqualTo("JJJJ/JJJJ");

        var reopened = new ExtendedModelMap();
        controller.showGroup(groupId, reopened);
        assertConsentReset((RuleGroupForm) reopened.get("groupForm"));
        assertThat(((BindingResult) reopened.get(BindingResult.MODEL_KEY_PREFIX + "groupForm")).hasErrors()).isTrue();
    }

    @Test
    void leavesStoredDataUntouchedUntilAnInvalidUpdateIsExplicitlyConfirmed() {
        long groupId = repository.createGroup(validForm());
        RuleGroupForm edit = repository.findGroupForm(groupId);
        edit.getRules().getFirst().setInputPattern("JJJJ/JJJJ");
        edit.setDescription("Absichtlich fehlerhafter Bearbeitungsstand");
        var reviewModel = new ExtendedModelMap();

        assertThat(controller.saveGroup(groupId, edit, errors(edit), reviewModel, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");
        assertThat(repository.findGroupForm(groupId).getRules().getFirst().getInputPattern()).isEqualTo("JJJJ/ZZZZ");
        assertThat(repository.findGroupForm(groupId).getDescription()).isEmpty();

        approve(edit, review(reviewModel));
        var confirmed = new RedirectAttributesModelMap();
        assertThat(controller.saveGroup(groupId, edit, errors(edit), new ExtendedModelMap(), confirmed))
                .isEqualTo("redirect:/rules/{groupId}");
        assertThat(confirmed.getFlashAttributes()).containsKey("saveWarning");
        assertThat(repository.findGroupForm(groupId).getRules().getFirst().getInputPattern()).isEqualTo("JJJJ/JJJJ");
        assertThat(repository.findGroupForm(groupId).getDescription()).isEqualTo(edit.getDescription());
    }

    @Test
    void rejectsAnApprovalAfterTheReviewedDraftChanges() {
        RuleGroupForm form = invalidForm();
        String token = reviewNewForm(form);
        form.setDescription("Nach der Prüfung geänderter Inhalt");
        approve(form, token);
        var model = new ExtendedModelMap();

        assertThat(controller.createGroup(form, errors(form), model, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");

        assertThat(repository.findGroupSummaries()).isEmpty();
        assertConsentReset(form);
        assertThat(review(model)).isNotEqualTo(token);
    }

    @Test
    void requiresAFreshReviewWhenAnotherRuleIntroducesANewConflict() {
        RuleGroupForm form = invalidForm();
        String token = reviewNewForm(form);
        repository.createGroup(validForm());
        approve(form, token);
        var model = new ExtendedModelMap();
        var currentErrors = errors(form);

        assertThat(controller.createGroup(form, currentErrors, model, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");

        assertThat(currentErrors.getFieldErrors()).anyMatch(error -> "rule.mask.conflict".equals(error.getCode()));
        assertThat(repository.findGroupSummaries()).hasSize(1);
        assertConsentReset(form);
        assertThat(review(model)).isNotEqualTo(token);
    }

    @Test
    void cannotReuseAnApprovalForAnotherGroup() {
        long firstGroup = repository.createGroup(invalidForm());
        long secondGroup = repository.createGroup(invalidForm());
        var firstModel = new ExtendedModelMap();
        controller.showGroup(firstGroup, firstModel);
        RuleGroupForm firstDraft = (RuleGroupForm) firstModel.get("groupForm");
        approve(firstDraft, review(firstModel));
        var wrongGroupModel = new ExtendedModelMap();

        assertThat(controller.saveGroup(secondGroup, firstDraft, errors(firstDraft), wrongGroupModel,
                new RedirectAttributesModelMap())).isEqualTo("rules/detail");

        assertThat(firstDraft.getId()).isEqualTo(secondGroup);
        assertConsentReset(firstDraft);
        assertThat(repository.findGroupForm(secondGroup).getRules().getFirst().getId())
                .isNotEqualTo(firstDraft.getRules().getFirst().getId());
        assertThat(repository.findGroupSummaries()).hasSize(2);
    }

    @Test
    void neverOverridesTechnicalBindingFailures() {
        RuleGroupForm form = invalidForm();
        approve(form, reviewNewForm(form));
        var bindingErrors = errors(form);
        bindingErrors.addError(new FieldError("groupForm", "id", "keine-zahl", true,
                new String[]{"typeMismatch"}, null, "Die Gruppen-ID ist ungültig."));
        var model = new ExtendedModelMap();

        assertThat(controller.createGroup(form, bindingErrors, model, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");

        assertThat(repository.findGroupSummaries()).isEmpty();
        assertThat(model).containsEntry("invalidSaveBlocked", true).doesNotContainKey("invalidSaveReview");
        assertConsentReset(form);
    }

    @Test
    void previewRequiresANewExplicitChoiceEvenForAnAlreadyReviewedDraft() {
        RuleGroupForm form = invalidForm();
        approve(form, reviewNewForm(form));
        var model = new ExtendedModelMap();
        var previewErrors = errors(form);

        assertThat(controller.previewNewGroup(form, previewErrors, model)).isEqualTo("rules/detail");

        assertThat(previewErrors.hasErrors()).isTrue();
        assertThat(review(model)).isNotBlank();
        assertConsentReset(form);
        assertThat(repository.findGroupSummaries()).isEmpty();
    }

    @Test
    void rowActionsDoNotCarryForwardSaveConsent() {
        RuleGroupForm form = invalidForm();
        approve(form, reviewNewForm(form));
        var model = new ExtendedModelMap();

        assertThat(controller.addTestToNewGroupRule(0, form, model)).isEqualTo("rules/detail");

        assertConsentReset(form);
        assertThat(model).doesNotContainKey("invalidSaveReview");
        assertThat(repository.findGroupSummaries()).isEmpty();
    }

    private String reviewNewForm(RuleGroupForm form) {
        var model = new ExtendedModelMap();
        assertThat(controller.createGroup(form, errors(form), model, new RedirectAttributesModelMap()))
                .isEqualTo("rules/detail");
        return review(model);
    }

    private String review(ExtendedModelMap model) {
        assertThat(model.get("invalidSaveReview")).isInstanceOf(String.class);
        return (String) model.get("invalidSaveReview");
    }

    private BeanPropertyBindingResult errors(RuleGroupForm form) {
        return new BeanPropertyBindingResult(form, "groupForm");
    }

    private void approve(RuleGroupForm form, String token) {
        form.setSaveInvalid(true);
        form.setValidationReview(token);
    }

    private void assertConsentReset(RuleGroupForm form) {
        assertThat(form.isSaveInvalid()).isFalse();
        assertThat(form.getValidationReview()).isEmpty();
    }

    private RuleGroupForm invalidForm() {
        RuleGroupForm form = validForm();
        form.getRules().getFirst().setInputPattern("JJJJ/JJJJ");
        return form;
    }

    private RuleGroupForm validForm() {
        RuleGroupForm form = new RuleGroupForm();
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
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }
}
