package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleLocation;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleLocationRepositoryTest {

    @TempDir
    Path tempDir;

    private CountingDataSource dataSource;
    private SqliteRuleRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new CountingDataSource("jdbc:sqlite:" + tempDir.resolve("rules.sqlite"));
        repository = new SqliteRuleRepository(new JdbcTemplate(dataSource), tempDir.resolve("missing-rules.csv"),
                tempDir.resolve("missing-tests.csv"), new CsvExportService());
        repository.initialize();
        TokenForm prefix = new TokenForm();
        prefix.setName("listingPrefix");
        prefix.setValues("ca.\ncirca");
        repository.saveToken(prefix);
    }

    @Test
    void reflectsCurrentTokenVariantsAndTheirOriginsInBothListings() {
        long groupId = repository.createGroup(group("Jahresvarianten", "R370", "~listingPrefix "));
        var initialRules = repository.generatedRuleListing();
        var initialTests = repository.generatedTestListing();
        assertThat(initialRules.rules()).hasSize(2);
        assertThat(initialTests.tests()).hasSize(2);
        assertThat(initialRules.locations()).containsEntry("R370_2",
                new RuleLocation("R370_2", "R370", groupId, "Jahresvarianten"));
        assertThat(initialTests.locations()).isEqualTo(initialRules.locations());

        long tokenId = repository.findTokens().stream().filter(token -> token.name().equals("listingPrefix"))
                .findFirst().orElseThrow().id();
        TokenForm edited = repository.findTokenForm(tokenId);
        edited.setValues("ca.");
        repository.saveToken(edited);

        var changedRules = repository.generatedRuleListing();
        var changedTests = repository.generatedTestListing();
        assertThat(changedRules.rules()).singleElement()
                .satisfies(rule -> assertThat(rule.getId()).isEqualTo("R370"));
        assertThat(changedTests.tests()).hasSize(1);
        assertThat(changedRules.locations()).doesNotContainKey("R370_2").containsEntry("R370",
                new RuleLocation("R370", "R370", groupId, "Jahresvarianten"));
        assertThat(changedTests.locations()).isEqualTo(changedRules.locations());
    }

    @Test
    void retainsKnownOriginsWhenGeneratedIdsCollideWithStoredTemplateIds() {
        long variantGroup = repository.createGroup(group("Varianten", "R1", "~listingPrefix "));
        long storedGroup = repository.createGroup(group("Explizite Suffix-ID", "R1_2", ""));
        byte[] rulesCsv = repository.rulesCsv();
        byte[] testsCsv = repository.testsCsv();

        var rules = repository.generatedRuleListing();
        var tests = repository.generatedTestListing();
        assertThat(rules.rules()).isEqualTo(repository.generatedRules());
        assertThat(tests.tests()).isEqualTo(repository.generatedTests());
        assertThat(rules.locations().get("R1_2")).isEqualTo(
                new RuleLocation("R1_2", "R1", variantGroup, "Varianten"));
        assertThat(rules.locations().get("R1_2_2")).isEqualTo(
                new RuleLocation("R1_2_2", "R1_2", storedGroup, "Explizite Suffix-ID"));
        assertThat(tests.tests()).hasSize(3).allSatisfy(test ->
                assertThat(tests.locations().get(test.getRuleId())).isEqualTo(rules.locations().get(test.getRuleId())));
        assertThat(repository.rulesCsv()).isEqualTo(rulesCsv);
        assertThat(repository.testsCsv()).isEqualTo(testsCsv);
    }

    @Test
    void retainsDifferentTemplateAnchorsWithinTheSameGroup() {
        RuleGroupForm form = group("Zwei Vorlagen", "R1", "~listingPrefix ");
        form.getRules().add(group("Andere Vorlage", "R1_2", "").getRules().getFirst());
        long groupId = repository.createGroup(form);

        var rules = repository.generatedRuleListing();
        var tests = repository.generatedTestListing();
        assertThat(rules.locations()).containsEntry("R1_2",
                new RuleLocation("R1_2", "R1", groupId, "Zwei Vorlagen"));
        assertThat(rules.locations()).containsEntry("R1_2_2",
                new RuleLocation("R1_2_2", "R1_2", groupId, "Zwei Vorlagen"));
        assertThat(tests.locations()).isEqualTo(rules.locations());
    }

    @Test
    void usesAFixedNumberOfReadsForBothListings() {
        repository.createGroup(group("Erste Gruppe", "R10", ""));
        repository.createGroup(group("Zweite Gruppe", "R20", ""));
        List<Integer> before = readCounts();
        for (int index = 3; index <= 20; index++) {
            repository.createGroup(group("Weitere Gruppe " + index, "R" + index + "0", ""));
        }

        assertThat(readCounts()).containsExactlyElementsOf(before);
        assertThat(before).containsExactly(3, 4);
    }

    private List<Integer> readCounts() {
        dataSource.connectionCount = 0;
        repository.generatedRuleListing();
        int ruleReads = dataSource.connectionCount;
        dataSource.connectionCount = 0;
        repository.generatedTestListing();
        return List.of(ruleReads, dataSource.connectionCount);
    }

    private RuleGroupForm group(String name, String id, String prefix) {
        var form = new RuleGroupForm();
        form.setName(name);
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId(id);
        rule.setInputMask(prefix + "####");
        rule.setInputPattern(prefix + "JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        var test = new RuleGroupForm.TestForm();
        test.setInput(prefix + "1900");
        test.setTokenized(prefix + "1900");
        test.setOutput("1900");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }

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
    }
}
