package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Liefert Listen, Prüfergebnisse sowie die CSV- und ZIP-Exporte.
 */
@Controller
@RequiredArgsConstructor
public class RuleOverviewController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final SqliteRuleRepository repository;
    private final DownloadResponseFactory downloadResponseFactory;

    @GetMapping("/rules")
    public String listRules(Model model) {
        model.addAttribute("groups", repository.findGroupSummaries());
        return "rules/list";
    }

    @GetMapping("/generated-rules")
    public String generatedRules(Model model) {
        model.addAttribute("rules", repository.generatedRules());
        return "rules/generated-rules";
    }

    @GetMapping("/generated-tests")
    public String generatedTests(Model model) {
        model.addAttribute("tests", repository.generatedTests());
        return "rules/generated-tests";
    }

    @GetMapping("/plausibility")
    public String plausibility(Model model) {
        model.addAttribute("plausibilityWarnings", repository.plausibilityWarnings());
        return "rules/plausibility";
    }

    @GetMapping("/rules/export")
    public ResponseEntity<byte[]> exportPackage() {
        return downloadResponseFactory.download(
                repository.rulesAndTestsZip(),
                "timeparser-rules-complete",
                "zip",
                ZIP
        );
    }

    @GetMapping("/generated-rules/export")
    public ResponseEntity<byte[]> exportRules() {
        return downloadResponseFactory.download(repository.rulesCsv(), "timeparser-rules", "csv", CSV);
    }

    @GetMapping("/generated-tests/export")
    public ResponseEntity<byte[]> exportTests() {
        return downloadResponseFactory.download(repository.testsCsv(), "timeparser-tests", "csv", CSV);
    }
}
