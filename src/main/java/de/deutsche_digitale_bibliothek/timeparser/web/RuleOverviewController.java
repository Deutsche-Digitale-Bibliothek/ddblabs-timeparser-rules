package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Liefert Listen, Prüfergebnisse sowie die CSV- und ZIP-Exporte.
 *
 * <p>Die erzeugten Listen zeigen expandierte Vorlagen; der Export verwendet
 * dagegen einen {@link de.deutsche_digitale_bibliothek.timeparser.model.ExportSnapshot}
 * mit zusammengeführten exakten Dubletten, umgehängten Testreferenzen und dem
 * zugehörigen Prüfbericht. Ein Snapshot wird für jeden Download neu erstellt.</p>
 *
 * <p>Plausibilitätsbefunde verhindern den Export nicht. Ohne den Parameter
 * {@code download=true} werden vorhandene Befunde zunächst als HTML angezeigt.
 * Der explizite Download erzeugt erneut einen aktuellen Snapshot, statt einen
 * möglicherweise veralteten Stand der Hinweisseite wiederzuverwenden. Die
 * Response-Header {@code X-Timeparser-Plausibility-Warnings} und
 * {@code X-Timeparser-Validation} benennen Umfang und Grenze der Prüfung.</p>
 */
@Controller
@RequiredArgsConstructor
public class RuleOverviewController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final SqliteRuleRepository repository;
    private final DownloadResponseFactory downloadResponseFactory;

    /** Zeigt die bearbeitbaren Gruppen, nicht deren expandierte CSV-Zeilen. */
    @GetMapping("/rules")
    public String listRules(Model model) {
        model.addAttribute("groups", repository.findGroupSummaries());
        return "rules/list";
    }

    /** Zeigt erzeugte Regelvarianten sowie Hinweise zu derzeitigen Bestandsfehlern. */
    @GetMapping("/generated-rules")
    public String generatedRules(Model model) {
        var listing = repository.generatedRuleListing();
        model.addAttribute("rules", listing.rules());
        model.addAttribute("ruleLocationsById", listing.locations());
        model.addAttribute("plausibilityWarnings", repository.plausibilityWarnings());
        return "rules/generated-rules";
    }

    /** Zeigt passende expandierte Testvorlagen; die Testfälle werden nicht ausgeführt. */
    @GetMapping("/generated-tests")
    public String generatedTests(Model model) {
        var listing = repository.generatedTestListing();
        model.addAttribute("tests", listing.tests());
        model.addAttribute("ruleLocationsById", listing.locations());
        model.addAttribute("plausibilityWarnings", repository.plausibilityWarnings());
        return "rules/generated-tests";
    }

    /** Zeigt gemeinsame statische Fehler- und Hinweismeldungen für den gespeicherten Bestand. */
    @GetMapping("/plausibility")
    public String plausibility(Model model) {
        model.addAttribute("plausibilityWarnings", repository.plausibilityWarnings());
        return "rules/plausibility";
    }

    /**
     * Exportiert zusammengehörige Regeln, Tests und {@code pruefhinweise.txt} als ZIP.
     *
     * @param download ob der Download auch bei vorhandenen Befunden angefordert ist
     * @param model Befunde und Downloadpfad für die vorgeschaltete Hinweisseite
     * @return Name der Hinweisseite oder binäre Download-Antwort
     */
    @GetMapping("/rules/export")
    public Object exportPackage(@RequestParam(defaultValue = "false") boolean download, Model model) {
        return export(download, model, ExportFormat.PACKAGE);
    }

    /**
     * Liefert die Regel-CSV aus derselben Aufbereitung wie der ZIP-Export.
     *
     * @param download ob vorhandene Befunde die Hinweisseite überspringen sollen
     * @param model Attribute für die Hinweisseite
     * @return Name der Hinweisseite oder CSV-Download-Antwort
     */
    @GetMapping("/generated-rules/export")
    public Object exportRules(@RequestParam(defaultValue = "false") boolean download, Model model) {
        return export(download, model, ExportFormat.RULES);
    }

    /**
     * Liefert die Test-CSV mit Referenzen auf die aufbereiteten Export-Regel-IDs.
     * Zwischen zwei getrennten Downloads kann sich der Datenbestand ändern;
     * einen gemeinsamen Stand gewährleistet der ZIP-Download.
     *
     * @param download ob vorhandene Befunde die Hinweisseite überspringen sollen
     * @param model Attribute für die Hinweisseite
     * @return Name der Hinweisseite oder CSV-Download-Antwort
     */
    @GetMapping("/generated-tests/export")
    public Object exportTests(@RequestParam(defaultValue = "false") boolean download, Model model) {
        return export(download, model, ExportFormat.TESTS);
    }

    /**
     * Zentralisiert die Entscheidung zwischen Hinweisansicht und Dateiausgabe.
     * Auch der direkte Download trägt die aktuelle Hinweisanzahl und den
     * ausdrücklichen Status {@code plausibility-only}; es erfolgt keine
     * Ausführung im Timeparser.
     */
    private Object export(boolean download, Model model, ExportFormat format) {
        var snapshot = repository.exportSnapshot();
        if (!download && !snapshot.warnings().isEmpty()) {
            model.addAttribute("exportTitle", format.title);
            model.addAttribute("exportPath", format.path);
            model.addAttribute("exportSnapshot", snapshot);
            model.addAttribute("plausibilityWarnings", snapshot.warnings());
            return "rules/export";
        }
        byte[] content = switch (format) {
            case PACKAGE -> repository.rulesAndTestsZip(snapshot);
            case RULES -> repository.rulesCsv(snapshot);
            case TESTS -> repository.testsCsv(snapshot);
        };
        var response = downloadResponseFactory.download(content, format.baseName, format.extension, format.mediaType);
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .header("X-Timeparser-Plausibility-Warnings", Integer.toString(snapshot.warnings().size()))
                .header("X-Timeparser-Validation", "plausibility-only")
                .body(response.getBody());
    }

    /** Hält Dateinamen, Medientyp und Rücksprungpfad der drei Exportwege zusammen. */
    private enum ExportFormat {
        PACKAGE("ZIP mit Regeln und Tests", "/rules/export", "timeparser-rules-complete", "zip", ZIP),
        RULES("Regeln als CSV", "/generated-rules/export", "timeparser-rules", "csv", CSV),
        TESTS("Tests als CSV", "/generated-tests/export", "timeparser-tests", "csv", CSV);

        private final String title;
        private final String path;
        private final String baseName;
        private final String extension;
        private final MediaType mediaType;

        ExportFormat(String title, String path, String baseName, String extension, MediaType mediaType) {
            this.title = title;
            this.path = path;
            this.baseName = baseName;
            this.extension = extension;
            this.mediaType = mediaType;
        }
    }
}
