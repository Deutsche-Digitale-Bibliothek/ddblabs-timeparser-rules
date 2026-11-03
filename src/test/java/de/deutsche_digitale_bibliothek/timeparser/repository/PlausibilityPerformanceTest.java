package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.TokenForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class PlausibilityPerformanceTest {

    @TempDir
    Path tempDir;

    private CountingDataSource dataSource;
    private SqliteRuleRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new CountingDataSource("jdbc:sqlite:" + tempDir.resolve("rules.sqlite"));
        repository = new SqliteRuleRepository(new JdbcTemplate(dataSource),
                tempDir.resolve("missing-rules.csv"), tempDir.resolve("missing-tests.csv"), new CsvExportService());
        repository.initialize();
    }

    @Test
    void loadsTheWholeCheckWithAFixedNumberOfDatabaseReads() {
        for (int index = 0; index < 2; index++) {
            repository.createGroup(form(index + ":"));
        }
        dataSource.reset();
        assertThat(repository.plausibilityWarnings()).isEmpty();
        int readsForTwoGroups = dataSource.connectionCount;

        for (int index = 2; index < 30; index++) {
            repository.createGroup(form(index + ":"));
        }
        dataSource.reset();
        assertThat(repository.plausibilityWarnings()).isEmpty();

        // Keine Laufzeitgrenze: Die Zahl der Datenbankzugriffe darf nicht mit der Gruppenzahl wachsen.
        assertThat(dataSource.connectionCount).isEqualTo(readsForTwoGroups).isLessThanOrEqualTo(8);
    }

    @Test
    void preservesCoverageNoticesForIncompleteStoredTestTemplates() {
        RuleGroupForm form = form("circa");
        form.getRules().getFirst().getTests().getFirst().setOutput("");
        repository.createGroup(form);

        assertThat(repository.plausibilityWarnings())
                .anyMatch(warning -> warning.message().equals("Die Ausgabe ist erforderlich."))
                .anyMatch(warning -> warning.message().contains("kein passender Test"));
    }

    @Test
    void reflectsChangedTokenValuesAndRulesInTheNextCheck() {
        TokenForm token = new TokenForm();
        token.setName("prefix");
        token.setValues("circa");
        repository.saveToken(token);
        token.setId(repository.findTokens().stream().filter(value -> value.name().equals("prefix"))
                .findFirst().orElseThrow().id());

        RuleGroupForm form = form("~prefix");
        var test = form.getRules().getFirst().getTests().getFirst();
        test.setInput("circa 1900");
        test.setTokenized("circa 1900");
        long firstId = repository.createGroup(form);
        assertThat(repository.plausibilityWarnings()).isEmpty();

        token.setValues("circa\netwa");
        repository.saveToken(token);
        assertThat(repository.plausibilityWarnings()).anyMatch(warning -> warning.message().contains("kein passender Test"));

        token.setValues("circa");
        repository.saveToken(token);
        assertThat(repository.plausibilityWarnings()).isEmpty();

        long duplicateId = repository.createGroup(form);
        assertThat(repository.plausibilityWarnings()).anyMatch(warning -> warning.message().contains("bereits vorhanden"));

        repository.deleteGroup(duplicateId);
        assertThat(repository.plausibilityWarnings()).isEmpty();
        repository.deleteGroup(firstId);
        assertThat(repository.plausibilityWarnings()).isEmpty();
    }

    private RuleGroupForm form(String prefix) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Jahr " + prefix);
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask(prefix + " ####");
        rule.setInputPattern(prefix + " JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        var test = new RuleGroupForm.TestForm();
        test.setInput(prefix + " 1900");
        test.setTokenized(prefix + " 1900");
        test.setOutput("1900");
        test.setTimespan("1900-01-01/1900-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }

    /** Jeder JdbcTemplate-Aufruf öffnet hier eine eigene Verbindung; es gibt keinen Transaktions- oder Poolcache. */
    private static final class CountingDataSource extends DriverManagerDataSource {
        private int connectionCount;

        private CountingDataSource(String url) {
            super(url);
        }

        @Override
        public Connection getConnection() throws SQLException {
            connectionCount++;
            return super.getConnection();
        }

        private void reset() {
            connectionCount = 0;
        }
    }
}
