package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.TokenForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ExportSnapshotTest {

    @TempDir
    Path tempDir;
    private SqliteRuleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))),
                tempDir.resolve("missing-rules.csv").toString(),
                tempDir.resolve("missing-tests.csv").toString(), new CsvExportService());
        repository.initialize();
    }

    @Test
    void zipAndStandaloneDownloadsUseTheSameCsvAndIncludeTheReport() throws IOException {
        repository.createGroup(yearGroup("Jahr", "1900"));
        var snapshot = repository.exportSnapshot();

        Map<String, byte[]> entries = zipEntries(repository.rulesAndTestsZip(snapshot));

        assertThat(entries).containsOnlyKeys("rules.csv", "tests.csv", "pruefhinweise.txt");
        assertThat(entries.get("rules.csv")).isEqualTo(repository.rulesCsv(snapshot));
        assertThat(entries.get("tests.csv")).isEqualTo(repository.testsCsv(snapshot));
        assertThat(entries.get("rules.csv")).isEqualTo(repository.rulesCsv());
        assertThat(entries.get("tests.csv")).isEqualTo(repository.testsCsv());
        assertThat(new String(entries.get("pruefhinweise.txt"), StandardCharsets.UTF_8)).isEqualTo(snapshot.report());
    }

    @Test
    void deduplicatesRulesWithoutLosingTheirDifferentTests() throws IOException {
        repository.createGroup(yearGroup("Erstes Jahr", "1900"));
        repository.createGroup(yearGroup("Zweites Jahr", "2000"));

        var snapshot = repository.exportSnapshot();

        assertThat(snapshot.dataset().rules()).hasSize(1);
        String canonicalRuleId = snapshot.dataset().rules().getFirst().getId();
        assertThat(snapshot.dataset().tests()).extracting(RuleTest::getInput).containsExactly("1900", "2000");
        assertThat(snapshot.dataset().tests()).extracting(RuleTest::getRuleId).containsOnly(canonicalRuleId);
        Set<String> ruleIds = snapshot.dataset().rules().stream().map(Rule::getId).collect(Collectors.toSet());
        assertThat(snapshot.dataset().tests()).allMatch(test -> ruleIds.contains(test.getRuleId()));
        Map<String, byte[]> entries = zipEntries(repository.rulesAndTestsZip(snapshot));
        assertThat(entries.get("tests.csv")).isEqualTo(repository.testsCsv(snapshot));
    }

    @Test
    void exportsConflictingTransformationsWithAnExplicitWarning() {
        repository.createGroup(yearGroup("Ganzes Jahr", "1900"));
        RuleGroupForm conflicting = yearGroup("Gekürztes Jahr", "1900");
        conflicting.getRules().getFirst().setOutputMask("##");
        conflicting.getRules().getFirst().setOutputPattern("JJ");
        conflicting.getRules().getFirst().getTests().getFirst().setOutput("19");
        repository.createGroup(conflicting);

        var snapshot = repository.exportSnapshot();

        assertThat(snapshot.dataset().rules()).hasSize(2);
        assertThat(snapshot.warnings()).anyMatch(warning -> warning.message().contains("anderen Transformation"));
        assertThat(new String(repository.rulesCsv(snapshot), StandardCharsets.UTF_8)).contains("JJJJ", "JJ");
        assertThat(snapshot.report()).contains("anderen Transformation");
    }

    @Test
    void omitsUnexpandableRuleAndTestTemplatesWhileExportingTheValidGroup() {
        long validId = repository.createGroup(yearGroup("Gültig", "1900"));
        RuleGroupForm missingRule = yearGroup("Fehlender Regelplatzhalter", "2000");
        missingRule.getRules().getFirst().setInputMask("~missingrule ####");
        missingRule.getRules().getFirst().setInputPattern("~missingrule JJJJ");
        long missingRuleId = repository.createGroup(missingRule);
        RuleGroupForm missingTest = yearGroup("Fehlender Testplatzhalter", "1700");
        missingTest.getRules().getFirst().setInputMask("Test ####");
        missingTest.getRules().getFirst().setInputPattern("Test JJJJ");
        missingTest.getRules().getFirst().getTests().getFirst().setInput("Test 1700");
        missingTest.getRules().getFirst().getTests().getFirst().setTokenized("Test 1700");
        missingTest.getRules().getFirst().getTests().getFirst().setTimespan("~missingtest");
        long missingTestId = repository.createGroup(missingTest);
        String validRuleId = repository.findGroupForm(validId).getRules().getFirst().getId();
        String omittedRuleId = repository.findGroupForm(missingRuleId).getRules().getFirst().getId();
        String omittedTestId = repository.findGroupForm(missingTestId).getRules().getFirst().getTests().getFirst().getId();

        var snapshot = repository.exportSnapshot();

        assertThat(snapshot.dataset().rules()).extracting(Rule::getId).contains(validRuleId).doesNotContain(omittedRuleId);
        assertThat(snapshot.dataset().tests()).extracting(RuleTest::getInput).contains("1900");
        assertThat(snapshot.dataset().tests()).extracting(RuleTest::getId).doesNotContain(omittedTestId);
        assertThat(snapshot.report()).contains(omittedRuleId, omittedTestId, "missingrule", "missingtest", "fehlen im Export", "fehlt im Export");
        assertThat(repository.rulesCsv(snapshot)).isNotEmpty();
        assertThat(repository.testsCsv(snapshot)).isNotEmpty();
    }

    @Test
    void rechecksTokenChangesAndDeletionAtExportTime() {
        TokenForm token = new TokenForm();
        token.setName("yearpattern");
        token.setValues("JJJJ");
        repository.saveToken(token);
        long tokenId = repository.findTokens().stream().filter(value -> value.name().equals("yearpattern")).findFirst().orElseThrow().id();
        RuleGroupForm form = yearGroup("Veränderlicher Platzhalter", "1900");
        form.getRules().getFirst().setInputPattern("~yearpattern");
        repository.createGroup(form);
        assertThat(repository.exportSnapshot().warnings()).isEmpty();

        token.setId(tokenId);
        token.setValues("JJ");
        repository.saveToken(token);
        var changed = repository.exportSnapshot();
        assertThat(changed.dataset().rules()).hasSize(1);
        assertThat(changed.report()).contains("gleich lang");
        assertThat(changed.warnings()).isNotEmpty();

        repository.deleteToken(tokenId);
        var deleted = repository.exportSnapshot();
        assertThat(deleted.dataset().rules()).isEmpty();
        assertThat(deleted.dataset().tests()).isEmpty();
        assertThat(deleted.report()).contains("yearpattern", "nicht definiert", "fehlen im Export");
    }

    @Test
    void reportStatesTheLimitOfTheStaticChecksEvenWhenThereAreNoWarnings() {
        repository.createGroup(yearGroup("Jahr", "1900"));

        var snapshot = repository.exportSnapshot();

        assertThat(snapshot.warnings()).isEmpty();
        assertThat(snapshot.report()).contains("statische Plausibilitätsprüfungen", "nicht im Timeparser ausgeführt", "Datumsinterpretation");
    }

    private RuleGroupForm yearGroup(String name, String year) {
        var group = new RuleGroupForm();
        group.setName(name);
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask("####");
        rule.setInputPattern("JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        var test = new RuleGroupForm.TestForm();
        test.setInput(year);
        test.setTokenized(year);
        test.setOutput(year);
        test.setTimespan(year + "-01-01/" + year + "-12-31");
        rule.getTests().add(test);
        group.getRules().add(rule);
        return group;
    }

    private Map<String, byte[]> zipEntries(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                entries.put(entry.getName(), input.readAllBytes());
            }
        }
        return entries;
    }
}
