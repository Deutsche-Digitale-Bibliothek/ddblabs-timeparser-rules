package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises direct links from generated rows to their editable source templates. */
@SpringBootTest
class RuleNavigationTest {

    private static final String CONTEXT_PATH = "/app/timeparser-rules";
    private static final Path TEST_FILE_PREFIX = Path.of(System.getProperty("java.io.tmpdir"),
            "timeparser-rules-navigation-" + UUID.randomUUID());

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private SqliteRuleRepository repository;

    private final List<Long> groupIds = new ArrayList<>();
    private MockMvc mvc;
    private long tokenId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("timeparser.database.path", () -> TEST_FILE_PREFIX + ".sqlite");
        registry.add("timeparser.csv.rules-path", () -> TEST_FILE_PREFIX + "-missing-rules.csv");
        registry.add("timeparser.csv.tests-path", () -> TEST_FILE_PREFIX + "-missing-tests.csv");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        TokenForm token = new TokenForm();
        token.setName("navigation");
        token.setValues("ca.\num");
        repository.saveToken(token);
        tokenId = repository.findTokens().stream().filter(item -> item.name().equals("navigation"))
                .findFirst().orElseThrow().id();
    }

    @AfterEach
    void cleanUp() {
        groupIds.forEach(repository::deleteGroup);
        repository.deleteToken(tokenId);
    }

    @Test
    void linksGeneratedRulesAndTheirTestsToTheGroupAndOriginalTemplate() throws Exception {
        long groupId = createGroup("Verlinkte Varianten", "R370", "~navigation ####", "~navigation JJJJ");
        for (String id : List.of("R370", "R370_2")) {
            assertLinkedRows(id, groupId, "R370", "Verlinkte Varianten");
        }
        assertThat(rowByPrimaryId(page("/generated-rules"), "R370_2"))
                .contains("Vorlage", "<code>R370</code>").doesNotContain("#rule-R370_2");
    }

    @Test
    void keepsCollidingGeneratedAndStoredIdsLinkedToTheirOwnOrigins() throws Exception {
        long expandedGroup = createGroup("Expandierte Herkunft", "R1", "~navigation ####", "~navigation JJJJ");
        long storedGroup = createGroup("Gespeicherte Herkunft", "R1_2", "###", "JJJ");
        assertThat(repository.generatedRules()).extracting(rule -> rule.getId()).contains("R1_2", "R1_2_2");

        assertLinkedRows("R1_2", expandedGroup, "R1", "Expandierte Herkunft");
        assertLinkedRows("R1_2_2", storedGroup, "R1_2", "Gespeicherte Herkunft");
        assertThat(rowByPrimaryId(page("/generated-rules"), "R1_2"))
                .doesNotContain("href=\"" + templateLink(storedGroup, "R1_2") + "\"");
        assertThat(rowByPrimaryId(page("/generated-rules"), "R1_2_2"))
                .doesNotContain("href=\"" + templateLink(expandedGroup, "R1") + "\"");
    }

    @Test
    void encodesImportedIdsInDirectHtmlLinksAndPreservesTheContextPath() throws Exception {
        String id = "R\u00E4/2?# legacy";
        long groupId = createGroup("Importierte ID", id, "####", "JJJJ");
        for (String path : List.of("/generated-rules", "/generated-tests")) {
            String html = mvc.perform(get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH)
                            .header("Host", "labs.deutsche-digitale-bibliothek.de:443")
                            .header("X-Forwarded-Host", "labs.deutsche-digitale-bibliothek.de")
                            .header("X-Forwarded-Port", "443").header("X-Forwarded-Proto", "https"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<URI> links = Pattern.compile("<a\\b[^>]*href=\"([^\"]+)\"").matcher(html).results()
                    .map(match -> match.group(1))
                    .filter(href -> href.startsWith(CONTEXT_PATH + "/rules/" + groupId + "#"))
                    .map(URI::create).toList();

            assertThat(links).as("Direct imported-ID links on %s", path).isNotEmpty();
            assertThat(links).allSatisfy(target -> {
                assertThat(target.isAbsolute()).isFalse();
                assertThat(target.getPath()).isEqualTo(CONTEXT_PATH + "/rules/" + groupId);
                assertThat(target.getFragment()).isEqualTo("rule-" + id);
            });
        }
    }

    @Test
    void retainsTheSharedNavigationWithoutAnExtraRuleIdSearchForm() throws Exception {
        for (String path : List.of("/rules", "/generated-rules", "/generated-tests", "/tokens", "/plausibility")) {
            assertThat(page(path)).contains("href=\"" + CONTEXT_PATH + "/rules\"",
                            "href=\"" + CONTEXT_PATH + "/generated-rules\"")
                    .doesNotContain("/rules/find", "name=\"ruleId\"", "role=\"search\"");
        }
    }

    @Test
    void keepsGeneratedCsvValuesAndHeadersIndependentOfNavigationLinks() throws Exception {
        createGroup("Unveraenderter Export", "R370", "~navigation ####", "~navigation JJJJ");
        var snapshot = repository.exportSnapshot();
        byte[] rulesBefore = repository.rulesCsv(snapshot);
        byte[] testsBefore = repository.testsCsv(snapshot);
        page("/generated-rules");
        page("/generated-tests");
        mvc.perform(get(CONTEXT_PATH + "/generated-rules/export").contextPath(CONTEXT_PATH).param("download", "true"))
                .andExpect(status().isOk()).andExpect(content().bytes(rulesBefore));
        mvc.perform(get(CONTEXT_PATH + "/generated-tests/export").contextPath(CONTEXT_PATH).param("download", "true"))
                .andExpect(status().isOk()).andExpect(content().bytes(testsBefore));
        assertThat(new String(rulesBefore, StandardCharsets.UTF_8))
                .startsWith("id,inputMask,inputPattern,outputMask,outputPattern").doesNotContain("href", "groupId", "templateId");
        assertThat(new String(testsBefore, StandardCharsets.UTF_8))
                .startsWith("id,for,input,tokenized,output,timespan").doesNotContain("href", "groupId", "templateId");
    }

    private void assertLinkedRows(String generatedId, long groupId, String templateId, String groupName) throws Exception {
        String expectedLink = "href=\"" + templateLink(groupId, templateId) + "\"";
        assertThat(rowByPrimaryId(page("/generated-rules"), generatedId)).contains(expectedLink, groupName);
        var tests = repository.generatedTests().stream().filter(test -> test.getRuleId().equals(generatedId)).toList();
        assertThat(tests).as("Generated tests for %s", generatedId).isNotEmpty();
        String testsPage = page("/generated-tests");
        for (var test : tests) {
            assertThat(rowByPrimaryId(testsPage, test.getId())).contains(expectedLink, groupName,
                    "<code>" + generatedId + "</code>");
        }
    }

    private String rowByPrimaryId(String html, String id) {
        var rows = Pattern.compile("<tr\\b[^>]*>.*?</tr>", Pattern.DOTALL).matcher(html);
        while (rows.find()) {
            String row = rows.group();
            var header = Pattern.compile("<th\\b[^>]*scope=\"row\"[^>]*>(.*?)</th>", Pattern.DOTALL).matcher(row);
            if (header.find() && header.group(1).replaceAll("<[^>]+>", "").strip().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("No generated row with primary ID " + id);
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(CONTEXT_PATH + path).contextPath(CONTEXT_PATH)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private long createGroup(String name, String id, String mask, String pattern) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName(name);
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId(id);
        rule.setInputMask(mask);
        rule.setInputPattern(pattern);
        boolean shortYear = mask.equals("###");
        rule.setOutputMask(shortYear ? "0###" : "####");
        rule.setOutputPattern(shortYear ? "0JJJ" : "JJJJ");
        var test = new RuleGroupForm.TestForm();
        test.setInput(mask.contains("~navigation") ? "~navigation 1950" : shortYear ? "800" : "1950");
        test.setTokenized(test.getInput());
        test.setOutput(shortYear ? "0800" : "1950");
        test.setTimespan(shortYear ? "0800-01-01/0800-12-31" : "1950-01-01/1950-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        long groupId = repository.createGroup(form);
        groupIds.add(groupId);
        return groupId;
    }

    private String templateLink(long groupId, String templateId) {
        return CONTEXT_PATH + "/rules/" + groupId + "#rule-" + templateId;
    }
}
