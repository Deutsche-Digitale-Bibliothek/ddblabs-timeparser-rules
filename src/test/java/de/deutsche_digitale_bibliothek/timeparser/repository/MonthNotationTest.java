package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Masken bestimmen den Werttyp; Musterbuchstaben benennen lediglich die Variablen. */
class MonthNotationTest {

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
    void acceptsBothReportedRulesIndividually() {
        assertThat(repository.validationIssues(numericRule("RYear", "NN.JJJJ", "####", "JJJJ", "03", "2010")))
                .isEmpty();
        assertThat(repository.validationIssues(numericRule("RMonth", "MM.JJJJ", "####-##", "JJJJ-MM", "03", "2010-03")))
                .isEmpty();
    }

    @Test
    void explainsTheSharedMaskConflictAndRetainsItsLocationForStoredRules() {
        long firstGroup = repository.createGroup(numericRule("RYear", "NN.JJJJ", "####", "JJJJ", "03", "2010"));
        RuleGroupForm incoming = numericRule("RMonth", "MM.JJJJ", "####-##", "JJJJ-MM", "03", "2010-03");

        var conflict = repository.validationIssues(incoming).stream()
                .filter(issue -> issue.code().equals("rule.mask.conflict"))
                .findFirst().orElseThrow();

        assertThat(conflict.message()).contains("##.####", "Variablennamen", "Monatsnamen");
        assertThat(conflict.relatedRules()).singleElement().satisfies(location -> {
            assertThat(location.id()).isEqualTo("RYear");
            assertThat(location.groupId()).isEqualTo(firstGroup);
            assertThat(location.templateId()).isEqualTo("RYear");
        });

        // Der Importpfad darf ungültige Altbestände speichern; der Bestandscheck
        // muss denselben Konflikt mit demselben Verweis weiterhin erläutern.
        long secondGroup = repository.createGroup(incoming);
        assertThat(repository.plausibilityWarningsForGroup(secondGroup)).anySatisfy(warning -> {
            assertThat(warning.message()).isEqualTo(conflict.message());
            assertThat(warning.entries()).anySatisfy(location -> {
                assertThat(location.id()).isEqualTo("RYear");
                assertThat(location.groupId()).isEqualTo(firstGroup);
                assertThat(location.anchor()).isEqualTo("rule-RYear");
            });
        });
    }

    @Test
    void acceptsRenamingANumericMonthVariableWithoutChangingItsMaskCategory() {
        RuleGroupForm renamed = numericRule("RNumber", "NN.JJJJ", "####-##", "JJJJ-NN", "03", "2010-03");

        assertThat(repository.validationIssues(renamed)).isEmpty();
        assertThat(RulePatternValidator.outputTestMask("##.####", "NN.JJJJ", "####-##", "JJJJ-NN"))
                .isEqualTo("####-##");
        long groupId = repository.createGroup(renamed);
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
    }

    @Test
    void acceptsAMonthNameBoundToNAndChecksItsOutputAsDigits() {
        RuleGroupForm form = group("RNamedMonth", "MM ####", "NN JJJJ", "####-MM", "JJJJ-NN",
                "März 2010", "MM 2010", "2010-03");

        assertThat(repository.validationIssues(form)).isEmpty();
        assertThat(RulePatternValidator.outputTestMask("MM ####", "NN JJJJ", "####-MM", "JJJJ-NN"))
                .isEqualTo("####-##");
        long groupId = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
    }

    @Test
    void doesNotDeriveAMonthRangeFromTheVariableNameM() {
        // Eine generische Zahl unter M ist noch kein kalendarisch geprüfter Monat.
        RuleGroupForm form = numericRule("RGenericM", "MM.JJJJ", "####-##", "JJJJ-MM", "99", "2010-99");

        assertThat(repository.validationIssues(form)).isEmpty();
        long groupId = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"##.####", "MM.####"})
    void explainsWhenTheTokenizedInputUsesTheWrongMonthNotation(String inputMask) {
        String tokenized = inputMask.startsWith("#") ? "MM.2010" : "03.2010";
        RuleGroupForm form = group("RWrongNotation", inputMask, "NN.JJJJ", "####", "JJJJ",
                "März.2010", tokenized, "2010");

        var mismatch = repository.validationIssues(form).stream()
                .filter(issue -> issue.code().equals("mask.mismatch") && issue.field().endsWith(".tokenized"))
                .findFirst().orElseThrow();
        assertThat(mismatch.message()).contains("MM", "Eingabemaske", "##");

        long groupId = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(groupId))
                .anyMatch(warning -> warning.message().equals(mismatch.message()));
    }

    @Test
    void explainsThatOutputMMMustReferenceAnInputVariableNamedM() {
        RuleGroupForm form = numericRule("RUndefinedM", "NN.JJJJ", "####-##", "JJJJ-MM", "03", "2010-03");

        var undefined = repository.validationIssues(form).stream()
                .filter(issue -> issue.code().equals("variable.undefined"))
                .findFirst().orElseThrow();

        assertThat(undefined.message()).contains("MM", "Variablenname");
        long groupId = repository.createGroup(form);
        assertThat(repository.plausibilityWarningsForGroup(groupId))
                .anyMatch(warning -> warning.message().equals(undefined.message()));
    }

    private RuleGroupForm numericRule(String id, String inputPattern, String outputMask, String outputPattern,
                                     String number, String output) {
        return group(id, "##.####", inputPattern, outputMask, outputPattern,
                number + ".2010", number + ".2010", output);
    }

    private RuleGroupForm group(String id, String inputMask, String inputPattern, String outputMask, String outputPattern,
                                String input, String tokenized, String output) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Monatsnotation " + id);
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId(id);
        rule.setInputMask(inputMask);
        rule.setInputPattern(inputPattern);
        rule.setOutputMask(outputMask);
        rule.setOutputPattern(outputPattern);
        var test = new RuleGroupForm.TestForm();
        test.setInput(input);
        test.setTokenized(tokenized);
        test.setOutput(output);
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }
}
