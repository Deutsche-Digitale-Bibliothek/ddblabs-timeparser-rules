package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.export.ExportDataset;
import de.deutsche_digitale_bibliothek.timeparser.model.ExportSnapshot;
import de.deutsche_digitale_bibliothek.timeparser.model.PlausibilityWarning;
import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuleExportControllerTest {

    private final SqliteRuleRepository repository = mock(SqliteRuleRepository.class);
    private final byte[] download = "export content".getBytes(StandardCharsets.UTF_8);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
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
        mvc = MockMvcBuilders.standaloneSetup(new RuleOverviewController(repository, new DownloadResponseFactory()))
                .setViewResolvers(views).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rules/export", "/generated-rules/export", "/generated-tests/export"})
    void showsFindingsBeforeAnExportWithoutBlockingTheDownload(String path) throws Exception {
        snapshot(true);

        mvc.perform(get("/prefix" + path).contextPath("/prefix"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nicht auflösbarer Platzhalter ~missing.")))
                .andExpect(content().string(containsString("Trotz Hinweisen herunterladen")))
                .andExpect(content().string(containsString(path + "?download=true")))
                .andExpect(content().string(containsString("nicht im Timeparser ausgeführt")))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rules/export", "/generated-rules/export", "/generated-tests/export"})
    void downloadsDespiteFindingsWhenRequested(String path) throws Exception {
        snapshot(true);

        mvc.perform(get(path).param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(download))
                .andExpect(header().string("Content-Disposition", containsString("attachment;")))
                .andExpect(header().string("X-Timeparser-Plausibility-Warnings", "1"))
                .andExpect(header().string("X-Timeparser-Validation", "plausibility-only"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/rules/export", "/generated-rules/export", "/generated-tests/export"})
    void downloadsImmediatelyWhenThereAreNoFindings(String path) throws Exception {
        snapshot(false);

        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().bytes(download))
                .andExpect(header().string("X-Timeparser-Plausibility-Warnings", "0"))
                .andExpect(header().string("X-Timeparser-Validation", "plausibility-only"));
    }

    private void snapshot(boolean withWarnings) {
        var warnings = withWarnings
                ? List.of(new PlausibilityWarning("Regel", List.of(new PlausibilityWarning.PlausibilityEntry("R1", 1, "rule-R1")),
                List.of(), "Nicht auflösbarer Platzhalter ~missing."))
                : List.<PlausibilityWarning>of();
        var snapshot = new ExportSnapshot(new ExportDataset(List.of(), List.of()), warnings, "Prüfbericht");
        when(repository.exportSnapshot()).thenReturn(snapshot);
        when(repository.rulesAndTestsZip(snapshot)).thenReturn(download);
        when(repository.rulesCsv(snapshot)).thenReturn(download);
        when(repository.testsCsv(snapshot)).thenReturn(download);
    }
}
