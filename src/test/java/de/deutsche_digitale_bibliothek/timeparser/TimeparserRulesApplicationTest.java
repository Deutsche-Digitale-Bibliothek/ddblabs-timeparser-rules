package de.deutsche_digitale_bibliothek.timeparser;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        registry.add("server.forward-headers-strategy", () -> "framework");
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
    void redirectsUseForwardedHttpsScheme() throws Exception {
        mockMvc.perform(get("/app/timeparser-rules/rules")
                        .contextPath("/app/timeparser-rules")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "domain.de")
                        .header("X-Forwarded-Port", "443"))
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
