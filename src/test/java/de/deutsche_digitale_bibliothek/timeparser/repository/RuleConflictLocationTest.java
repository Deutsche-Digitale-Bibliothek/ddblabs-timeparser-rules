package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.RelatedRule;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuleConflictLocationTest {

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
    void identifiesTheExistingRuleAndGroupInDuplicateFormErrors() {
        long existingGroup = repository.createGroup(group("Vorhandene Regel", "RStored"));

        Issue duplicate = repository.validationIssues(group("Kopie", "RCopy")).stream()
                .filter(issue -> issue.code().equals("rule.duplicate"))
                .findFirst().orElseThrow();

        assertThat(duplicate.message()).contains("Diese generierte Regel ist bereits vorhanden.",
                "RStored", "Regelgruppe " + existingGroup);
        assertThat(duplicate.relatedRules()).containsExactly(new RelatedRule("RStored", existingGroup, "RStored"));
    }

    @Test
    void linksBothTheCheckedAndExistingRuleInPlausibilityWarnings() {
        long existingGroup = repository.createGroup(group("Vorhandene Regel", "RStored"));
        long duplicateGroup = repository.createGroup(group("Importierte Kopie", "RCopy"));

        var warning = repository.plausibilityWarningsForGroup(duplicateGroup).stream()
                .filter(item -> item.message().startsWith("Diese generierte Regel ist bereits vorhanden."))
                .findFirst().orElseThrow();

        assertThat(warning.entries()).anySatisfy(entry -> {
            assertThat(entry.id()).isEqualTo("RCopy");
            assertThat(entry.groupId()).isEqualTo(duplicateGroup);
            assertThat(entry.anchor()).isEqualTo("rule-RCopy");
        }).anySatisfy(entry -> {
            assertThat(entry.id()).isEqualTo("RStored");
            assertThat(entry.groupId()).isEqualTo(existingGroup);
            assertThat(entry.anchor()).isEqualTo("rule-RStored");
        });
    }

    @Test
    void identifiesTheExistingTransformationWhenTheInputMaskConflicts() {
        long existingGroup = repository.createGroup(group("Vorhandene Regel", "RStored"));
        RuleGroupForm conflicting = group("Andere Transformation", "RConflict");
        var rule = conflicting.getRules().getFirst();
        rule.setOutputMask("###0");
        rule.setOutputPattern("JJJ0");

        Issue conflict = repository.validationIssues(conflicting).stream()
                .filter(issue -> issue.code().equals("rule.mask.conflict"))
                .findFirst().orElseThrow();

        assertThat(conflict.message()).contains("RStored", "Regelgruppe " + existingGroup);
        assertThat(conflict.relatedRules()).containsExactly(new RelatedRule("RStored", existingGroup, "RStored"));
    }

    @Test
    void doesNotReportTheUnchangedStoredGroupAsItsOwnDuplicate() {
        long groupId = repository.createGroup(group("Vorhandene Regel", "RStored"));

        assertThat(repository.validationIssues(repository.findGroupForm(groupId))).isEmpty();
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
    }

    @Test
    void keepsNewRulesSeparateWhenTheirProvisionalIdMatchesAnotherFormRule() {
        RuleGroupForm form = group("Regeln mit vorläufiger ID-Kollision", "R2");
        var newRule = group("Neue Regel", "").getRules().getFirst();
        newRule.setInputMask("###");
        newRule.setInputPattern("JJJ");
        newRule.setOutputMask("###");
        newRule.setOutputPattern("JJJ");
        var newTest = newRule.getTests().getFirst();
        newTest.setInput("800");
        newTest.setTokenized("800");
        newTest.setOutput("800");
        newTest.setTimespan("0800-01-01/0800-12-31");
        form.getRules().add(newRule);

        // Die neue Zeile an Index 1 erhält dieselbe vorläufige ID R2 wie die erste.
        // Ihre Expansion und Testprüfung müssen trotzdem getrennt bleiben.
        assertThat(repository.validationIssues(form)).isEmpty();
    }

    @Test
    void linksExpandedDuplicatesToTheirEditableTemplateWithinTheSameGroup() {
        RuleGroupForm form = group("Variantenüberschneidung", "RToken");
        var tokenRule = form.getRules().getFirst();
        tokenRule.setInputMask("~approx ####");
        tokenRule.setInputPattern("~approx JJJJ");
        tokenRule.getTests().getFirst().setInput("~approx 1900");
        tokenRule.getTests().getFirst().setTokenized("~approx 1900");
        var literalRule = group("Einzelne Variante", "RLiteral").getRules().getFirst();
        literalRule.setInputMask("ca. ####");
        literalRule.setInputPattern("ca. JJJJ");
        literalRule.getTests().getFirst().setInput("ca. 1900");
        literalRule.getTests().getFirst().setTokenized("ca. 1900");
        form.getRules().add(literalRule);
        long groupId = repository.createGroup(form);

        var warning = repository.plausibilityWarningsForGroup(groupId).stream()
                .filter(item -> item.message().startsWith("Diese generierte Regel ist bereits vorhanden."))
                .findFirst().orElseThrow();

        assertThat(warning.message()).contains("RToken_2", "Regelgruppe " + groupId);
        assertThat(warning.entries()).anySatisfy(entry -> {
            assertThat(entry.id()).isEqualTo("RToken_2");
            assertThat(entry.groupId()).isEqualTo(groupId);
            assertThat(entry.anchor()).isEqualTo("rule-RToken");
        }).anySatisfy(entry -> {
            assertThat(entry.id()).isEqualTo("RLiteral");
            assertThat(entry.anchor()).isEqualTo("rule-RLiteral");
        });
    }

    private RuleGroupForm group(String name, String ruleId) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName(name);
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId(ruleId);
        rule.setInputMask("####");
        rule.setInputPattern("JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        var test = new RuleGroupForm.TestForm();
        test.setInput("1900");
        test.setTokenized("1900");
        test.setOutput("1900");
        test.setTimespan("1900-01-01/1900-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }
}
