package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.RulePreview;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteRuleRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void expandsPlaceholdersInTestsWithRuleTokens() {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Test-Platzhalter mit Regel-Token");
        RuleGroupForm.RuleVariantForm rule = rule("~aprox ###", "~aprox JJJ", "um 0###", "um 0JJJ");
        rule.getTests().add(test("~approx 800", "~approx 800", "um 0800", "0790-01-01/0810-12-31"));
        form.getRules().add(rule);

        assertThat(repository.previewTests(form))
                .extracting(RuleTest::getTokenized)
                .contains("um 800", "ca. 800", "circa 800", "zirka 800", "etwa 800");

        long groupId = repository.createGroup(form);

        List<RuleTest> generatedTests = repository.generatedTestsForGroup(groupId);
        assertThat(generatedTests)
                .extracting(RuleTest::getTokenized)
                .contains("um 800", "ca. 800", "circa 800", "zirka 800", "etwa 800");
    }

    @Test
    void exportsGeneratedPlaceholderTests() throws IOException {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Exportierte Platzhalter-Tests");
        RuleGroupForm.RuleVariantForm rule = rule("~aprox ###", "~aprox JJJ", "um 0###", "um 0JJJ");
        rule.getTests().add(test("~approx 800", "~approx 800", "um 0800", "0790-01-01/0810-12-31"));
        form.getRules().add(rule);
        repository.createGroup(form);

        String testsCsv = zipEntry(repository.rulesAndTestsZip(), "tests.csv");
        String standaloneTestsCsv = new String(repository.testsCsv(), StandardCharsets.UTF_8);

        assertThat(testsCsv).contains("um 800", "ca. 800", "circa 800", "zirka 800", "etwa 800");
        assertThat(standaloneTestsCsv)
                .startsWith("id,for,input,tokenized,output,timespan")
                .contains("um 800", "ca. 800", "circa 800", "zirka 800", "etwa 800");
    }

    @Test
    void exportsGeneratedRulesAsStandaloneCsv() throws IOException {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Exportierte Regeln");
        RuleGroupForm.RuleVariantForm rule = rule("###", "JJJ", "0###", "0JJJ");
        rule.getTests().add(test("800", "800", "0800", "0790-01-01/0810-12-31"));
        form.getRules().add(rule);
        repository.createGroup(form);

        String rulesCsv = new String(repository.rulesCsv(), StandardCharsets.UTF_8);

        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get()
                .parse(new StringReader(rulesCsv))) {
            assertThat(parser.getRecords().getFirst().toMap()).containsAllEntriesOf(java.util.Map.of(
                    "inputMask", "###",
                    "inputPattern", "JJJ",
                    "outputMask", "0###",
                    "outputPattern", "0JJJ"
            ));
        }
    }

    @Test
    void expandsPlaceholdersOnlyUsedInTests() {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Test-Platzhalter ohne Regel-Token");
        RuleGroupForm.RuleVariantForm rule = rule("MM ####", "MM JJJJ", "####-##", "JJJJ-MM");
        rule.getTests().add(test("~approx März 2010", "MM 2010", "2010-03", "2010-03-01/2010-03-31"));
        form.getRules().add(rule);

        long groupId = repository.createGroup(form);

        List<RuleTest> generatedTests = repository.generatedTestsForGroup(groupId);
        assertThat(generatedTests)
                .extracting(RuleTest::getInput)
                .contains("um März 2010", "ca. März 2010", "circa März 2010", "zirka März 2010", "etwa März 2010");
    }

    @Test
    void expandsBeforeChristPlaceholderWhenRuleUsesSamePlaceholder() {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Test-Platzhalter vor Christus");
        RuleGroupForm.RuleVariantForm rule = rule("######## ~vchr", "JJJJJJJJ ~vchr", "-########", "-JJJJJJJJ");
        rule.getTests().add(test("20000000 ~vchr", "20000000 ~vchr", "-20000000", "-19999999-01-01/-19999999-12-31"));
        form.getRules().add(rule);

        assertThat(repository.previewTests(form))
                .extracting(RuleTest::getTokenized)
                .contains("20000000 v. Chr.", "20000000 v.Chr.", "20000000 v. Chr", "20000000 v.Chr");
    }

    @Test
    void fillsDigitPlaceholdersInTokenizedTestFromInput() {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Test-Platzhalter mit tokenisierter Maske");
        RuleGroupForm.RuleVariantForm rule = rule("~aprox ####", "~aprox JJJJ", "um ####", "um JJJJ");
        rule.getTests().add(test("~aprox 1950", "~aprox ####", "um 1950", "1950-01-01/1950-12-31"));
        form.getRules().add(rule);

        assertThat(repository.previewTests(form))
                .extracting(RuleTest::getTokenized)
                .contains("um 1950", "ca. 1950", "circa 1950", "zirka 1950", "etwa 1950");
    }

    @Test
    void initializesWithoutCsvSeedFiles() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("empty-rules.sqlite"));
        SqliteRuleRepository repository = new SqliteRuleRepository(
                new JdbcTemplate(dataSource),
                tempDir.resolve("missing-rules.csv"),
                tempDir.resolve("missing-tests.csv"),
                new CsvExportService()
        );

        repository.initialize();

        assertThat(repository.findGroupSummaries()).isEmpty();
        assertThat(repository.findTokens()).isNotEmpty();
    }

    @Test
    void createsPreviewInOneConsistentResult() {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Gemeinsame Vorschau");
        RuleGroupForm.RuleVariantForm rule = rule("~aprox ###", "~aprox JJJ", "um 0###", "um 0JJJ");
        rule.getTests().add(test("~aprox 800", "~aprox 800", "um 0800", "0790-01-01/0810-12-31"));
        form.getRules().add(rule);

        RulePreview preview = repository.preview(form);

        assertThat(preview.rules()).isEqualTo(repository.previewRules(form));
        assertThat(preview.tests()).isEqualTo(repository.previewTests(form));
    }

    @Test
    void loadsEditableTestsWithGroupForm() {
        SqliteRuleRepository repository = repository();
        RuleGroupForm form = group("Bearbeitbare Tests");
        RuleGroupForm.RuleVariantForm rule = rule("###", "JJJ", "0###", "0JJJ");
        rule.getTests().add(test("800", "800", "0800", "0790-01-01/0810-12-31"));
        form.getRules().add(rule);
        long groupId = repository.createGroup(form);

        RuleGroupForm loaded = repository.findGroupForm(groupId);
        loaded.getRules().getFirst().getTests().add(new RuleGroupForm.TestForm());

        assertThat(loaded.getRules().getFirst().getTests()).hasSize(2);
    }

    private SqliteRuleRepository repository() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("rules.sqlite"));
        Path rulesCsv = tempDir.resolve("rules.csv");
        Path testsCsv = tempDir.resolve("tests.csv");
        writeSeedCsv(rulesCsv, testsCsv);
        SqliteRuleRepository repository = new SqliteRuleRepository(
                new JdbcTemplate(dataSource),
                rulesCsv,
                testsCsv,
                new CsvExportService()
        );
        repository.initialize();
        return repository;
    }

    private void writeSeedCsv(Path rulesCsv, Path testsCsv) {
        try {
            Files.writeString(rulesCsv, "id,inputMask,inputPattern,outputMask,outputPattern\n", StandardCharsets.UTF_8);
            Files.writeString(testsCsv, "id,for,input,tokenized,output,timespan\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Test-CSV-Dateien konnten nicht erzeugt werden.", e);
        }
    }

    private RuleGroupForm group(String name) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName(name);
        form.setDescription("");
        return form;
    }

    private RuleGroupForm.RuleVariantForm rule(String inputMask, String inputPattern, String outputMask, String outputPattern) {
        RuleGroupForm.RuleVariantForm rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask(inputMask);
        rule.setInputPattern(inputPattern);
        rule.setOutputMask(outputMask);
        rule.setOutputPattern(outputPattern);
        return rule;
    }

    private RuleGroupForm.TestForm test(String input, String tokenized, String output, String timespan) {
        RuleGroupForm.TestForm test = new RuleGroupForm.TestForm();
        test.setInput(input);
        test.setTokenized(tokenized);
        test.setOutput(output);
        test.setTimespan(timespan);
        return test;
    }

    private String zipEntry(byte[] zip, String fileName) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = zipInputStream.getNextEntry(); entry != null; entry = zipInputStream.getNextEntry()) {
                if (entry.getName().equals(fileName)) {
                    return new String(zipInputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }
}
