package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupFormEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuleTestScopeTest {

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(
                new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))),
                tempDir.resolve("missing-rules.csv").toString(), tempDir.resolve("missing-tests.csv").toString(),
                new CsvExportService());
        repository.initialize();
    }

    @Test
    void allowsTheSameExampleForGeneralAndMoreSpecificRulesWithinAGroup() {
        RuleGroupForm form = group("Allgemeine und spezielle Regel", "####", "JJJJ");
        form.getRules().add(rule("19##", "19JJ"));

        assertThat(repository.validationIssues(form)).isEmpty();
        long groupId = repository.createGroup(form);
        assertThat(repository.validationIssues(repository.findGroupForm(groupId))).isEmpty();
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
        assertBothRuleTestsAreExported();
    }

    @Test
    void allowsTheSameExampleForDifferentRulesAcrossGroups() {
        repository.createGroup(group("Allgemeine Regel", "####", "JJJJ"));
        RuleGroupForm moreSpecific = group("Spezielle Regel", "19##", "19JJ");

        assertThat(repository.validationIssues(moreSpecific)).isEmpty();
        repository.createGroup(moreSpecific);

        assertThat(repository.plausibilityWarnings()).isEmpty();
        assertBothRuleTestsAreExported();
    }

    @Test
    void stillRejectsDuplicateTestsWithinTheSameRuleVariant() {
        RuleGroupForm form = group("Doppelter Test", "####", "JJJJ");
        new RuleGroupFormEditor().duplicateTest(form, 0, 0);

        assertThat(repository.validationIssues(form)).anyMatch(issue ->
                issue.code().equals("test.duplicate") && issue.field().equals("rules[0].tests[1].input"));
    }

    @Test
    void treatsTheSameExampleForIdenticalRulesAcrossGroupsAsADuplicate() {
        repository.createGroup(group("Erste Regel", "####", "JJJJ"));
        RuleGroupForm duplicate = group("Regelkopie", "####", "JJJJ");

        assertThat(repository.validationIssues(duplicate)).anyMatch(issue -> issue.code().equals("rule.duplicate"));
        assertThat(repository.validationIssues(duplicate)).anyMatch(issue -> issue.code().equals("test.duplicate"));

        // Importierte Altbestände bleiben diagnostizierbar; der Export vereinigt exakte Dubletten.
        repository.createGroup(duplicate);
        var snapshot = repository.exportSnapshot();
        assertThat(snapshot.dataset().rules()).hasSize(1);
        assertThat(snapshot.dataset().tests()).hasSize(1);
        assertThat(snapshot.warnings()).isNotEmpty();
    }

    private void assertBothRuleTestsAreExported() {
        var dataset = repository.exportSnapshot().dataset();
        assertThat(dataset.rules()).hasSize(2);
        assertThat(dataset.tests()).hasSize(2);
        assertThat(dataset.tests()).extracting(RuleTest::getRuleId).doesNotHaveDuplicates();
        assertThat(dataset.tests()).extracting(RuleTest::getInput).containsOnly("1900");
        assertThat(dataset.tests()).extracting(RuleTest::getOutput).containsOnly("1900");
    }

    private RuleGroupForm group(String name, String mask, String pattern) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName(name);
        form.getRules().add(rule(mask, pattern));
        return form;
    }

    private RuleGroupForm.RuleVariantForm rule(String mask, String pattern) {
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask(mask);
        rule.setInputPattern(pattern);
        rule.setOutputMask(mask);
        rule.setOutputPattern(pattern);
        var test = new RuleGroupForm.TestForm();
        test.setInput("1900");
        test.setTokenized("1900");
        test.setOutput("1900");
        test.setTimespan("1900-01-01/1900-12-31");
        rule.getTests().add(test);
        return rule;
    }
}
