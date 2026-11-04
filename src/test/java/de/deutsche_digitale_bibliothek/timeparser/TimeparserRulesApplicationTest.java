package de.deutsche_digitale_bibliothek.timeparser;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class TimeparserRulesApplicationTest {

    private static final Path TEST_FILE_PREFIX = Path.of(
            System.getProperty("java.io.tmpdir"),
            "timeparser-rules-context-" + UUID.randomUUID()
    );

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private SqliteRuleRepository repository;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("timeparser.database.path", () -> TEST_FILE_PREFIX + ".sqlite");
        registry.add("timeparser.csv.rules-path", () -> TEST_FILE_PREFIX + "-missing-rules.csv");
        registry.add("timeparser.csv.tests-path", () -> TEST_FILE_PREFIX + "-missing-tests.csv");
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void contextLoadsWithoutCsvSeedFiles() {
    }

    @Test
    void servesDataTables3AndBootstrapIntegrationAssets() throws Exception {
        mockMvc.perform(get("/webjars/datatables.net/js/dataTables.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("DataTables 3.")));
        mockMvc.perform(get("/webjars/datatables.net-bs5/js/dataTables.bootstrap5.min.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/webjars/datatables.net-bs5/css/dataTables.bootstrap5.min.css"))
                .andExpect(status().isOk());
    }

    @Test
    void servesTheSharedTableConfiguration() throws Exception {
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(get("/js/rule-tables.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("table[data-rule-table]")))
                .andExpect(content().string(containsString("lengthLabels: {'-1': 'Alle'}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rules", "/generated-rules", "/generated-tests", "/tokens", "/plausibility"})
    void rendersSharedNavigationAndTableAssetsWithTheContextPath(String path) throws Exception {
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(get("/app/timeparser-rules" + path).contextPath("/app/timeparser-rules"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/app/timeparser-rules/rules\"")))
                .andExpect(content().string(containsString("action=\"/app/timeparser-rules/logout\"")))
                .andExpect(content().string(containsString("src=\"/app/timeparser-rules/js/rule-tables.js\"")));
    }

    @Test
    void redirectsUnauthenticatedRequestsToLoginWithinTheContextPath() throws Exception {
        mockMvc.perform(get("/app/timeparser-rules/rules")
                        .contextPath("/app/timeparser-rules"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/app/timeparser-rules/login"));
    }

    @Test
    void rendersPlausibilityAndExportWarningsAndStillDownloadsStoredRules() throws Exception {
        var form = new RuleGroupForm();
        form.setName("Fehlerhafte Bestandsregel");
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask("####/####");
        rule.setInputPattern("JJJJ/JJJJ");
        rule.setOutputMask("####/####");
        rule.setOutputPattern("JJJJ/ZZZZ");
        var test = new RuleGroupForm.TestForm();
        test.setInput("1900/2000");
        test.setTokenized("1900/2000");
        test.setOutput("1900/2000");
        test.setTimespan("1900-01-01/2000-12-31");
        rule.getTests().add(test);
        form.getRules().add(rule);
        long groupId = repository.createGroup(form);

        try {
            var applicationMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
            applicationMvc.perform(get("/plausibility"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Die Variable J kommt im Eingabemuster mehrfach vor.")));
            applicationMvc.perform(get("/rules/export"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Trotz Hinweisen herunterladen")));
            applicationMvc.perform(get("/rules/export").param("download", "true"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/zip"))
                    .andExpect(header().string("X-Timeparser-Validation", "plausibility-only"));
        } finally {
            repository.deleteGroup(groupId);
        }
    }

    @Test
    void rendersDuplicateLocationsWithLinksToBothRuleTemplates() throws Exception {
        var form = new RuleGroupForm();
        form.setName("Dublettenfundstelle");
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId("RLocationSource");
        rule.setInputMask("####");
        rule.setInputPattern("JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        form.getRules().add(rule);
        long existingGroupId = repository.createGroup(form);
        rule.setId("RLocationCopy");
        long duplicateGroupId = repository.createGroup(form);

        try {
            MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                    .perform(get("/app/timeparser-rules/plausibility").contextPath("/app/timeparser-rules"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Diese generierte Regel ist bereits vorhanden. Fundstelle: Regel RLocationSource")))
                    .andExpect(content().string(containsString("Regelgruppe " + existingGroupId)))
                    .andExpect(content().string(containsString("href=\"/app/timeparser-rules/rules/" + existingGroupId + "#rule-RLocationSource\"")))
                    .andExpect(content().string(containsString("href=\"/app/timeparser-rules/rules/" + duplicateGroupId + "#rule-RLocationCopy\"")));
        } finally {
            repository.deleteGroup(duplicateGroupId);
            repository.deleteGroup(existingGroupId);
        }
    }

    @Test
    void explainsMonthMarkersAndVariableNamesInTheRuleForm() throws Exception {
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(get("/rules/new"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Die <strong>Eingabemaske</strong> beschreibt die passenden Zeichen")))
                .andExpect(content().string(containsString("NN = 03")))
                .andExpect(content().string(containsString("JJJJ = 2010")))
                .andExpect(content().string(containsString("Die Buchstaben <code>MM</code> erscheinen dabei nicht im Ergebnis.")))
                .andExpect(content().string(containsString("placeholder=\"NN.JJJJ\"")))
                .andExpect(content().string(containsString("placeholder=\"JJJJ-NN\"")))
                .andExpect(content().string(containsString("03.2010 → 2010-03")))
                .andExpect(content().string(containsString("MM #### / NN JJJJ")))
                .andExpect(content().string(containsString("aria-describedby=\"rules0.inputMaskHelp\"")))
                .andExpect(content().string(containsString("id=\"rules0.inputMaskHelp\"")))
                .andExpect(content().string(containsString("aria-describedby=\"rules0.inputPatternHelp\"")))
                .andExpect(content().string(containsString("id=\"rules0.inputPatternHelp\"")));
    }

    @Test
    void validatesStoredRulesOnOpenAndSavesErrorsOnlyAfterExplicitConsent() throws Exception {
        RuleGroupForm form = new RuleGroupForm();
        form.setName("Ungültige Bestandsregel");
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setInputMask("####/####");
        rule.setInputPattern("JJJJ/JJJJ");
        rule.setOutputMask("####");
        rule.setOutputPattern("JJJJ");
        var test = new RuleGroupForm.TestForm();
        test.setInput("1900/2000");
        test.setTokenized("1900/2000");
        test.setOutput("1900");
        rule.getTests().add(test);
        form.getRules().add(rule);
        long groupId = repository.createGroup(form);
        var applicationMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        try {
            var opened = applicationMvc.perform(get("/rules/" + groupId))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("groupForm", "rules[0].inputPattern"))
                    .andExpect(content().string(containsString("Die Variable J kommt im Eingabemuster mehrfach vor.")))
                    .andExpect(content().string(containsString("Fehler akzeptieren und trotzdem speichern")))
                    .andReturn();
            String html = opened.getResponse().getContentAsString();
            // Die Fehlerfreigabe darf nicht der erste Submit-Button und damit
            // das implizite Ziel der Eingabetaste im Formular sein.
            assertThat(html.indexOf("Vorschau aktualisieren"))
                    .isLessThan(html.indexOf("Fehler akzeptieren und trotzdem speichern"));
            RuleGroupForm draft = (RuleGroupForm) opened.getModelAndView().getModel().get("groupForm");
            draft.setName("Ausdrücklich freigegebene Bestandsregel");

            var blocked = applicationMvc.perform(ruleSaveRequest(groupId, draft))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("groupForm", "rules[0].inputPattern"))
                    .andReturn();
            assertThat(repository.findGroupForm(groupId).getName()).isEqualTo("Ungültige Bestandsregel");
            String review = (String) blocked.getModelAndView().getModel().get("invalidSaveReview");
            assertThat(review).isNotBlank();

            applicationMvc.perform(ruleSaveRequest(groupId, draft)
                            .param("validationReview", review).param("saveInvalid", "true"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(flash().attribute("saveWarning", "Regelgruppe wurde mit Plausibilitätsfehlern gespeichert."));
            assertThat(repository.findGroupForm(groupId).getName()).isEqualTo(draft.getName());

            applicationMvc.perform(get("/rules/" + groupId))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("groupForm", "rules[0].inputPattern"));
            assertThat(repository.findGroupForm(groupId).isSaveInvalid()).isFalse();
        } finally {
            repository.deleteGroup(groupId);
        }
    }

    private MockHttpServletRequestBuilder ruleSaveRequest(long groupId, RuleGroupForm form) {
        var request = post("/rules/" + groupId).param("id", Long.toString(groupId))
                .param("name", form.getName()).param("description", "");
        var rule = form.getRules().getFirst();
        request.param("rules[0].id", rule.getId())
                .param("rules[0].inputMask", rule.getInputMask()).param("rules[0].inputPattern", rule.getInputPattern())
                .param("rules[0].outputMask", rule.getOutputMask()).param("rules[0].outputPattern", rule.getOutputPattern());
        var test = rule.getTests().getFirst();
        return request.param("rules[0].tests[0].id", test.getId())
                .param("rules[0].tests[0].input", test.getInput()).param("rules[0].tests[0].tokenized", test.getTokenized())
                .param("rules[0].tests[0].output", test.getOutput()).param("rules[0].tests[0].timespan", "");
    }

    @Test
    void rendersRequiredRuleMessageForBlankPreview() throws Exception {
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(post("/rules/new/preview"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mindestens eine Regel ist erforderlich.")));
    }

    @Test
    void rendersRequiredTestMessageForPreviewWithoutTests() throws Exception {
        MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
                .perform(post("/rules/new/preview")
                        .param("name", "Regel ohne Tests")
                        .param("rules[0].inputMask", "####")
                        .param("rules[0].inputPattern", "JJJJ")
                        .param("rules[0].outputMask", "####")
                        .param("rules[0].outputPattern", "JJJJ"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mindestens ein Test ist erforderlich.")));
    }
}
