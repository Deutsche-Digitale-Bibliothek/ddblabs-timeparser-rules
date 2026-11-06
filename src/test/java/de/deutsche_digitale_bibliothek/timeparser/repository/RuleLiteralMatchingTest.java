package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.TokenForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RuleLiteralMatchingTest {

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
    void keepsExpandedLiteralVariantsAndTheirTestsDistinctThroughExport() {
        List<String> literals = List.of("-", "‐", "‑", "–", "—", "−", "－", ":", ";", "|", "@", "x", "y", "(", ")");
        TokenForm separator = new TokenForm();
        separator.setName("separator");
        separator.setValues(String.join("\n", literals));
        repository.saveToken(separator);
        RuleGroupForm form = group("~separator", "~separator");

        assertThat(repository.validationIssues(form)).isEmpty();
        assertThat(repository.validationNotices(form)).isEmpty();
        long groupId = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();

        var snapshot = repository.exportSnapshot();
        assertThat(snapshot.warnings()).isEmpty();
        assertThat(snapshot.dataset().rules()).extracting(Rule::getInputMask)
                .containsExactlyInAnyOrderElementsOf(literals.stream().map(value -> "#### " + value + " ####").toList());
        assertThat(snapshot.dataset().tests()).hasSize(literals.size());
        Map<String, Rule> rulesById = snapshot.dataset().rules().stream()
                .collect(Collectors.toMap(Rule::getId, rule -> rule));
        assertThat(snapshot.dataset().tests()).allSatisfy(test -> {
            Rule rule = rulesById.get(test.getRuleId());
            String separatorValue = rule.getInputMask().substring(5, 6);
            assertThat(test.getTokenized()).isEqualTo("1900 " + separatorValue + " 2000");
        });
        assertThat(snapshot.dataset().tests()).extracting(RuleTest::getTokenized).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @CsvSource({"-,–", "‐,‑", "–,—", "−,－", ":,;", "|,@", "x,y", "(,)"})
    void rejectsTestsWhoseLiteralOnlyLooksLikeTheRuleLiteral(String ruleLiteral, String testLiteral) {
        RuleGroupForm form = group(ruleLiteral, testLiteral);

        assertThat(repository.validationIssues(form)).anySatisfy(issue -> {
            assertThat(issue.field()).isEqualTo("rules[0].tests[0].tokenized");
            assertThat(issue.code()).isEqualTo("mask.mismatch");
        });
        assertThat(repository.previewTests(form)).isEmpty();

        repository.createGroup(form);
        var snapshot = repository.exportSnapshot();
        assertThat(snapshot.dataset().rules()).hasSize(1);
        assertThat(snapshot.dataset().tests()).isEmpty();
        assertThat(snapshot.warnings()).anySatisfy(warning ->
                assertThat(warning.message()).contains("keiner erzeugten Eingabemaske"));
    }

    private RuleGroupForm group(String ruleLiteral, String testLiteral) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Literale Trennzeichen");
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask("#### " + ruleLiteral + " ####");
        rule.setInputPattern("JJJJ " + ruleLiteral + " ZZZZ");
        rule.setOutputMask("####/####");
        rule.setOutputPattern("JJJJ/ZZZZ");
        var test = new RuleGroupForm.TestForm();
        test.setInput("1900 " + testLiteral + " 2000");
        test.setTokenized("1900 " + testLiteral + " 2000");
        test.setOutput("1900/2000");
        test.setTimespan("1900-01-01/2000-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }
}
