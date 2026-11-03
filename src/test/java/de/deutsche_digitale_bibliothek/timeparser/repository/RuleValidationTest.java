package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupFormEditor;
import de.deutsche_digitale_bibliothek.timeparser.web.TokenForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RuleValidationTest {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidForms")
    void appliesTheSameChecksToNewAndStoredGroups(String description, Consumer<RuleGroupForm> change,
                                                   String field, String code) {
        RuleGroupForm form = form();
        change.accept(form);
        var issues = repository.validationIssues(form);
        assertThat(issues).anyMatch(issue -> issue.field().equals(field) && issue.code().equals(code));

        // Bestehende/aus CSV importierte Fehler müssen auch ohne vorherige Formularprüfung auffallen.
        long id = repository.createGroup(form);
        var warnings = repository.plausibilityWarningsForGroup(id);
        for (Issue issue : issues) {
            assertThat(warnings).anyMatch(warning -> warning.message().equals(issue.message()));
        }
        assertThat(warnings).allSatisfy(warning -> assertThat(warning.entries())
                .allMatch(entry -> entry.groupId() == id));
        assertThat(repository.plausibilityWarnings()).containsAll(warnings);
    }

    static Stream<Arguments> invalidForms() {
        return Stream.of(
                invalid("doppelte Eingabe", f -> f.getRules().getFirst().setInputPattern("JJJJ/JJJJ"), "inputPattern", "variable.duplicate"),
                invalid("Länge Eingabe", f -> f.getRules().getFirst().setInputPattern("JJJJ"), "inputPattern", "length.mismatch"),
                invalid("Länge Ausgabe", f -> f.getRules().getFirst().setOutputPattern("JJJJ"), "outputPattern", "length.mismatch"),
                invalid("Literal", f -> f.getRules().getFirst().setInputPattern("JJJJ-ZZZZ"), "inputPattern", "pattern.literal"),
                invalid("unbekannte Ausgabevariable", f -> f.getRules().getFirst().setOutputPattern("JJJJ/XXXX"), "outputPattern", "variable.undefined"),
                invalid("Pflichtfeld", f -> f.getRules().getFirst().setOutputPattern(""), "outputPattern", "field.required"),
                invalid("fehlende Tests", f -> f.getRules().getFirst().getTests().clear(), "tests", "tests.required"),
                invalid("Testlänge", f -> f.getRules().getFirst().getTests().getFirst().setTokenized("1900"), "tests[0].tokenized", "length.mismatch"),
                invalid("Testmaske", f -> f.getRules().getFirst().getTests().getFirst().setTokenized("1900-2000"), "tests[0].tokenized", "mask.mismatch"),
                invalid("Ausgabemaske", f -> f.getRules().getFirst().getTests().getFirst().setOutput("1900-2000"), "tests[0].output", "mask.mismatch"),
                invalid("äußere Testleerzeichen", f -> f.getRules().getFirst().getTests().getFirst().setInput(" 1900/2000 "), "tests[0].input", "csv.whitespace"),
                invalid("äußere Maskenleerzeichen", f -> {
                    f.getRules().getFirst().setInputMask(" ####/####");
                    f.getRules().getFirst().setInputPattern(" JJJJ/ZZZZ");
                }, "inputMask", "csv.whitespace"),
                invalid("Zeitraumformat", f -> f.getRules().getFirst().getTests().getFirst().setTimespan("1900/2000"), "tests[0].timespan", "timespan.format"),
                invalid("Zeitraumdatum", f -> f.getRules().getFirst().getTests().getFirst().setTimespan("1900-02-29/2000-12-31"), "tests[0].timespan", "timespan.format"),
                invalid("Zeitraumreihenfolge", f -> f.getRules().getFirst().getTests().getFirst().setTimespan("2000-01-01/1900-12-31"), "tests[0].timespan", "timespan.order"),
                invalid("unbekanntes Token", f -> {
                    f.getRules().getFirst().setInputMask("~unknown ####/####");
                    f.getRules().getFirst().setInputPattern("~unknown JJJJ/ZZZZ");
                }, "inputMask", "token.unknown"),
                Arguments.of("fehlende Regeln", (Consumer<RuleGroupForm>) f -> f.getRules().clear(), "rules", "rules.required"),
                Arguments.of("fehlender Name", (Consumer<RuleGroupForm>) f -> f.setName(""), "name", "field.required"),
                Arguments.of("doppelte Regel", (Consumer<RuleGroupForm>) f -> new RuleGroupFormEditor().duplicateRule(f, 0), "rules[1].inputMask", "rule.duplicate"),
                Arguments.of("doppelter Test", (Consumer<RuleGroupForm>) f -> new RuleGroupFormEditor().duplicateTest(f, 0, 0), "rules[0].tests[1].input", "test.duplicate")
        );
    }

    private static Arguments invalid(String description, Consumer<RuleGroupForm> change, String field, String code) {
        return Arguments.of(description, change, "rules[0]." + field, code);
    }

    @Test
    void checksAllExpandedRulePatterns() {
        token("years", "JJJJ/ZZZZ\nJJJJ/JJJJ");
        RuleGroupForm form = form();
        form.getRules().getFirst().setInputPattern("~years");
        assertThat(repository.validationIssues(form)).anyMatch(issue -> issue.code().equals("variable.duplicate"));
        long id = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(id)).anyMatch(warning -> warning.message().contains("mehrfach"));
    }

    @Test
    void acceptsReusingAVariableToCreateBothDecadeBoundaries() {
        RuleGroupForm form = form();
        var rule = form.getRules().getFirst();
        rule.setInputMask("###?");
        rule.setInputPattern("JJJ?");
        rule.setOutputMask("###0/###9");
        rule.setOutputPattern("JJJ0/JJJ9");
        var test = rule.getTests().getFirst();
        test.setInput("195?");
        test.setTokenized("195?");
        test.setOutput("1950/1959");
        test.setTimespan("1950-01-01/1959-12-31");

        assertThat(repository.validationIssues(form)).isEmpty();
        long id = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(id)).isEmpty();
        assertThat(repository.validationIssues(repository.findGroupForm(id))).isEmpty();
    }

    @Test
    void reportsInvalidTestExpansionEvenWhenAnotherExpansionMatches() {
        token("example", "1900/2000\nabcd/2000");
        RuleGroupForm form = form();
        form.getRules().getFirst().getTests().getFirst().setTokenized("~example");
        assertThat(repository.validationIssues(form)).anyMatch(issue -> issue.code().equals("mask.mismatch"));
        long id = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(id)).anyMatch(warning -> warning.message().contains("nicht zur Eingabemaske"));
    }

    @Test
    void acceptsTokenizedDigitTemplatesAndVariableLengthTokenValuesAfterExpansion() {
        RuleGroupForm form = form();
        var rule = form.getRules().getFirst();
        rule.setInputMask("~approx ####/####");
        rule.setInputPattern("~approx JJJJ/ZZZZ");
        var test = rule.getTests().getFirst();
        test.setInput("~approx 1900/2000");
        test.setTokenized("~approx ####/####");
        assertThat(repository.validationIssues(form)).isEmpty();
        long id = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(id)).isEmpty();
        assertThat(repository.validationIssues(repository.findGroupForm(id))).isEmpty();
    }

    @Test
    void checksRuleBoundOutputTokensOnlyForMatchingInputVariants() {
        RuleGroupForm form = form();
        var rule = form.getRules().getFirst();
        rule.setInputMask("~approx ####");
        rule.setInputPattern("~approx JJJJ");
        rule.setOutputMask("~approx ####");
        rule.setOutputPattern("~approx JJJJ");
        var test = rule.getTests().getFirst();
        test.setInput("ca. 1950");
        test.setTokenized("ca. 1950");
        test.setOutput("~approx 1950");
        test.setTimespan("1950-01-01/1950-12-31");
        assertThat(repository.validationIssues(form)).isEmpty();
        assertThat(repository.previewTests(form)).hasSize(1);
        var coverageNotices = repository.validationNotices(form).stream()
                .filter(issue -> issue.code().equals("test.coverage")).toList();
        assertThat(coverageNotices).isNotEmpty();
        long id = repository.createGroup(form);
        assertThat(repository.validationIssues(repository.findGroupForm(id))).isEmpty();
        assertThat(repository.validationNotices(repository.findGroupForm(id)))
                .anyMatch(issue -> issue.code().equals("test.coverage"));
        assertThat(repository.plausibilityWarningsForGroup(id))
                .anyMatch(warning -> warning.message().contains("kein passender Test exportiert"));
    }

    @Test
    void bindsCaseVariantsAndHistoricalTokenAliasesToTheSameExpansion() {
        RuleGroupForm form = form();
        var rule = form.getRules().getFirst();
        rule.setInputMask("~approx ####");
        rule.setInputPattern("~APROX JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        var test = rule.getTests().getFirst();
        test.setInput("~APPROX 1950");
        test.setTokenized("~aprox 1950");
        test.setOutput("1950");
        test.setTimespan("1950-01-01/1950-12-31");

        int variants = repository.findTokens().stream().filter(token -> token.name().equals("approx"))
                .findFirst().orElseThrow().values().size();
        assertThat(repository.validationIssues(form)).isEmpty();
        assertThat(repository.previewRules(form)).hasSize(variants);
        assertThat(repository.previewTests(form)).hasSize(variants);
        long id = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(id)).isEmpty();
    }

    @Test
    void checksMaskConflictsAndDuplicateTestsAcrossGroupsAndIgnoresTheEditedGroup() {
        long first = repository.createGroup(form());
        assertThat(repository.validationIssues(repository.findGroupForm(first))).isEmpty();
        RuleGroupForm conflicting = form();
        conflicting.getRules().getFirst().setOutputPattern("ZZZZ/JJJJ");
        assertThat(repository.validationIssues(conflicting)).anyMatch(issue -> issue.code().equals("rule.mask.conflict"));
        assertThat(repository.validationIssues(conflicting)).noneMatch(issue -> issue.code().equals("test.duplicate"));
        long second = repository.createGroup(conflicting);
        assertThat(repository.plausibilityWarningsForGroup(second)).anyMatch(warning -> warning.message().contains("Eingabemaske ist bereits"));
    }

    @Test
    void validatesCalendarValuesByTheirInputVariableType() {
        RuleGroupForm form = form();
        var rule = form.getRules().getFirst();
        rule.setInputMask("MM ####");
        rule.setInputPattern("XX JJJJ");
        rule.setOutputMask("####-MM");
        rule.setOutputPattern("JJJJ-XX");
        var test = rule.getTests().getFirst();
        test.setInput("März 2010");
        test.setTokenized("MM 2010");
        test.setOutput("2010-03");
        test.setTimespan("2010-03-01/2010-03-31");
        assertThat(repository.validationIssues(form)).isEmpty();
        long id = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(id)).isEmpty();
    }

    @Test
    void reportsPossibleMaskOverlapWithoutBlockingTheForm() {
        RuleGroupForm first = form();
        var firstRule = first.getRules().getFirst();
        firstRule.setInputMask("1###");
        firstRule.setInputPattern("1JJJ");
        firstRule.setOutputMask("1###");
        firstRule.setOutputPattern("1JJJ");
        var firstTest = firstRule.getTests().getFirst();
        firstTest.setInput("1900");
        firstTest.setTokenized("1900");
        firstTest.setOutput("1900");
        firstTest.setTimespan("1900-01-01/1900-12-31");
        RuleGroupForm second = form();
        var secondRule = second.getRules().getFirst();
        secondRule.setInputMask("##0#");
        secondRule.setInputPattern("JJ0Z");
        secondRule.setOutputMask("##0#");
        secondRule.setOutputPattern("JJ0Z");
        secondRule.setTests(java.util.List.of(firstTest));
        first.getRules().add(secondRule);

        assertThat(repository.validationIssues(first)).isEmpty();
        assertThat(repository.validationNotices(first)).anyMatch(issue -> issue.code().equals("rule.mask.overlap"));
        long id = repository.createGroup(first);
        assertThat(repository.plausibilityWarningsForGroup(id)).anyMatch(warning -> warning.message().contains("überschneiden"));
    }

    private void token(String name, String values) {
        TokenForm token = new TokenForm();
        token.setName(name);
        token.setValues(values);
        repository.saveToken(token);
    }

    private RuleGroupForm form() {
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
        test.setTimespan("1900-01-01/2000-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }
}
