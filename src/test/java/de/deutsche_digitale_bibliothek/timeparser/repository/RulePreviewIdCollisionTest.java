package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RulePreviewIdCollisionTest {

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))), tempDir.resolve("missing-rules.csv"),
                tempDir.resolve("missing-tests.csv"), new CsvExportService());
        repository.initialize();
    }

    @Test
    void doesNotMoveAnUnmatchedNewTestToAnotherRuleWithTheSameNominalPreviewId() {
        RuleGroupForm form = collisionGroup("9000");
        var newTest = form.getRules().get(1).getTests().getFirst();
        newTest.setOutput("800");

        assertThat(repository.validationIssues(form)).anySatisfy(issue -> {
            assertThat(issue.field()).isEqualTo("rules[1].tests[0].tokenized");
            assertThat(issue.code()).isEqualTo("length.mismatch");
        });
        var coverage = repository.validationNotices(form).stream()
                .filter(issue -> issue.code().equals("test.coverage"))
                .toList();
        assertThat(coverage).singleElement().satisfies(issue -> {
            assertThat(issue.field()).isEqualTo("rules[1].tests");
            assertThat(issue.message()).contains("[###]", "TNew").doesNotContain("TExisting");
        });
        assertThat(repository.previewTests(form)).extracting(test -> test.getId()).containsExactly("TExisting");
        assertThat(repository.preview(form).tests()).extracting(test -> test.getId()).containsExactly("TExisting");
        assertThat(newTest.getId()).isEqualTo("TNew");
        assertThat(form.getRules().get(1).getId()).isBlank();
    }

    @Test
    void keepsValidNewTestsAttachedToTheirOwnRuleAcrossPreviewAndSavedExport() {
        RuleGroupForm form = collisionGroup("800");

        assertThat(repository.validationIssues(form)).isEmpty();
        assertThat(repository.validationNotices(form)).isEmpty();
        var preview = repository.preview(form);
        assertThat(preview.rules()).isEqualTo(repository.previewRules(form));
        assertThat(preview.tests()).isEqualTo(repository.previewTests(form));
        assertTestRuleMasks(preview.rules(), preview.tests());
        assertThat(preview.rules()).extracting(Rule::getId).doesNotHaveDuplicates();
        assertThat(preview.rules()).filteredOn(rule -> rule.getInputMask().equals("####"))
                .singleElement().satisfies(rule -> assertThat(rule.getId()).isEqualTo("R2"));

        long groupId = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
        var snapshot = repository.exportSnapshot();
        assertThat(snapshot.warnings()).isEmpty();
        assertTestRuleMasks(snapshot.dataset().rules(), snapshot.dataset().tests());
        assertThat(form.getRules().get(1).getId()).isBlank();
    }

    @Test
    void reservesLaterExplicitIdsEvenForIncompleteRowsAndKeepsSeveralDraftsSeparate() {
        var form = new RuleGroupForm();
        form.setName("Reservierte spätere IDs");
        form.getRules().add(rule("", "####", "1900", "TFirst"));
        form.getRules().add(rule("", "###", "800", "TSecond"));
        form.getRules().add(rule("R1", "##", "80", "TLater"));
        var incompleteR2 = new RuleGroupForm.RuleVariantForm();
        incompleteR2.setId("R2");
        form.getRules().add(incompleteR2);
        var incompleteR1Variant = new RuleGroupForm.RuleVariantForm();
        incompleteR1Variant.setId("R1_2");
        form.getRules().add(incompleteR1Variant);

        var preview = repository.preview(form);

        assertThat(preview.rules()).hasSize(3).extracting(Rule::getId).doesNotHaveDuplicates();
        assertThat(preview.rules()).filteredOn(rule -> rule.getInputMask().equals("##"))
                .singleElement().satisfies(rule -> assertThat(rule.getId()).isEqualTo("R1"));
        assertThat(preview.rules()).filteredOn(rule -> !rule.getInputMask().equals("##"))
                .allSatisfy(rule -> assertThat(rule.getId()).isNotIn("R1", "R2", "R1_2"));
        Map<String, Rule> rules = preview.rules().stream().collect(Collectors.toMap(Rule::getId, rule -> rule));
        assertThat(preview.tests()).hasSize(3).allSatisfy(test ->
                assertThat(rules.get(test.getRuleId()).getInputMask().length()).isEqualTo(test.getTokenized().length()));
        assertThat(repository.validationNotices(form)).isEmpty();
        assertThat(form.getRules()).extracting(RuleGroupForm.RuleVariantForm::getId)
                .containsExactly("", "", "R1", "R2", "R1_2");
    }

    @Test
    void keepsTheUsualIndexBasedIdsWhenNothingIsReserved() {
        var form = new RuleGroupForm();
        form.setName("Unvergebene IDs");
        form.getRules().add(rule("", "####", "1900", "TFirst"));
        form.getRules().add(rule("", "###", "800", "TSecond"));

        assertThat(repository.previewRules(form)).extracting(Rule::getId).containsExactly("R1", "R2");
        assertThat(repository.validationIssues(form)).isEmpty();
        assertThat(repository.validationNotices(form)).isEmpty();
    }

    @Test
    void assignsOverlapNoticesToTheNewRuleWhenItsNominalIdIsReserved() {
        var form = new RuleGroupForm();
        form.setName("Überschneidung mit neuer Regel");
        form.getRules().add(rule("R2", "1###", "1900", "TExisting"));
        var added = rule("", "##0#", "1900", "TNew");
        added.setInputPattern("JJ0Z");
        added.setOutputPattern("JJ0Z");
        form.getRules().add(added);

        assertThat(repository.validationIssues(form)).isEmpty();
        var overlap = repository.validationNotices(form).stream()
                .filter(issue -> issue.code().equals("rule.mask.overlap"))
                .findFirst().orElseThrow();
        assertThat(overlap.field()).isEqualTo("rules[1].inputMask");
        assertThat(overlap.relatedRules()).hasSize(2);
        assertThat(overlap.relatedRules().getFirst().id()).isNotEqualTo("R2");
        assertThat(overlap.relatedRules().get(1).id()).isEqualTo("R2");
    }

    private RuleGroupForm collisionGroup(String newValue) {
        var form = new RuleGroupForm();
        form.setName("Bestehende und neue Regel");
        form.getRules().add(rule("R2", "####", "1900", "TExisting"));
        form.getRules().add(rule("", "###", newValue, "TNew"));
        return form;
    }

    private RuleGroupForm.RuleVariantForm rule(String id, String mask, String input, String testId) {
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId(id);
        rule.setInputMask(mask);
        rule.setInputPattern(mask.replace('#', 'J'));
        rule.setOutputMask(mask);
        rule.setOutputPattern(mask.replace('#', 'J'));
        var test = new RuleGroupForm.TestForm();
        test.setId(testId);
        test.setInput(input);
        test.setTokenized(input);
        test.setOutput(input);
        rule.getTests().add(test);
        return rule;
    }

    private void assertTestRuleMasks(List<Rule> rules, List<RuleTest> tests) {
        Map<String, Rule> rulesById = rules.stream().collect(Collectors.toMap(Rule::getId, rule -> rule));
        assertThat(tests).hasSize(2).allSatisfy(test ->
                assertThat(rulesById.get(test.getRuleId()).getInputMask())
                        .isEqualTo(test.getId().equals("TNew") ? "###" : "####"));
    }
}
