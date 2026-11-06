package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuleLiteralDiagnosticsTest {

    private static final String INPUT_MASK = "####, #### \u2013 ####";
    private static final String INPUT_PATTERN = "JJJJ, XXXX \u2013 ZZZZ";
    private static final String INPUT = "1945, 1947 \u2013 1949";
    private static final String MISMATCHED_TOKENIZED = "1945, 1947 - 1949";

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;
    private MockMvc mvc;
    private long groupId;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))), tempDir.resolve("missing-rules.csv").toString(),
                tempDir.resolve("missing-tests.csv").toString(), new CsvExportService());
        repository.initialize();
        groupId = repository.createGroup(mismatchedRange());

        var templates = new ClassLoaderTemplateResolver();
        templates.setPrefix("templates/");
        templates.setSuffix(".html");
        templates.setTemplateMode("HTML");
        templates.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(templates);
        var views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");
        mvc = MockMvcBuilders.standaloneSetup(
                        new RuleController(repository, new RuleGroupFormEditor(), new RuleGroupFormValidator(repository)),
                        new RuleOverviewController(repository, new DownloadResponseFactory()))
                .setViewResolvers(views).build();
    }

    @Test
    void rendersTheUnicodeDifferenceAtTheStoredTestFieldWithoutNormalizingValues() throws Exception {
        var result = mvc.perform(get("/rules/{groupId}", groupId)).andExpect(status().isOk()).andReturn();
        assertRenderedMismatch(result);
        for (String suffix : new String[]{"", "/preview"}) {
            var rejected = mvc.perform(rangeRequest("/rules/{groupId}" + suffix, MISMATCHED_TOKENIZED))
                    .andExpect(status().isOk()).andReturn();
            assertRenderedMismatch(rejected);
        }
        assertStoredTokenized(MISMATCHED_TOKENIZED);
    }

    private void assertRenderedMismatch(MvcResult result) throws Exception {
        var model = result.getModelAndView().getModel();
        var errors = (BindingResult) model.get(BindingResult.MODEL_KEY_PREFIX + "groupForm");
        var mismatch = errors.getFieldError("rules[0].tests[0].tokenized");

        assertThat(mismatch).isNotNull();
        assertThat(mismatch.getCode()).isEqualTo("mask.mismatch");
        assertThat(mismatch.getDefaultMessage()).contains("Position 12", "U+2013", "U+002D");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("U+2013", "U+002D", "R344", "T344");
        var rendered = (RuleGroupForm) model.get("groupForm");
        assertThat(rendered.getRules().getFirst().getInputMask()).isEqualTo(INPUT_MASK);
        assertThat(rendered.getRules().getFirst().getTests().getFirst().getTokenized())
                .isEqualTo(MISMATCHED_TOKENIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/plausibility", "/rules/export"})
    void identifiesTheExistingMismatchedTestAndItsRuleInWarningPages(String path) throws Exception {
        var coverage = repository.validationNotices(repository.findGroupForm(groupId)).stream()
                .filter(issue -> issue.code().equals("test.coverage"))
                .findFirst().orElseThrow();
        assertThat(coverage.message()).contains("R344", "T344");

        var result = mvc.perform(get("/prefix" + path).contextPath("/prefix"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Content-Disposition"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("R344", "T344", "U+2013", "U+002D", "/prefix/rules/" + groupId + "#rule-R344");
        assertStoredTokenized(MISMATCHED_TOKENIZED);
    }

    @Test
    void explainsTheOmittedTestInTheDownloadAndExportsItAfterAnExplicitCorrection() throws Exception {
        var before = zipEntries(mvc.perform(get("/rules/export").param("download", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertThat(before.get("rules.csv")).contains("R344", INPUT_MASK);
        assertThat(before.get("tests.csv")).doesNotContain("T344");
        assertThat(before.get("pruefhinweise.txt")).contains("T344", "R344", "U+2013", "U+002D");
        assertStoredTokenized(MISMATCHED_TOKENIZED);

        mvc.perform(rangeRequest("/rules/{groupId}", INPUT))
                .andExpect(status().is3xxRedirection());

        var after = zipEntries(mvc.perform(get("/rules/export").param("download", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(after.get("rules.csv")).isEqualTo(before.get("rules.csv"));
        assertThat(after.get("tests.csv")).contains("T344", INPUT).doesNotContain(MISMATCHED_TOKENIZED);
        assertThat(repository.plausibilityWarningsForGroup(groupId)).isEmpty();
        assertThat(after.get("pruefhinweise.txt")).doesNotContain("T344", "U+002D");
        assertStoredTokenized(INPUT);
    }

    private MockHttpServletRequestBuilder rangeRequest(String path, String tokenized) {
        return post(path, groupId)
                .param("name", "Regel R344 mit abweichendem Testliteral")
                .param("rules[0].id", "R344")
                .param("rules[0].inputMask", INPUT_MASK)
                .param("rules[0].inputPattern", INPUT_PATTERN)
                .param("rules[0].outputMask", "####/####")
                .param("rules[0].outputPattern", "JJJJ/ZZZZ")
                .param("rules[0].tests[0].id", "T344")
                .param("rules[0].tests[0].input", INPUT)
                .param("rules[0].tests[0].tokenized", tokenized)
                .param("rules[0].tests[0].output", "1945/1949")
                .param("rules[0].tests[0].timespan", "1945-01-01/1949-12-31");
    }

    private void assertStoredTokenized(String expected) {
        var rule = repository.findGroupForm(groupId).getRules().getFirst();
        assertThat(rule.getInputMask()).isEqualTo(INPUT_MASK);
        assertThat(rule.getInputPattern()).isEqualTo(INPUT_PATTERN);
        assertThat(rule.getTests().getFirst().getInput()).isEqualTo(INPUT);
        assertThat(rule.getTests().getFirst().getTokenized()).isEqualTo(expected);
    }

    private RuleGroupForm mismatchedRange() {
        var form = new RuleGroupForm();
        form.setName("Regel R344 mit abweichendem Testliteral");
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId("R344");
        rule.setInputMask(INPUT_MASK);
        rule.setInputPattern(INPUT_PATTERN);
        rule.setOutputMask("####/####");
        rule.setOutputPattern("JJJJ/ZZZZ");
        var test = new RuleGroupForm.TestForm();
        test.setId("T344");
        test.setInput(INPUT);
        test.setTokenized(MISMATCHED_TOKENIZED);
        test.setOutput("1945/1949");
        test.setTimespan("1945-01-01/1949-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        return form;
    }

    private Map<String, String> zipEntries(byte[] zip) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
