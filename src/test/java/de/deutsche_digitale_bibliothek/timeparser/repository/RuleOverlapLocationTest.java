package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.PlausibilityWarning.PlausibilityEntry;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.RelatedRule;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.TokenForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuleOverlapLocationTest {

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))), tempDir.resolve("missing-rules.csv"),
                tempDir.resolve("missing-tests.csv"), new CsvExportService());
        repository.initialize();
        TokenForm token = new TokenForm();
        token.setName("prefix");
        token.setValues("1\n2");
        repository.saveToken(token);
    }

    @Test
    void identifiesConcreteMasksAndTheStoredVariantForANewForm() {
        long storedGroup = repository.createGroup(group("RStored", "~prefix.### x", "~prefix.JJJ x"));
        RuleGroupForm incoming = group("", "#.##0 x", "N.JJ0 x");

        Issue overlap = repository.validationNotices(incoming).stream()
                .filter(issue -> issue.code().equals("rule.mask.overlap"))
                .filter(issue -> issue.relatedRules().stream().anyMatch(related -> related.id().equals("RStored_2")))
                .findFirst().orElseThrow();

        assertThat(overlap.field()).isEqualTo("rules[0].inputMask");
        assertThat(overlap.message()).contains("R1 [#.##0␠x]", "RStored_2 [2.###␠x]", "Beispiel: 2.000␠x");
        assertThat(overlap.message()).doesNotContain("~prefix");
        assertThat(overlap.relatedRules()).containsExactly(
                new RelatedRule("R1", 0, "R1"),
                new RelatedRule("RStored_2", storedGroup, "RStored"));
    }

    @Test
    void linksBothStoredVariantsToTheirEditableTemplates() {
        long storedGroup = repository.createGroup(group("RStored", "~prefix.### x", "~prefix.JJJ x"));
        long incomingGroup = repository.createGroup(group("RIncoming", "#.##0 x", "N.JJ0 x"));

        var warning = repository.plausibilityWarningsForGroup(incomingGroup).stream()
                .filter(item -> item.message().contains("RIncoming [#.##0␠x]"))
                .filter(item -> item.message().contains("RStored_2 [2.###␠x]"))
                .findFirst().orElseThrow();

        assertThat(warning.entries()).containsExactly(
                new PlausibilityEntry("RIncoming", incomingGroup, "rule-RIncoming"),
                new PlausibilityEntry("RStored_2", storedGroup, "rule-RStored"));
        assertThat(warning.message()).contains("Beispiel: 2.000␠x");
    }

    private RuleGroupForm group(String id, String mask, String pattern) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Maskenüberschneidung");
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId(id);
        rule.setInputMask(mask);
        rule.setInputPattern(pattern);
        rule.setOutputMask("0");
        rule.setOutputPattern("0");
        form.getRules().add(rule);
        return form;
    }
}
