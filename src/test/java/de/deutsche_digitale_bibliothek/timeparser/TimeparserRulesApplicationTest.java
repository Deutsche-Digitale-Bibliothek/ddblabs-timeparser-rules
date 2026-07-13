package de.deutsche_digitale_bibliothek.timeparser;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void redirectsUseForwardedHttpsScheme() throws Exception {
        mockMvc.perform(get("/app/timeparser-rules/rules")
                        .contextPath("/app/timeparser-rules")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "domain.de")
                        .header("X-Forwarded-Port", "443"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/app/timeparser-rules/login"));
    }
}
