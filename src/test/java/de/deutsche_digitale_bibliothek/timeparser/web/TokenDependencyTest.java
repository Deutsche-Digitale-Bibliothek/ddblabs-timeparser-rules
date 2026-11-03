package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleGroupSummary;
import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TokenDependencyTest {

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;
    private TokenController controller;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))),
                tempDir.resolve("missing-rules.csv").toString(), tempDir.resolve("missing-tests.csv").toString(),
                new CsvExportService());
        repository.initialize();
        controller = new TokenController(repository, new DownloadResponseFactory());
    }

    @Test
    void findsRuleAndTestDependenciesIncludingHistoricalAliasesAndCaseVariants() {
        long approx = tokenId("approx");
        long ruleGroup = repository.createGroup(ruleTokenGroup("~aprox"));
        long testGroup = repository.createGroup(testTokenGroup("~APPROX"));
        repository.createGroup(testTokenGroup("~approximation"));

        assertThat(repository.groupsUsingToken(approx)).extracting(RuleGroupSummary::id)
                .containsExactlyInAnyOrder(ruleGroup, testGroup);
    }

    @Test
    void findsATokenUsedOnlyInTheExpectedTimespan() {
        long dates = createToken("dates", "1950-01-01/1950-12-31");
        RuleGroupForm form = literalGroup("", "1950");
        form.getRules().getFirst().getTests().getFirst().setTimespan("~DATES");
        long group = repository.createGroup(form);

        assertThat(repository.groupsUsingToken(dates)).extracting(RuleGroupSummary::id).containsExactly(group);
    }

    @Test
    void reportsAffectedGroupsAfterUpdatingTokenValues() {
        long approx = tokenId("approx");
        long ruleGroup = repository.createGroup(ruleTokenGroup("~aprox"));
        long testGroup = repository.createGroup(testTokenGroup("~APPROX"));
        TokenForm form = repository.findTokenForm(approx);
        form.setValues("ungefähr\ncirca");
        var errors = new BeanPropertyBindingResult(form, "tokenForm");
        var redirect = new RedirectAttributesModelMap();

        assertThat(controller.updateToken(approx, form, errors, new ExtendedModelMap(), redirect)).isEqualTo("redirect:/tokens");

        assertThat(errors.getAllErrors()).isEmpty();
        assertThat(affectedGroups(redirect)).extracting(RuleGroupSummary::id)
                .containsExactlyInAnyOrder(ruleGroup, testGroup);
        assertThat(repository.findTokenForm(approx).getValues()).isEqualTo("ungefähr\ncirca");
    }

    @Test
    void renamingAReferencedTokenLeavesVisibleFindingsAndStillAllowsExport() throws Exception {
        long marker = createToken("marker", "ca.");
        long affected = repository.createGroup(ruleTokenGroup("~marker"));
        repository.createGroup(literalGroup("Datum ", "1960"));
        TokenForm form = repository.findTokenForm(marker);
        form.setName("renamed");
        var errors = new BeanPropertyBindingResult(form, "tokenForm");
        var redirect = new RedirectAttributesModelMap();

        assertThat(controller.updateToken(marker, form, errors, new ExtendedModelMap(), redirect)).isEqualTo("redirect:/tokens");

        assertThat(errors.getAllErrors()).isEmpty();
        assertThat(affectedGroups(redirect)).extracting(RuleGroupSummary::id).containsExactly(affected);
        assertThat(repository.plausibilityWarningsForGroup(affected))
                .anyMatch(warning -> warning.message().contains("Platzhalter ~marker ist nicht definiert"));
        var snapshot = repository.exportSnapshot();
        assertThat(snapshot.dataset().rules()).extracting(rule -> rule.getInputMask()).containsExactly("Datum ####");
        assertThat(snapshot.dataset().tests()).hasSize(1);
        assertThat(snapshot.report()).contains("~marker", "fehlen im Export");
        assertThat(zipEntry(repository.rulesAndTestsZip(snapshot), "pruefhinweise.txt"))
                .contains("~marker", "fehlen im Export");
    }

    @Test
    void deletingATokenReportsItsTestDependenciesAndKeepsOtherExportedTests() throws Exception {
        long marker = createToken("marker", "ca.");
        long affected = repository.createGroup(testTokenGroup("~marker"));
        repository.createGroup(literalGroup("Datum ", "1960"));
        var redirect = new RedirectAttributesModelMap();

        assertThat(controller.deleteToken(marker, redirect)).isEqualTo("redirect:/tokens");

        assertThat(repository.tokenExists(marker)).isFalse();
        assertThat(affectedGroups(redirect)).extracting(RuleGroupSummary::id).containsExactly(affected);
        assertThat(repository.plausibilityWarningsForGroup(affected))
                .anyMatch(warning -> warning.message().contains("Platzhalter ~marker ist nicht definiert"));
        var snapshot = repository.exportSnapshot();
        assertThat(snapshot.dataset().rules()).hasSize(2);
        assertThat(snapshot.dataset().tests()).extracting(test -> test.getInput()).containsExactly("Datum 1960");
        assertThat(zipEntry(repository.rulesAndTestsZip(snapshot), "pruefhinweise.txt"))
                .contains("fehlt im Export", "Platzhalter");
    }

    @SuppressWarnings("unchecked")
    private List<RuleGroupSummary> affectedGroups(RedirectAttributesModelMap redirect) {
        assertThat(redirect.getFlashAttributes()).containsKey("tokenAffectedGroups");
        return (List<RuleGroupSummary>) redirect.getFlashAttributes().get("tokenAffectedGroups");
    }

    private long tokenId(String name) {
        return repository.findTokens().stream().filter(token -> token.name().equals(name)).findFirst().orElseThrow().id();
    }

    private long createToken(String name, String values) {
        TokenForm token = new TokenForm();
        token.setName(name);
        token.setValues(values);
        repository.saveToken(token);
        return tokenId(name);
    }

    private RuleGroupForm ruleTokenGroup(String token) {
        return literalGroup(token + " ", "1950");
    }

    private RuleGroupForm testTokenGroup(String token) {
        RuleGroupForm form = literalGroup("", "1950");
        form.getRules().getFirst().getTests().getFirst().setInput(token + " 1950");
        return form;
    }

    private RuleGroupForm literalGroup(String prefix, String year) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Abhängigkeit " + prefix + year);
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask(prefix + "####");
        rule.setInputPattern(prefix + "JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        var test = new RuleGroupForm.TestForm();
        test.setInput(prefix + year);
        test.setTokenized(prefix + year);
        test.setOutput(year);
        test.setTimespan(year + "-01-01/" + year + "-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }

    private String zipEntry(byte[] archive, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("ZIP-Eintrag fehlt: " + name);
    }
}
