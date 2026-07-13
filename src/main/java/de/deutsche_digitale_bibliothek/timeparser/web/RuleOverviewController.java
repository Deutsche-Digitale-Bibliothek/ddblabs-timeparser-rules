package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Liefert Listen, Prüfergebnisse und den CSV-Export.
 */
@Controller
@RequiredArgsConstructor
public class RuleOverviewController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final SqliteRuleRepository repository;

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
    public ResponseEntity<byte[]> exportCsv() {
        return download(repository.rulesAndTestsZip(), "timeparser-rules-csv.zip", ZIP);
    }

    private ResponseEntity<byte[]> download(byte[] content, String filename, MediaType mediaType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentLength(content.length)
                .contentType(mediaType)
                .body(content);
    }
}
