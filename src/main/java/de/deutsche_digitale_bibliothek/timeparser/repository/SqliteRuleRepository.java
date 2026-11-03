package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.export.ExportDataset;
import de.deutsche_digitale_bibliothek.timeparser.export.ExportDatasetFactory;
import de.deutsche_digitale_bibliothek.timeparser.model.ExportSnapshot;
import de.deutsche_digitale_bibliothek.timeparser.model.PlausibilityWarning;
import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleGroupSummary;
import de.deutsche_digitale_bibliothek.timeparser.model.RulePreview;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.model.Token;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.TokenForm;
import de.deutsche_digitale_bibliothek.timeparser.validation.RuleGroupValidator;
import de.deutsche_digitale_bibliothek.timeparser.validation.RuleMaskOverlap;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue;
import jakarta.annotation.PostConstruct;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speichert Regelvorlagen in SQLite und koordiniert deren Expansion, Prüfung und Export.
 *
 * <p>Gespeichert werden die vom Benutzer bearbeiteten Masken, Muster und Testwerte,
 * einschließlich Platzhaltern wie {@code ~approx}. Konkrete CSV-Zeilen entstehen erst
 * beim Lesen durch die Kombination der aktuellen Platzhalterwerte. Diese abgeleiteten
 * Zeilen und ihre IDs werden nicht zurück in die Vorlagen geschrieben.</p>
 *
 * <p>Die fachlichen Prüfungen sind bewusst in zwei Aufrufwege getrennt:
 * {@link #validationIssues(RuleGroupForm)} liefert Fehler für das Speichern und die
 * Formularvorschau; {@link #validationNotices(RuleGroupForm)} liefert unverbindliche
 * Hinweise auf fehlende Testabdeckung und mögliche Maskenüberschneidungen. Der
 * Plausibilitätscheck führt beide Ergebnisse für bereits gespeicherte Gruppen zusammen.
 * Die eigentlichen Masken- und Feldprüfungen liegen in {@link RulePatternValidator}
 * und {@link RuleGroupValidator}. Testfälle werden hier nicht im Timeparser ausgeführt.</p>
 *
 * <p>Für zusammengehörige Exportdateien ist zunächst {@link #exportSnapshot()} aufzurufen.
 * Der Snapshot bündelt Daten, Befunde und Bericht. Die Serialisierungsüberladungen
 * mit einem {@link ExportSnapshot} lesen anschließend keine neuen Datenbankwerte.
 * Exportbefunde verhindern den Download nicht; nicht expandierbare Vorlagen werden
 * mit einem ausdrücklichen Hinweis ausgelassen.</p>
 */
@Repository
public class SqliteRuleRepository {

    private static final String[] RULE_HEADERS = {"id", "inputMask", "inputPattern", "outputMask", "outputPattern"};
    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("^(\\D*)(\\d+)$");
    private static final Pattern TOKEN_REFERENCE = Pattern.compile("~([A-Za-z][A-Za-z0-9_-]*)");
    private static final Comparator<String> ID_COMPARATOR = NaturalIdComparator.INSTANCE;

    private final JdbcTemplate jdbcTemplate;
    private final Path rulesFile;
    private final Path testsFile;
    private final CsvExportService csvExportService;

    /**
     * Verbindet Persistenz, optionale Seed-Dateien und die CSV-Serialisierung.
     *
     * @param jdbcTemplate Datenbankzugriff auf die konfigurierte SQLite-Datei
     * @param rulesFile Pfad zur optionalen Regel-CSV für den ersten Import
     * @param testsFile Pfad zur zugehörigen optionalen Test-CSV
     * @param csvExportService Serializer für bereits aufbereitete Exportzeilen
     */
    @Autowired
    public SqliteRuleRepository(JdbcTemplate jdbcTemplate,
                                @Value("${timeparser.csv.rules-path:rules.csv}") String rulesFile,
                                @Value("${timeparser.csv.tests-path:tests.csv}") String testsFile,
                                CsvExportService csvExportService) {
        this(jdbcTemplate, Path.of(rulesFile), Path.of(testsFile), csvExportService);
    }

    SqliteRuleRepository(JdbcTemplate jdbcTemplate,
                         Path rulesFile,
                         Path testsFile,
                         CsvExportService csvExportService) {
        this.jdbcTemplate = jdbcTemplate;
        this.rulesFile = rulesFile;
        this.testsFile = testsFile;
        this.csvExportService = csvExportService;
    }

    /**
     * Legt Schema und Standardplatzhalter an und migriert ältere Testtabellen.
     * CSV-Dateien werden nur importiert, wenn keine Gruppen vorhanden sind und
     * beide Seed-Dateien existieren. Ein fehlender Seed ist kein Startfehler;
     * bestehende Regelgruppen werden durch den Start nicht erneut importiert.
     */
    @PostConstruct
    @Transactional
    public void initialize() {
        // Reihenfolge ist wichtig: Schema und Migration müssen vor Seed/Import stehen.
        createSchema();
        migrateTestsSchema();
        seedTokens();
        if (countGroups() == 0 && csvSeedFilesAvailable()) {
            importCsv();
        }
    }

    /**
     * Liefert die Gruppenübersicht mit Anzahlen gespeicherter Vorlagen.
     * Die Anzahlen berücksichtigen keine durch Platzhalter zusätzlich erzeugten Zeilen.
     *
     * @return nach Gruppen-ID sortierte Übersicht, bei leerer Datenbank eine leere Liste
     */
    public List<RuleGroupSummary> findGroupSummaries() {
        return jdbcTemplate.query("""
                SELECT g.id,
                       g.name,
                       g.description,
                       COUNT(DISTINCT r.id) AS rule_count,
                       COUNT(t.id) AS test_count
                FROM rule_groups g
                LEFT JOIN rules r ON r.group_id = g.id
                LEFT JOIN tests t ON t.rule_id = r.id
                GROUP BY g.id, g.name, g.description
                ORDER BY g.id
                """, (rs, rowNum) -> new RuleGroupSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getLong("rule_count"),
                rs.getLong("test_count")
        ));
    }

    /**
     * Lädt eine bearbeitbare Gruppe einschließlich ihrer unexpandierten Testvorlagen.
     * Regeln und Tests behalten die gespeicherte Positionsreihenfolge; Platzhalter
     * und fachlich relevante Leerzeichen bleiben erhalten.
     *
     * @param groupId technische ID einer vorhandenen Gruppe
     * @return neues Formularobjekt mit den gespeicherten IDs und Werten
     * @throws org.springframework.dao.EmptyResultDataAccessException wenn die Gruppe fehlt
     */
    public RuleGroupForm findGroupForm(long groupId) {
        Map<String, Object> group = jdbcTemplate.queryForMap(
                "SELECT id, name, description FROM rule_groups WHERE id = ?",
                groupId
        );
        RuleGroupForm form = new RuleGroupForm();
        form.setId(((Number) group.get("id")).longValue());
        form.setName((String) group.get("name"));
        form.setDescription((String) group.get("description"));

        Map<String, List<RuleGroupForm.TestForm>> testsByRuleId = findTestFormsForGroup(groupId);
        List<RuleGroupForm.RuleVariantForm> rules = jdbcTemplate.query("""
                SELECT id, input_mask, input_pattern, output_mask, output_pattern
                FROM rules
                WHERE group_id = ?
                ORDER BY position, id
                """, (rs, rowNum) -> {
            RuleGroupForm.RuleVariantForm rule = new RuleGroupForm.RuleVariantForm();
            rule.setId(rs.getString("id"));
            rule.setInputMask(rs.getString("input_mask"));
            rule.setInputPattern(rs.getString("input_pattern"));
            rule.setOutputMask(rs.getString("output_mask"));
            rule.setOutputPattern(rs.getString("output_pattern"));
            rule.setTests(new ArrayList<>(testsByRuleId.getOrDefault(rule.getId(), List.of())));
            return rule;
        }, groupId);
        form.setRules(rules);
        return form;
    }

    /**
     * @param groupId zu prüfende Gruppen-ID
     * @return ob die Gruppe gespeichert ist
     */
    public boolean groupExists(long groupId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rule_groups WHERE id = ?", Integer.class, groupId);
        return count != null && count > 0;
    }

    /**
     * Prüft die Regelzugehörigkeit, etwa vor einer gruppenbezogenen Controlleraktion.
     *
     * @param groupId erwartete Gruppe
     * @param ruleId ID der gespeicherten Regelvorlage, keine generierte Varianten-ID
     * @return ob genau diese Regel in der Gruppe gespeichert ist
     */
    public boolean ruleExists(long groupId, String ruleId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rules
                WHERE group_id = ? AND id = ?
                """, Integer.class, groupId, ruleId);
        return count != null && count > 0;
    }

    /**
     * Speichert eine neue Gruppe mit ihren Regel- und Testvorlagen.
     * Fachliche Formularprüfung und Entfernung leerer Hilfszeilen erfolgen beim Aufrufer.
     * Fehlende Regel-/Test-IDs werden vergeben, aber nicht in {@code form} zurückgeschrieben.
     *
     * @param form zuvor geprüftes Formular; Platzhalter bleiben als Vorlagentext erhalten
     * @return Datenbank-ID der neu angelegten Gruppe
     */
    @Transactional
    public long createGroup(RuleGroupForm form) {
        long groupId = insertGroup(form.getName(), form.getDescription());
        saveRulesForGroup(groupId, form.getRules());
        return groupId;
    }

    /**
     * Ersetzt die Regeln und Tests einer vorhandenen Gruppe durch den Formularstand.
     * Die zugehörigen Test- und Regelzeilen werden gelöscht und in Formularreihenfolge
     * neu eingefügt; mitgegebene IDs bleiben erhalten, fehlende IDs werden vergeben.
     *
     * @param form bereinigtes, fachlich geprüftes Formular mit vorhandener Gruppen-ID
     */
    @Transactional
    public void updateGroup(RuleGroupForm form) {
        jdbcTemplate.update(
                "UPDATE rule_groups SET name = ?, description = ? WHERE id = ?",
                form.getName(),
                form.getDescription(),
                form.getId()
        );
        jdbcTemplate.update("""
                DELETE FROM tests
                WHERE rule_id IN (SELECT id FROM rules WHERE group_id = ?)
                """, form.getId());
        jdbcTemplate.update("DELETE FROM rules WHERE group_id = ?", form.getId());
        saveRulesForGroup(form.getId(), form.getRules());
    }

    /**
     * Entfernt eine Gruppe und ihre gespeicherten Regeln und Tests.
     *
     * @param groupId ID der zu entfernenden Gruppe
     */
    @Transactional
    public void deleteGroup(long groupId) {
        jdbcTemplate.update("""
                DELETE FROM tests
                WHERE rule_id IN (SELECT id FROM rules WHERE group_id = ?)
                """, groupId);
        jdbcTemplate.update("DELETE FROM rules WHERE group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM rule_groups WHERE id = ?", groupId);
    }

    /**
     * Entfernt eine Regelvorlage einschließlich Tests, sofern die Gruppe weitere Regeln hat.
     * Der Controller prüft vor dem Aufruf Existenz und Gruppenzugehörigkeit.
     *
     * @param groupId zugehörige Gruppe
     * @param ruleId ID der gespeicherten Regelvorlage
     * @throws CsvRepositoryException wenn die Gruppe höchstens eine Regel enthält
     */
    @Transactional
    public void deleteRule(long groupId, String ruleId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rules WHERE group_id = ?", Integer.class, groupId);
        if (count != null && count <= 1) {
            throw new CsvRepositoryException("Eine Regelgruppe muss mindestens eine Regel haben.");
        }
        jdbcTemplate.update("DELETE FROM tests WHERE rule_id = ?", ruleId);
        jdbcTemplate.update("DELETE FROM rules WHERE id = ? AND group_id = ?", ruleId, groupId);
    }

    /**
     * Entfernt eine Testvorlage, sofern die Regel mindestens einen weiteren Test hat.
     * Der Löschzugriff wird zusätzlich auf die angegebene Gruppe und Regel eingeschränkt.
     *
     * @param groupId Gruppe der Regel
     * @param ruleId gespeicherte Regel-ID
     * @param testId gespeicherte Test-ID
     * @throws CsvRepositoryException wenn die Regel höchstens einen Test enthält
     */
    @Transactional
    public void deleteTest(long groupId, String ruleId, String testId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tests WHERE rule_id = ?", Integer.class, ruleId);
        if (count != null && count <= 1) {
            throw new CsvRepositoryException("Eine Regel muss mindestens einen Test haben.");
        }
        jdbcTemplate.update("""
                DELETE FROM tests
                WHERE id = ?
                  AND rule_id = ?
                  AND EXISTS (SELECT 1 FROM rules WHERE rules.id = tests.rule_id AND rules.group_id = ?)
                """, testId, ruleId, groupId);
    }

    /**
     * @param groupId erwartete Gruppe
     * @param ruleId erwartete gespeicherte Regel
     * @param testId zu prüfende gespeicherte Test-ID
     * @return ob der Test über die Regel zu genau dieser Gruppe gehört
     */
    public boolean testExists(long groupId, String ruleId, String testId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM tests t
                JOIN rules r ON r.id = t.rule_id
                WHERE r.group_id = ? AND r.id = ? AND t.id = ?
                """, Integer.class, groupId, ruleId, testId);
        return count != null && count > 0;
    }

    /**
     * Expandiert alle auflösbaren gespeicherten Regelvorlagen für die Übersicht.
     * Exakte Dubletten bleiben hier sichtbar. Vorlagen mit unbekannten Platzhaltern
     * fehlen in dieser Liste und werden im Plausibilitätscheck gesondert gemeldet.
     *
     * @return konkrete Regeln, natürlich nach ID sortiert, noch nicht exportbereinigt
     */
    public List<Rule> generatedRules() {
        return safeGeneratedRuleRows(ruleTemplates((Long) null), tokenValuesByName()).stream()
                .map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR))
                .toList();
    }

    /**
     * @param groupId auf diese Gruppe einzuschränkende Vorlagen
     * @return auflösbare generierte Regeln der Gruppe einschließlich möglicher Dubletten
     * @see #generatedRules()
     */
    public List<Rule> generatedRulesForGroup(long groupId) {
        return safeGeneratedRuleRows(ruleTemplates(groupId), tokenValuesByName()).stream()
                .map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR))
                .toList();
    }

    /**
     * Erzeugt konkrete Tests für die auflösbaren Regelvarianten aller Gruppen.
     * Nur Kombinationen mit passender tokenisierter Eingabe werden übernommen;
     * dies ist keine Ausführung oder Erfolgsbewertung der Testfälle.
     *
     * @return nach Test-ID sortierte, noch nicht für den Export deduplizierte Tests
     */
    public List<RuleTest> generatedTests() {
        return generatedTests(null);
    }

    /**
     * @param groupId einzuschränkende Gruppe
     * @return generierte Tests der auflösbaren Regelvarianten dieser Gruppe
     * @see #generatedTests()
     */
    public List<RuleTest> generatedTestsForGroup(long groupId) {
        return generatedTests(groupId);
    }

    /**
     * Expandiert vollständige Regelzeilen eines noch nicht gespeicherten Formularstands.
     * Es werden keine Daten gespeichert und keine Dubletten entfernt.
     *
     * @param form aktueller Formularstand
     * @return konkrete Vorschauzeilen mit vorläufigen IDs für neue Vorlagen
     * @throws CsvRepositoryException wenn ein verwendeter Platzhalter nicht auflösbar ist
     */
    public List<Rule> previewRules(RuleGroupForm form) {
        return generatedRuleRows(ruleTemplates(form), tokenValuesByName()).stream()
                .map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR))
                .toList();
    }

    /**
     * @param form aktueller Formularstand mit Regel- und Testvorlagen
     * @return generierbare Testwerte; unvollständige Testzeilen werden ignoriert
     * @throws CsvRepositoryException wenn ein verwendeter Platzhalter nicht auflösbar ist
     * @see #preview(RuleGroupForm)
     */
    public List<RuleTest> previewTests(RuleGroupForm form) {
        return generatedTests(ruleTemplates(form), testTemplates(form), tokenValuesByName());
    }

    /**
     * Erzeugt Regeln und Tests gemeinsam mit derselben gelesenen Platzhalterbelegung.
     * So passen die vorläufigen Regel-IDs und die Testreferenzen innerhalb der Vorschau
     * zusammen. Vollständigkeit, fachliche Fehler und Hinweise sind separat zu prüfen.
     *
     * @param form nicht persistierter oder bearbeiteter Formularstand
     * @return zusammengehörige Regeln und Tests ohne Export-Deduplizierung
     * @throws CsvRepositoryException wenn eine Vorlage unbekannte Platzhalter verwendet
     */
    public RulePreview preview(RuleGroupForm form) {
        Map<String, List<String>> tokenValues = tokenValuesByName();
        List<GeneratedRuleRow> generatedRuleRows = generatedRuleRows(ruleTemplates(form), tokenValues);
        List<Rule> rules = generatedRuleRows.stream()
                .map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR))
                .toList();
        List<RuleTest> tests = generateTestsForRows(generatedRuleRows, testTemplates(form), tokenValues);
        return new RulePreview(rules, tests);
    }

    /**
     * @return Fehler, unverbindliche Hinweise und Dublettenfundstellen aller Gruppen
     * @see #validationIssues(RuleGroupForm)
     * @see #validationNotices(RuleGroupForm)
     */
    @Transactional(readOnly = true)
    public List<PlausibilityWarning> plausibilityWarnings() {
        return plausibilityWarnings(null);
    }

    /**
     * Prüft eine gespeicherte Gruppe; Konflikte mit anderen Gruppen werden mitbetrachtet.
     *
     * @param groupId zu prüfende vorhandene Gruppe
     * @return Befunde mit Verweisen auf die betroffenen Vorlagen dieser Gruppe
     */
    @Transactional(readOnly = true)
    public List<PlausibilityWarning> plausibilityWarningsForGroup(long groupId) {
        return plausibilityWarnings(groupId);
    }

    /**
     * Prüft Vorlagen und alle erzeugbaren konkreten Varianten gegen den aktuellen Bestand.
     * Die gespeicherte Fassung einer bearbeiteten Gruppe wird vom Vergleich ausgenommen.
     * Feldpfade wie {@code rules[0].tests[1].output} beziehen sich stets auf das Formular,
     * auch wenn der Fehler erst nach der Platzhalterexpansion auffällt.
     *
     * @param form aktueller Formularstand, bei Bearbeitung mit seiner Gruppen-ID
     * @return Befunde einmal je Feldpfad und Prüfcode; der zuerst erfasste Text bleibt erhalten
     * @see #validationNotices(RuleGroupForm)
     */
    public List<Issue> validationIssues(RuleGroupForm form) {
        Map<String, List<String>> tokens = tokenValuesByName();
        ValidationContext context = validationContext(tokens);
        return validationIssues(form, tokens, context, null);
    }

    /**
     * Ermittelt fehlende Testabdeckung je erzeugter Variante und mögliche Überschneidungen
     * gleich spezifischer Masken. Die statische Prüfung sagt keine tatsächliche
     * Mehrdeutigkeit der späteren Timeparser-Auswahl voraus.
     *
     * @param form aktueller Formularstand
     * @return Hinweise für die Oberfläche; diese werden nicht als Feldfehler gebunden
     */
    public List<Issue> validationNotices(RuleGroupForm form) {
        Map<String, List<String>> tokens = tokenValuesByName();
        ValidationContext context = validationContext(tokens);
        List<GeneratedRuleRow> rows = safeGeneratedRuleRows(ruleTemplates(form), tokens);
        Set<String> testedIds = new HashSet<>(safeGeneratedTests(rows, testTemplates(form), tokens).stream()
                .map(RuleTest::getRuleId).toList());
        return validationNotices(form, context, rows, testedIds);
    }

    /** Verwendet Vorlagenzuordnung und generierte Regel-IDs für Abdeckungshinweise je Variante. */
    private List<Issue> validationNotices(RuleGroupForm form, ValidationContext context,
                                          List<GeneratedRuleRow> rows, Set<String> testedIds) {
        Map<MaskShape, List<GeneratedRuleRow>> previousRows = new LinkedHashMap<>();
        Map<String, Integer> ruleIndices = new LinkedHashMap<>();
        for (int index = 0; index < form.getRules().size(); index++) {
            ruleIndices.putIfAbsent(previewRuleId(form.getRules().get(index), index), index);
        }
        Set<Issue> notices = new LinkedHashSet<>();
        for (GeneratedRuleRow row : rows) {
            Integer index = ruleIndices.get(row.templateId());
            if (index == null || !isCompleteRule(form.getRules().get(index))) {
                continue;
            }
            String prefix = "rules[" + index + "].";
            if (!testedIds.contains(row.rule().getId())) {
                notices.add(new Issue(prefix + "tests", "test.coverage",
                        "Für die erzeugte Variante " + row.rule().getId() + " [" + visibleWhitespace(row.rule().getInputMask())
                                + "] wird kein passender Test exportiert."));
            }
            MaskShape shape = maskShape(row.rule().getInputMask());
            List<GeneratedRuleRow> candidates = new ArrayList<>();
            for (GeneratedRuleRow other : context.rowsByShape.getOrDefault(shape, List.of())) {
                if (form.getId() == null || other.groupId() != form.getId()) {
                    candidates.add(other);
                }
            }
            candidates.addAll(previousRows.getOrDefault(shape, List.of()));
            for (GeneratedRuleRow other : candidates) {
                RuleMaskOverlap.witness(row.rule().getInputMask(), other.rule().getInputMask()).ifPresent(example ->
                        notices.add(new Issue(prefix + "inputMask", "rule.mask.overlap",
                                "Die Eingabemaske könnte sich mit " + other.rule().getId() + " überschneiden (Beispiel: "
                                        + visibleWhitespace(example) + "). Beide haben gleich viele #-Stellen. Die tatsächliche Regelauswahl ist nicht geprüft.")));
            }
            previousRows.computeIfAbsent(shape, ignored -> new ArrayList<>()).add(row);
        }
        return List.copyOf(notices);
    }

    /**
     * Ergänzt die strukturelle Formularprüfung um Prüfungen expandierter Werte.
     * Die eigene gespeicherte Gruppe wird ausgeschlossen, damit eine unveränderte
     * Bearbeitung nicht als Dublette ihrer Datenbankfassung gilt. Testsignaturen
     * enthalten die Regelsignatur: gleiche Beispiele an unterschiedlichen Regeln
     * sind zulässig und erhalten ihre jeweilige Zuordnung.
     *
     * <p>{@code formRows} enthält beim Bestandscheck die bereits gemeinsam
     * expandierten Zeilen mit ihren tatsächlichen Varianten-IDs. Bei einem
     * bearbeiteten Formular ist die Liste {@code null}: Jede Zeile wird dort
     * einzeln expandiert, weil vorläufige IDs neuer Zeilen mit vorhandenen
     * Formular-IDs kollidieren können und keine gemeinsame Herkunft darstellen.</p>
     */
    private List<Issue> validationIssues(RuleGroupForm form, Map<String, List<String>> tokens,
                                         ValidationContext context, List<GeneratedRuleRow> formRows) {
        Set<Issue> issues = new LinkedHashSet<>(RuleGroupValidator.validate(form));
        Map<String, List<GeneratedRuleRow>> rowsByTemplate = new LinkedHashMap<>();
        if (formRows != null) {
            for (GeneratedRuleRow row : formRows) {
                rowsByTemplate.computeIfAbsent(row.templateId(), ignored -> new ArrayList<>()).add(row);
            }
        }
        Map<String, List<GeneratedRuleRow>> seenRules = new LinkedHashMap<>();
        Set<String> seenTests = new HashSet<>();
        for (int r = 0; r < form.getRules().size(); r++) {
            var rule = form.getRules().get(r);
            String prefix = "rules[" + r + "].";
            boolean known = checkTokens(issues, prefix, tokens,
                    new String[]{"inputMask", "inputPattern", "outputMask", "outputPattern"},
                    rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern());
            List<GeneratedRuleRow> rows = List.of();
            if (known && isCompleteRule(rule)) {
                rows = formRows == null
                        ? generatedRuleRows(List.of(new RuleTemplate(previewRuleId(rule, r),
                                form.getId() == null ? 0 : form.getId(), rule.getInputMask(), rule.getInputPattern(),
                                rule.getOutputMask(), rule.getOutputPattern())), tokens)
                        : rowsByTemplate.getOrDefault(previewRuleId(rule, r), List.of());
            }
            for (GeneratedRuleRow row : rows) {
                Rule concrete = row.rule();
                addIssues(issues, prefix, RulePatternValidator.validate(concrete.getInputMask(), concrete.getInputPattern(),
                        concrete.getOutputMask(), concrete.getOutputPattern()));
                addIssues(issues, prefix, RuleGroupValidator.validateCsvWhitespace(
                        new String[]{"inputMask", "inputPattern", "outputMask", "outputPattern"},
                        concrete.getInputMask(), concrete.getInputPattern(), concrete.getOutputMask(), concrete.getOutputPattern()));
                List<GeneratedRuleRow> previous = seenRules.computeIfAbsent(concrete.getInputMask(), ignored -> new ArrayList<>());
                for (GeneratedRuleRow other : previous) {
                    addRuleConflict(issues, prefix, concrete, other);
                }
                previous.add(row);
                for (GeneratedRuleRow other : context.rowsByMask.getOrDefault(concrete.getInputMask(), List.of())) {
                    if (form.getId() == null || other.groupId() != form.getId()) {
                        addRuleConflict(issues, prefix, concrete, other);
                    }
                }
            }
            for (int t = 0; t < rule.getTests().size(); t++) {
                var test = rule.getTests().get(t);
                String testPrefix = prefix + "tests[" + t + "].";
                boolean testKnown = checkTokens(issues, testPrefix, tokens,
                        new String[]{"input", "tokenized", "output", "timespan"},
                        test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan());
                if (!known || !testKnown || !isCompleteTest(test) || rows.isEmpty()) {
                    if (test.getTimespan() != null && !test.getTimespan().contains("~")) {
                        addIssues(issues, testPrefix, RuleGroupValidator.validateTest(null, null, null, null, test.getTimespan()));
                    }
                    continue;
                }
                RuleTestTemplate template = new RuleTestTemplate(previewTestId(test, r, t), previewRuleId(rule, r),
                        test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan());
                // Eine konkrete Eingabe muss zu mindestens einer Regelvariante passen.
                // Ausgabewerte hängen dagegen von der gerade gewählten Regelbelegung ab.
                Set<String> matchedCandidates = new HashSet<>();
                Set<String> matchedTests = new HashSet<>();
                Map<String, List<Issue>> candidateIssues = new LinkedHashMap<>();
                for (GeneratedRuleRow row : rows) {
                    for (Map<String, String> values : testTokenExpansions(template, row.tokenValues(), tokens)) {
                        String input = applyTokenValues(template.input(), values);
                        String tokenized = applyTestTokenizedTemplate(applyTokenValues(template.tokenized(), values), input);
                        String output = applyTokenValues(template.output(), values);
                        String timespan = applyTokenValues(template.timespan(), values);
                        // Output/Timespan gehören absichtlich nicht zum Kandidatenschlüssel:
                        // Ein Test für "ca. 1950" muss keine "um"-Regelvariante erfüllen.
                        String candidate = signature(input, tokenized);
                        Rule concrete = row.rule();
                        String outputTestMask = RulePatternValidator.outputTestMask(concrete.getInputMask(), concrete.getInputPattern(),
                                concrete.getOutputMask(), concrete.getOutputPattern());
                        List<Issue> testIssues = RuleGroupValidator.validateTest(concrete.getInputMask(), outputTestMask,
                                tokenized, output, timespan);
                        testIssues.addAll(RuleGroupValidator.validateCsvWhitespace(new String[]{"input"}, input));
                        candidateIssues.putIfAbsent(candidate, testIssues);
                        if (matchesInputMask(row.rule().getInputMask(), tokenized)) {
                            addIssues(issues, testPrefix, testIssues);
                            matchedCandidates.add(candidate);
                            String concreteTest = signature(ruleSignature(concrete), input, tokenized, output, timespan);
                            if (matchedTests.add(concreteTest)
                                    && (!seenTests.add(concreteTest) || context.hasTestOutsideGroup(concreteTest, form.getId()))) {
                                addIssue(issues, new Issue(testPrefix + "input", "test.duplicate", "Dieser generierte Test ist bereits vorhanden."));
                            }
                        }
                    }
                }
                for (var candidate : candidateIssues.entrySet()) {
                    if (!matchedCandidates.contains(candidate.getKey())) {
                        addIssues(issues, testPrefix, candidate.getValue());
                    }
                }
            }
        }
        return List.copyOf(issues);
    }

    /** Unterscheidet exakte Regeldubletten von konkurrierenden Transformationen derselben Maske. */
    private void addRuleConflict(Set<Issue> issues, String prefix, Rule rule, GeneratedRuleRow other) {
        boolean duplicate = ruleSignature(rule).equals(ruleSignature(other.rule()));
        String location = "Regel " + other.rule().getId() + (other.groupId() > 0
                ? " (Regelgruppe " + other.groupId() + ", Vorlage " + other.templateId() + ")"
                : " (Vorlage " + other.templateId() + " im aktuellen Formular)");
        addIssue(issues, new Issue(prefix + "inputMask", duplicate ? "rule.duplicate" : "rule.mask.conflict",
                (duplicate ? "Diese generierte Regel ist bereits vorhanden. Fundstelle: " + location + "."
                        : "Die Eingabemaske ist bereits mit einer anderen Transformation belegt. Fundstelle: " + location
                                + ". Der Timeparser verwendet nur die erste Regel je Eingabemaske."),
                List.of(new RulePatternValidator.RelatedRule(other.rule().getId(), other.groupId(), other.templateId()))));
    }

    private void addIssues(Set<Issue> issues, String prefix, List<Issue> additions) {
        for (Issue issue : additions) {
            addIssue(issues, new Issue(prefix + issue.field(), issue.code(), issue.message(), issue.relatedRules()));
        }
    }

    /**
     * Meldet Speicherfehler nur einmal je Feld und Prüfcode. Die Record-Gleichheit
     * berücksichtigt auch den Text; sie reicht für Befunde aus Grundprüfung und
     * Expansion mit unterschiedlichen Erläuterungen nicht aus. Der erste Text bleibt
     * erhalten. Variantenbezogene Hinweise verwenden bewusst nicht diese Verdichtung.
     */
    private void addIssue(Set<Issue> issues, Issue addition) {
        boolean alreadyReported = issues.stream().anyMatch(issue ->
                issue.field().equals(addition.field()) && issue.code().equals(addition.code()));
        if (!alreadyReported) {
            issues.add(addition);
        }
    }

    private boolean checkTokens(Set<Issue> issues, String prefix, Map<String, List<String>> tokens,
                                  String[] fields, String... values) {
        boolean known = true;
        for (int i = 0; i < fields.length; i++) {
            Set<String> names = new LinkedHashSet<>();
            collectTokenNames(names, values[i]);
            for (String name : names) {
                if (tokenValuesForName(name, tokens).isEmpty()) {
                    addIssue(issues, new Issue(prefix + fields[i], "token.unknown", "Platzhalter ~" + name + " ist nicht definiert."));
                    known = false;
                }
            }
        }
        return known;
    }

    /**
     * Überspringt ausschließlich Vorlagen mit nicht auflösbaren Platzhaltern.
     * "Safe" bezeichnet hier die fortsetzbare Expansion, nicht fachliche Fehlerfreiheit:
     * andere fehlerhafte Zeilen bleiben für Anzeige, Prüfung und Export erhalten.
     */
    private List<GeneratedRuleRow> safeGeneratedRuleRows(List<RuleTemplate> templates, Map<String, List<String>> tokens) {
        List<RuleTemplate> knownTemplates = templates.stream().filter(template -> {
            Set<String> names = new LinkedHashSet<>();
            collectTokenNames(names, template.inputMask(), template.inputPattern(), template.outputMask(), template.outputPattern());
            return names.stream().allMatch(name -> !tokenValuesForName(name, tokens).isEmpty());
        }).toList();
        // Die gemeinsame ID-Vergabe muss auch bei unvollständigen Vorlagen erhalten bleiben.
        return generatedRuleRows(knownTemplates, tokens);
    }

    private List<PlausibilityWarning> plausibilityWarnings(Long groupId) {
        Map<String, List<String>> tokenValues = tokenValuesByName();
        List<RuleTemplate> templates = ruleTemplates((Long) null);
        List<RuleTestTemplate> tests = testTemplates((Long) null);
        List<GeneratedRuleRow> rows = safeGeneratedRuleRows(templates, tokenValues);
        return plausibilityWarnings(groupId, tokenValues, templates, tests,
                new ValidationContext(rows, safeGeneratedTests(rows, tests, tokenValues)));
    }

    /**
     * Stellt feldbezogene Fehler, unverbindliche Hinweise und gruppierte Dubletten dar.
     * Vorgegebene Belegungen und Zeilen erlauben dem Export, denselben gelesenen Stand
     * für Daten und Bericht zu verwenden. {@code groupId == null} prüft alle Gruppen.
     */
    private List<PlausibilityWarning> plausibilityWarnings(Long groupId, Map<String, List<String>> tokenValues,
                                                           List<RuleTemplate> templates, List<RuleTestTemplate> tests,
                                                           ValidationContext context) {
        List<PlausibilityWarning> warnings = new ArrayList<>();
        List<GeneratedRuleRow> generatedRuleRows = groupId == null ? context.rows
                : context.rowsByGroup.getOrDefault(groupId, List.of());
        List<RuleGroupForm> forms = groupId == null ? findGroupForms(templates, tests) : List.of(findGroupForm(groupId));
        for (RuleGroupForm form : forms) {
            List<GeneratedRuleRow> formRows = context.rowsByGroup.getOrDefault(form.getId(), List.of());
            for (Issue issue : validationIssues(form, tokenValues, context, formRows)) {
                warnings.add(validationWarning(form, issue));
            }
            for (Issue notice : validationNotices(form, context, formRows, context.testedRuleIds)) {
                warnings.add(validationWarning(form, notice));
            }
        }
        Map<String, GeneratedRuleRow> rowsByRuleId = new LinkedHashMap<>();
        for (GeneratedRuleRow row : generatedRuleRows) {
            rowsByRuleId.put(row.rule().getId(), row);
        }
        addDuplicateRuleWarnings(warnings, generatedRuleRows);
        addDuplicateTestWarnings(
                warnings,
                groupId == null ? context.tests : context.tests.stream()
                        .filter(test -> rowsByRuleId.containsKey(test.getRuleId())).toList(),
                rowsByRuleId
        );
        return warnings;
    }

    /**
     * Lässt Testvorlagen mit unbekannten Platzhaltern aus und erzeugt die übrigen Tests.
     * Fehlende Zuordnungen zu einer Regel oder Maske können weitere Tests herausfiltern;
     * die aufrufende Prüf-/Exportschicht ist für entsprechende Hinweise zuständig.
     */
    private List<RuleTest> safeGeneratedTests(List<GeneratedRuleRow> rows, List<RuleTestTemplate> templates,
                                              Map<String, List<String>> tokens) {
        List<RuleTestTemplate> knownTemplates = templates.stream().filter(test -> {
            Set<String> names = new LinkedHashSet<>();
            collectTokenNames(names, test.input(), test.tokenized(), test.output(), test.timespan());
            return names.stream().allMatch(name -> !tokenValuesForName(name, tokens).isEmpty());
        }).toList();
        return generateTestsForRows(rows, knownTemplates, tokens);
    }

    /** Ordnet einen Formularfeldpfad gespeicherten IDs und dem HTML-Anker der Regelvorlage zu. */
    private PlausibilityWarning validationWarning(RuleGroupForm form, Issue issue) {
        String id = isBlank(form.getName()) ? "Gruppe " + form.getId() : form.getName();
        String anchor = "";
        String type = "Regelgruppe";
        List<PlausibilityWarning.PlausibilityField> fields = List.of(plausibilityField("Feld", issue.field()));
        Matcher location = Pattern.compile("^rules\\[(\\d+)](?:\\.tests\\[(\\d+)])?.*").matcher(issue.field());
        if (location.matches()) {
            var rule = form.getRules().get(Integer.parseInt(location.group(1)));
            id = rule.getId();
            anchor = "rule-" + id;
            type = "Regel";
            fields = ruleFields(new Rule(id, rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern()));
            if (location.group(2) != null) {
                var test = rule.getTests().get(Integer.parseInt(location.group(2)));
                id = test.getId();
                type = "Test";
                fields = testFields(new RuleTest(id, rule.getId(), test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan()));
            }
        }
        Set<PlausibilityWarning.PlausibilityEntry> entries = new LinkedHashSet<>();
        entries.add(new PlausibilityWarning.PlausibilityEntry(id, form.getId(), anchor));
        for (var related : issue.relatedRules()) {
            entries.add(new PlausibilityWarning.PlausibilityEntry(related.id(), related.groupId(), "rule-" + related.templateId()));
        }
        return new PlausibilityWarning(type, List.copyOf(entries), fields, issue.message());
    }

    private void addDuplicateRuleWarnings(List<PlausibilityWarning> warnings, List<GeneratedRuleRow> ruleRows) {
        Map<String, List<GeneratedRuleRow>> rowsByValues = new LinkedHashMap<>();
        for (GeneratedRuleRow row : ruleRows) {
            rowsByValues.computeIfAbsent(ruleSignature(row.rule()), key -> new ArrayList<>()).add(row);
        }
        for (List<GeneratedRuleRow> duplicateRows : rowsByValues.values()) {
            if (duplicateRows.size() > 1) {
                Rule duplicateRule = duplicateRows.get(0).rule();
                warnings.add(new PlausibilityWarning(
                        "Regel",
                        duplicateRows.stream().map(this::plausibilityEntry).toList(),
                        ruleFields(duplicateRule)
                ));
            }
        }
    }

    private void addDuplicateTestWarnings(List<PlausibilityWarning> warnings,
                                          List<RuleTest> tests,
                                          Map<String, GeneratedRuleRow> rowsByRuleId) {
        Map<String, List<RuleTest>> testsByValues = new LinkedHashMap<>();
        for (RuleTest test : tests) {
            GeneratedRuleRow row = rowsByRuleId.get(test.getRuleId());
            String scope = row == null ? test.getRuleId() : ruleSignature(row.rule());
            testsByValues.computeIfAbsent(signature(scope, testSignature(test)), key -> new ArrayList<>()).add(test);
        }
        for (List<RuleTest> duplicateTests : testsByValues.values()) {
            if (duplicateTests.size() > 1) {
                warnings.add(new PlausibilityWarning(
                        "Test",
                        duplicateTests.stream()
                                .map(test -> plausibilityEntry(test, rowsByRuleId.get(test.getRuleId())))
                                .toList(),
                        testFields(duplicateTests.get(0))
                ));
            }
        }
    }

    /**
     * Erstellt einen aktuellen Snapshot und serialisiert Regeln, Tests und Prüfbericht.
     * Befunde verhindern die Erzeugung des ZIP nicht.
     *
     * @return ZIP mit {@code rules.csv}, {@code tests.csv} und {@code pruefhinweise.txt}
     * @see #exportSnapshot()
     */
    @Transactional(readOnly = true)
    public byte[] rulesAndTestsZip() {
        return rulesAndTestsZip(exportSnapshot());
    }

    /**
     * @return UTF-8-Regel-CSV aus einem neu erzeugten Exportstand
     * @see #rulesCsv(ExportSnapshot)
     */
    @Transactional(readOnly = true)
    public byte[] rulesCsv() {
        return rulesCsv(exportSnapshot());
    }

    /**
     * @return UTF-8-Test-CSV mit Referenzen auf die kanonischen Regeln des Exportstands
     * @see #testsCsv(ExportSnapshot)
     */
    @Transactional(readOnly = true)
    public byte[] testsCsv() {
        return testsCsv(exportSnapshot());
    }

    /**
     * Serialisiert einen bereits geprüften Exportstand ohne erneuten Datenbankzugriff.
     *
     * @param snapshot gemeinsame Daten und Befunde für diesen Download
     * @return ZIP einschließlich des zum Snapshot gehörenden Prüfberichts
     */
    public byte[] rulesAndTestsZip(ExportSnapshot snapshot) {
        return csvExportService.rulesAndTestsZip(snapshot.dataset().rules(), snapshot.dataset().tests(), snapshot.report());
    }

    /**
     * @param snapshot bereits erzeugter gemeinsamer Exportstand
     * @return dessen Regel-CSV; Reihenfolge und kanonische IDs werden unverändert übernommen
     */
    public byte[] rulesCsv(ExportSnapshot snapshot) {
        return csvExportService.rulesCsv(snapshot.dataset().rules());
    }

    /**
     * @param snapshot bereits erzeugter gemeinsamer Exportstand
     * @return dessen Test-CSV mit den durch die Exportaufbereitung angepassten Regelreferenzen
     */
    public byte[] testsCsv(ExportSnapshot snapshot) {
        return csvExportService.testsCsv(snapshot.dataset().tests());
    }

    /**
     * Bündelt die aufbereiteten Exportdaten und alle zugehörigen statischen Befunde.
     *
     * <p>Auflösbare Vorlagen werden expandiert und durch {@link ExportDatasetFactory}
     * von exakten Dubletten bereinigt. Nicht auflösbare Regeln oder Tests sowie Tests
     * ohne passende Eingabemaske werden ausgelassen und ausdrücklich im Bericht genannt.
     * Andere Plausibilitätsfehler entfernen keine expandierbaren Exportzeilen.</p>
     *
     * <p>Beim Aufruf über das Spring-Repository umfasst die Lesetransaktion die
     * Daten und den Bericht desselben Downloads. Für mehrere Dateien aus einem Stand
     * sollte derselbe Snapshot verwendet werden. Zwei getrennte HTTP-Downloads erzeugen
     * dagegen jeweils einen neuen Stand. Die enthaltenen Testfälle werden nicht ausgeführt.</p>
     *
     * @return Exportdaten, verlinkbare Befunde und UTF-8-geeigneter Berichtstext
     */
    @Transactional(readOnly = true)
    public ExportSnapshot exportSnapshot() {
        Map<String, List<String>> tokens = tokenValuesByName();
        List<RuleTemplate> templates = ruleTemplates((Long) null);
        List<RuleTestTemplate> tests = testTemplates((Long) null);
        List<GeneratedRuleRow> rows = safeGeneratedRuleRows(templates, tokens);
        ValidationContext context = new ValidationContext(rows, safeGeneratedTests(rows, tests, tokens));
        ExportDataset dataset = ExportDatasetFactory.create(rows.stream().map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR)).toList(), context.tests);
        List<PlausibilityWarning> warnings = new ArrayList<>(plausibilityWarnings(null, tokens, templates, tests, context));
        for (String warning : dataset.warnings()) {
            warnings.add(new PlausibilityWarning("Export", List.of(), List.of(), warning));
        }
        Set<String> expanded = new HashSet<>(rows.stream().map(GeneratedRuleRow::templateId).toList());
        for (RuleTemplate template : templates) {
            if (!expanded.contains(template.id())) {
                warnings.add(new PlausibilityWarning("Export", List.of(new PlausibilityWarning.PlausibilityEntry(
                        template.id(), template.groupId(), "rule-" + template.id())), List.of(),
                        "Die Regel " + template.id() + " und ihre Tests fehlen im Export, weil Platzhalter nicht expandiert werden können."));
            }
        }
        for (RuleTestTemplate test : tests) {
            Set<String> names = new LinkedHashSet<>();
            collectTokenNames(names, test.input(), test.tokenized(), test.output(), test.timespan());
            if (names.stream().anyMatch(name -> tokenValuesForName(name, tokens).isEmpty())) {
                warnings.add(new PlausibilityWarning("Export", List.of(), List.of(),
                        "Der Test " + test.id() + " fehlt im Export, weil ein Platzhalter nicht expandiert werden kann."));
            } else if (expanded.contains(test.ruleId()) && safeGeneratedTests(rows, List.of(test), tokens).isEmpty()) {
                warnings.add(new PlausibilityWarning("Export", List.of(), List.of(),
                        "Der Test " + test.id() + " fehlt im Export, weil der tokenisierte Wert zu keiner erzeugten Eingabemaske passt."));
            }
        }
        String report = exportReport(dataset, warnings);
        return new ExportSnapshot(dataset, warnings, report);
    }

    /** Formatiert Befunde und Prüfgrenzen als beiliegenden Text; der Bericht ist keine Parserfreigabe. */
    private String exportReport(ExportDataset dataset, List<PlausibilityWarning> warnings) {
        StringBuilder report = new StringBuilder("Timeparser-Regeln: Prüfhinweise zum Export\n\n")
                .append("Regeln: ").append(dataset.rules().size()).append("\nTests: ").append(dataset.tests().size())
                .append("\nHinweise: ").append(warnings.size()).append("\n\n")
                .append("Es wurden statische Plausibilitätsprüfungen durchgeführt. Die Testfälle wurden nicht im Timeparser ausgeführt.\n")
                .append("Tokenisierung, tatsächliche Regelauswahl, Transformation und Datumsinterpretation sind nicht abschließend geprüft.\n")
                .append("Nicht expandierbare Vorlagen können nicht als konkrete CSV-Zeilen ausgegeben werden und werden unten aufgeführt.\n\n");
        for (PlausibilityWarning warning : warnings) {
            String ids = warning.entries().stream().map(PlausibilityWarning.PlausibilityEntry::id)
                    .collect(java.util.stream.Collectors.joining(", "));
            report.append("- ").append(warning.type()).append(ids.isBlank() ? "" : " [" + ids + "]")
                    .append(": ").append(warning.message()).append('\n');
        }
        return report.toString();
    }

    /**
     * @return Platzhalterdefinitionen im Editorformat; kein Ersatz für die
     *         {@code calendar_tokens.csv} des Timeparsers
     */
    public byte[] tokensCsv() {
        return csvExportService.tokensCsv(findTokens());
    }

    /**
     * @return nach Namen sortierte Platzhalter mit ihren Werten in gespeicherter Reihenfolge
     */
    public List<Token> findTokens() {
        Map<Long, List<String>> valuesByTokenId = tokenValuesByTokenId();
        return jdbcTemplate.query("""
                SELECT id, name, description
                FROM tokens
                ORDER BY name
                """, (rs, rowNum) -> new Token(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                valuesByTokenId.getOrDefault(rs.getLong("id"), List.of())
        ));
    }

    /**
     * Lädt Platzhalterwerte als mehrzeiligen Text zur Bearbeitung. Leerzeichen innerhalb
     * der einzelnen Werte bleiben unverändert; nur die Zeilentrennung wird vereinheitlicht.
     *
     * @param tokenId vorhandene Platzhalter-ID
     * @return bearbeitbares Formular, ein Wert pro Zeile
     * @throws org.springframework.dao.EmptyResultDataAccessException wenn die Definition fehlt
     */
    public TokenForm findTokenForm(long tokenId) {
        Map<String, Object> token = jdbcTemplate.queryForMap(
                "SELECT id, name, description FROM tokens WHERE id = ?",
                tokenId
        );
        TokenForm form = new TokenForm();
        form.setId(((Number) token.get("id")).longValue());
        form.setName((String) token.get("name"));
        form.setDescription((String) token.get("description"));
        form.setValues(String.join("\n", tokenValues(tokenId)));
        return form;
    }

    /**
     * @param tokenId technische Platzhalter-ID
     * @return ob die Definition vorhanden ist, unabhängig von der Anzahl ihrer Werte
     */
    public boolean tokenExists(long tokenId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tokens WHERE id = ?", Integer.class, tokenId);
        return count != null && count > 0;
    }

    /**
     * Prüft die Namenseindeutigkeit ohne Beachtung der Großschreibung.
     * Anders als die Expansion vergleicht diese Datenbankabfrage die Namen unmittelbar
     * und führt den historischen Alias {@code aprox} nicht auf {@code approx} zurück.
     *
     * @param name angefragter Name; {@code null} oder leer liefert {@code false}
     * @param excludedTokenId beim Bearbeiten auszunehmende eigene ID, sonst {@code null}
     * @return ob eine andere gespeicherte Definition denselben Namen hat
     */
    public boolean tokenNameExists(String name, Long excludedTokenId) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Integer count;
        if (excludedTokenId == null) {
            count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tokens WHERE LOWER(name) = LOWER(?)", Integer.class, name);
        } else {
            count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM tokens
                    WHERE LOWER(name) = LOWER(?)
                      AND id <> ?
                    """, Integer.class, name, excludedTokenId);
        }
        return count != null && count > 0;
    }

    /**
     * Sucht Verwendungen in unexpandierten Regel- und Testfeldern, einschließlich
     * erwarteter Zeiträume. Großschreibung und der Alias {@code aprox} werden wie
     * bei der Expansion normalisiert. Vor Umbenennen oder Löschen aufrufen, damit
     * die Oberfläche die vom bisherigen Namen abhängigen Gruppen nennen kann.
     *
     * @param tokenId ID der noch vorhandenen Platzhalterdefinition
     * @return betroffene Gruppen einmalig und in der Reihenfolge der Gruppenübersicht
     */
    public List<RuleGroupSummary> groupsUsingToken(long tokenId) {
        String name = tokenLookupKey(findTokenForm(tokenId).getName());
        Set<Long> groupIds = new HashSet<>();
        Map<String, Long> ruleGroups = new LinkedHashMap<>();
        for (RuleTemplate rule : ruleTemplates((Long) null)) {
            ruleGroups.put(rule.id(), rule.groupId());
            Set<String> names = new LinkedHashSet<>();
            collectTokenNames(names, rule.inputMask(), rule.inputPattern(), rule.outputMask(), rule.outputPattern());
            if (names.stream().anyMatch(value -> tokenLookupKey(value).equals(name))) {
                groupIds.add(rule.groupId());
            }
        }
        for (RuleTestTemplate test : testTemplates((Long) null)) {
            Set<String> names = new LinkedHashSet<>();
            collectTokenNames(names, test.input(), test.tokenized(), test.output(), test.timespan());
            if (names.stream().anyMatch(value -> tokenLookupKey(value).equals(name))) {
                groupIds.add(ruleGroups.get(test.ruleId()));
            }
        }
        return findGroupSummaries().stream().filter(group -> groupIds.contains(group.id())).toList();
    }

    /**
     * Legt einen Platzhalter an oder ersetzt seine vollständige Werteliste.
     * Leere Zeilen werden ausgelassen, Leerzeichen nicht getrimmt. Eine Umbenennung
     * ändert keine Verweise in Regel- oder Testvorlagen; diese können anschließend
     * als unbekannte Platzhalter gemeldet werden.
     *
     * @param form vorab geprüftes Formular; {@code id == null} legt eine neue Definition an
     */
    @Transactional
    public void saveToken(TokenForm form) {
        long tokenId;
        if (form.getId() == null) {
            tokenId = insertToken(form.getName(), form.getDescription());
        } else {
            tokenId = form.getId();
            jdbcTemplate.update("UPDATE tokens SET name = ?, description = ? WHERE id = ?", form.getName(), form.getDescription(), tokenId);
            jdbcTemplate.update("DELETE FROM token_values WHERE token_id = ?", tokenId);
        }
        insertTokenValues(tokenId, splitValues(form.getValues()));
    }

    /**
     * Löscht Definition und Werte, ohne referenzierende Vorlagen umzuschreiben.
     * Abhängige Gruppen bleiben erhalten und werden bei der nächsten Prüfung neu bewertet.
     *
     * @param tokenId zu löschende Platzhalterdefinition
     * @see #groupsUsingToken(long)
     */
    @Transactional
    public void deleteToken(long tokenId) {
        jdbcTemplate.update("DELETE FROM token_values WHERE token_id = ?", tokenId);
        jdbcTemplate.update("DELETE FROM tokens WHERE id = ?", tokenId);
    }

    private Map<String, List<RuleGroupForm.TestForm>> findTestFormsForGroup(long groupId) {
        Map<String, List<RuleGroupForm.TestForm>> testsByRuleId = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT t.id, t.rule_id, t.input, t.tokenized, t.output, t.timespan
                FROM tests t
                JOIN rules r ON r.id = t.rule_id
                WHERE r.group_id = ?
                ORDER BY r.position, r.id, t.position, t.id
                """, rs -> {
            RuleGroupForm.TestForm test = new RuleGroupForm.TestForm();
            test.setId(rs.getString("id"));
            test.setInput(rs.getString("input"));
            test.setTokenized(rs.getString("tokenized"));
            test.setOutput(rs.getString("output"));
            test.setTimespan(rs.getString("timespan"));
            testsByRuleId.computeIfAbsent(rs.getString("rule_id"), ignored -> new ArrayList<>()).add(test);
        }, groupId);
        return testsByRuleId;
    }

    /**
     * Baut die Formulare des Gesamtchecks aus den bereits gelesenen Vorlagen auf.
     * Nur die Gruppenmetadaten benötigen eine weitere Abfrage; die Anzahl der
     * Datenbankzugriffe wächst damit nicht mit der Anzahl der Gruppen. Die globalen
     * Vorlagenlisten erhalten innerhalb jeder Gruppe dieselbe Positionsreihenfolge
     * wie {@link #findGroupForm(long)}, auch für unvollständige Altbestände.
     */
    private List<RuleGroupForm> findGroupForms(List<RuleTemplate> rules, List<RuleTestTemplate> tests) {
        Map<Long, RuleGroupForm> groups = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT id, name, description FROM rule_groups ORDER BY id", (RowCallbackHandler) rs -> {
            RuleGroupForm form = new RuleGroupForm();
            form.setId(rs.getLong("id"));
            form.setName(rs.getString("name"));
            form.setDescription(rs.getString("description"));
            groups.put(form.getId(), form);
        });
        Map<String, RuleGroupForm.RuleVariantForm> rulesById = new LinkedHashMap<>();
        for (RuleTemplate template : rules) {
            RuleGroupForm form = groups.get(template.groupId());
            if (form == null) {
                continue;
            }
            var rule = new RuleGroupForm.RuleVariantForm();
            rule.setId(template.id());
            rule.setInputMask(template.inputMask());
            rule.setInputPattern(template.inputPattern());
            rule.setOutputMask(template.outputMask());
            rule.setOutputPattern(template.outputPattern());
            form.getRules().add(rule);
            rulesById.put(template.id(), rule);
        }
        for (RuleTestTemplate template : tests) {
            var rule = rulesById.get(template.ruleId());
            if (rule == null) {
                continue;
            }
            var test = new RuleGroupForm.TestForm();
            test.setId(template.id());
            test.setInput(template.input());
            test.setTokenized(template.tokenized());
            test.setOutput(template.output());
            test.setTimespan(template.timespan());
            rule.getTests().add(test);
        }
        return List.copyOf(groups.values());
    }

    private List<RuleTest> generatedTests(Long groupId) {
        Map<String, List<String>> tokens = tokenValuesByName();
        return safeGeneratedTests(safeGeneratedRuleRows(ruleTemplates(groupId), tokens), testTemplates(groupId), tokens);
    }

    private List<RuleTest> generatedTests(List<RuleTemplate> ruleTemplates,
                                          List<RuleTestTemplate> tests,
                                          Map<String, List<String>> tokenValuesByName) {
        List<GeneratedRuleRow> generatedRules = generatedRuleRows(ruleTemplates, tokenValuesByName);
        return generateTestsForRows(generatedRules, tests, tokenValuesByName);
    }

    /**
     * Verknüpft Testvorlagen über ihre gespeicherte Regel-ID mit deren konkreten Varianten.
     * Regelgebundene Platzhalterwerte werden übernommen, reine Testplatzhalter zusätzlich
     * expandiert. Nur maskenpassende tokenisierte Werte werden zu Ergebniszeilen.
     * Erwartete Ausgabe und Zeitraum werden übernommen, nicht berechnet oder überprüft.
     */
    private List<RuleTest> generateTestsForRows(List<GeneratedRuleRow> generatedRules,
                                                List<RuleTestTemplate> tests,
                                                Map<String, List<String>> tokenValuesByName) {
        Map<String, List<GeneratedRuleRow>> rulesByTemplateId = new LinkedHashMap<>();
        for (GeneratedRuleRow generatedRule : generatedRules) {
            rulesByTemplateId.computeIfAbsent(generatedRule.templateId(), key -> new ArrayList<>()).add(generatedRule);
        }

        Set<String> usedIds = new HashSet<>();
        List<RuleTest> generatedTests = new ArrayList<>();
        for (RuleTestTemplate test : tests) {
            List<GeneratedTestCandidate> candidates = new ArrayList<>();
            List<GeneratedRuleRow> rows = rulesByTemplateId.getOrDefault(test.ruleId(), List.of());
            for (GeneratedRuleRow row : rows) {
                for (Map<String, String> tokenValues : testTokenExpansions(test, row.tokenValues(), tokenValuesByName)) {
                    String input = applyTokenValues(test.input(), tokenValues);
                    String tokenized = applyTestTokenizedTemplate(applyTokenValues(test.tokenized(), tokenValues), input);
                    // Eine Testvorlage kann bewusst nur eine Teilmenge der Regelvarianten abdecken.
                    if (!matchesInputMask(row.rule().getInputMask(), tokenized)) {
                        continue;
                    }
                    candidates.add(new GeneratedTestCandidate(
                            test.id(),
                            row.rule().getId(),
                            input,
                            tokenized,
                            applyTokenValues(test.output(), tokenValues),
                            applyTokenValues(test.timespan(), tokenValues)
                    ));
                }
            }
            for (int index = 0; index < candidates.size(); index++) {
                GeneratedTestCandidate candidate = candidates.get(index);
                String testId = generatedId(candidate.templateId(), index + 1, candidates.size(), usedIds);
                generatedTests.add(new RuleTest(
                        testId,
                        candidate.ruleId(),
                        candidate.input(),
                        candidate.tokenized(),
                        candidate.output(),
                        candidate.timespan()
                ));
            }
        }
        return generatedTests.stream()
                .sorted(Comparator.comparing(RuleTest::getId, ID_COMPARATOR))
                .toList();
    }

    /**
     * Expandiert Vorlagen und bewahrt ihre Herkunft für Rückverweise und Testzuordnungen.
     * Alle Zeilen dieses Aufrufs teilen sich die ID-Kollisionsmenge. Daher dürfen
     * exportierte IDs nicht durch getrennte Expansion jeder einzelnen Vorlage entstehen.
     */
    private List<GeneratedRuleRow> generatedRuleRows(List<RuleTemplate> templates,
                                                     Map<String, List<String>> tokenValuesByName) {
        Set<String> usedIds = new HashSet<>();
        List<GeneratedRuleRow> generatedRules = new ArrayList<>();
        for (RuleTemplate template : templates) {
            List<Map<String, String>> expansions = tokenExpansions(template, tokenValuesByName);
            for (int index = 0; index < expansions.size(); index++) {
                Map<String, String> tokenValues = expansions.get(index);
                String ruleId = generatedId(template.id(), index + 1, expansions.size(), usedIds);
                Rule rule = new Rule(
                        ruleId,
                        applyTokenValues(template.inputMask(), tokenValues),
                        applyTokenValues(template.inputPattern(), tokenValues),
                        applyTokenValues(template.outputMask(), tokenValues),
                        applyTokenValues(template.outputPattern(), tokenValues)
                );
                generatedRules.add(new GeneratedRuleRow(template.id(), template.groupId(), index + 1, expansions.size(), tokenValues, rule));
            }
        }
        return generatedRules;
    }

    private List<RuleTemplate> ruleTemplates(Long groupId) {
        if (groupId == null) {
            return jdbcTemplate.query("""
                    SELECT id, group_id, input_mask, input_pattern, output_mask, output_pattern
                    FROM rules
                    ORDER BY position, id
                    """, this::ruleTemplate);
        }
        return jdbcTemplate.query("""
                SELECT id, group_id, input_mask, input_pattern, output_mask, output_pattern
                FROM rules
                WHERE group_id = ?
                ORDER BY position, id
                """, this::ruleTemplate, groupId);
    }

    /** Übernimmt vollständige Formularzeilen; neue Zeilen erhalten nur für die Vorschau vorläufige IDs. */
    private List<RuleTemplate> ruleTemplates(RuleGroupForm form) {
        long groupId = form.getId() == null ? 0 : form.getId();
        List<RuleTemplate> templates = new ArrayList<>();
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = form.getRules().get(ruleIndex);
            if (!isCompleteRule(rule)) {
                continue;
            }
            templates.add(new RuleTemplate(
                    previewRuleId(rule, ruleIndex),
                    groupId,
                    rule.getInputMask(),
                    rule.getInputPattern(),
                    rule.getOutputMask(),
                    rule.getOutputPattern()
            ));
        }
        return templates;
    }

    private RuleTemplate ruleTemplate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RuleTemplate(
                rs.getString("id"),
                rs.getLong("group_id"),
                rs.getString("input_mask"),
                rs.getString("input_pattern"),
                rs.getString("output_mask"),
                rs.getString("output_pattern")
        );
    }

    private List<RuleTestTemplate> testTemplates(Long groupId) {
        if (groupId == null) {
            return jdbcTemplate.query("""
                    SELECT t.id, t.rule_id, t.input, t.tokenized, t.output, t.timespan
                    FROM tests t
                    JOIN rules r ON r.id = t.rule_id
                    ORDER BY r.position, r.id, t.position, t.id
                    """, this::ruleTestTemplate);
        }
        return jdbcTemplate.query("""
                SELECT t.id, t.rule_id, t.input, t.tokenized, t.output, t.timespan
                FROM tests t
                JOIN rules r ON r.id = t.rule_id
                WHERE r.group_id = ?
                ORDER BY r.position, r.id, t.position, t.id
                """, this::ruleTestTemplate, groupId);
    }

    /** Ignoriert unvollständige Tests und Tests unvollständiger Regeln, ohne das Formular zu verändern. */
    private List<RuleTestTemplate> testTemplates(RuleGroupForm form) {
        List<RuleTestTemplate> templates = new ArrayList<>();
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = form.getRules().get(ruleIndex);
            if (!isCompleteRule(rule)) {
                continue;
            }
            String ruleId = previewRuleId(rule, ruleIndex);
            for (int testIndex = 0; testIndex < rule.getTests().size(); testIndex++) {
                RuleGroupForm.TestForm test = rule.getTests().get(testIndex);
                if (!isCompleteTest(test)) {
                    continue;
                }
                templates.add(new RuleTestTemplate(
                        previewTestId(test, ruleIndex, testIndex),
                        ruleId,
                        test.getInput(),
                        test.getTokenized(),
                        test.getOutput(),
                        test.getTimespan()
                ));
            }
        }
        return templates;
    }

    private RuleTestTemplate ruleTestTemplate(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RuleTestTemplate(
                rs.getString("id"),
                rs.getString("rule_id"),
                rs.getString("input"),
                rs.getString("tokenized"),
                rs.getString("output"),
                rs.getString("timespan")
        );
    }

    /**
     * Bildet das kartesische Produkt aller in einer Regel verwendeten Platzhalterwerte.
     * Derselbe normalisierte Name hat innerhalb einer Belegung in allen Feldern denselben
     * Wert. Ohne Platzhalter gibt es genau eine leere Belegung, nicht null Varianten.
     * Nicht auflösbare Namen führen im strikten Vorschaupfad zu einer Ausnahme.
     */
    private List<Map<String, String>> tokenExpansions(RuleTemplate template,
                                                      Map<String, List<String>> tokenValuesByName) {
        Set<String> tokenNames = new LinkedHashSet<>();
        collectTokenNames(tokenNames, template.inputMask(), template.inputPattern(), template.outputMask(), template.outputPattern());
        if (tokenNames.isEmpty()) {
            return List.of(Map.of());
        }

        List<Map.Entry<String, List<String>>> tokenValues = new ArrayList<>();
        for (String tokenName : tokenNames) {
            List<String> values = tokenValuesForName(tokenName, tokenValuesByName);
            if (values.isEmpty()) {
                throw new CsvRepositoryException("Token ~" + tokenName + " ist nicht definiert.");
            }
            tokenValues.add(Map.entry(tokenName, values));
        }

        List<Map<String, String>> expansions = new ArrayList<>();
        expandTokenValues(tokenValues, 0, new LinkedHashMap<>(), expansions);
        return expansions;
    }

    /**
     * Erweitert die Belegung einer konkreten Regel nur um zusätzlich im Test vorkommende
     * Namen. Bereits gebundene Namen bleiben fest: Regel und Test sollen beispielsweise
     * dieselbe {@code ~approx}-Variante verwenden, auch bei anderer Schreibweise des Namens.
     */
    private List<Map<String, String>> testTokenExpansions(RuleTestTemplate test,
                                                          Map<String, String> ruleTokenValues,
                                                          Map<String, List<String>> tokenValuesByName) {
        Set<String> tokenNames = new LinkedHashSet<>();
        collectTokenNames(tokenNames, test.input(), test.tokenized(), test.output(), test.timespan());

        List<Map.Entry<String, List<String>>> tokenValues = new ArrayList<>();
        for (String tokenName : tokenNames) {
            if (lookupTokenValue(ruleTokenValues, tokenName) != null) {
                continue;
            }
            List<String> values = tokenValuesForName(tokenName, tokenValuesByName);
            if (values.isEmpty()) {
                throw new CsvRepositoryException("Token ~" + tokenName + " ist nicht definiert.");
            }
            tokenValues.add(Map.entry(tokenName, values));
        }

        if (tokenValues.isEmpty()) {
            return List.of(new LinkedHashMap<>(ruleTokenValues));
        }

        List<Map<String, String>> expansions = new ArrayList<>();
        expandTokenValues(tokenValues, 0, new LinkedHashMap<>(ruleTokenValues), expansions);
        return expansions;
    }

    /** Rekursiver Produktaufbau; jede Ergebnisbelegung ist eine eigene Kopie des Arbeitszustands. */
    private void expandTokenValues(List<Map.Entry<String, List<String>>> tokenValues,
                                   int index,
                                   Map<String, String> currentValues,
                                   List<Map<String, String>> expansions) {
        if (index == tokenValues.size()) {
            expansions.add(new LinkedHashMap<>(currentValues));
            return;
        }

        Map.Entry<String, List<String>> token = tokenValues.get(index);
        for (String value : token.getValue()) {
            currentValues.put(token.getKey(), value);
            expandTokenValues(tokenValues, index + 1, currentValues, expansions);
        }
        currentValues.remove(token.getKey());
    }

    /** Sammelt normalisierte Namen in Fundreihenfolge; {@code null}-Felder werden ignoriert. */
    private void collectTokenNames(Set<String> tokenNames, String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            Matcher matcher = TOKEN_REFERENCE.matcher(value);
            while (matcher.find()) {
                tokenNames.add(tokenLookupKey(matcher.group(1)));
            }
        }
    }

    /**
     * Ersetzt Referenzen in einem Durchlauf, ohne ersetzte Werte erneut zu expandieren.
     * Unbelegte Referenzen bleiben im Text; die aufrufende Schicht prüft deren Auflösbarkeit.
     * {@link Matcher#quoteReplacement(String)} bewahrt etwaige Dollarzeichen und Backslashes
     * in Platzhalterwerten als Daten statt als reguläre Ausdrucksreferenzen.
     */
    private String applyTokenValues(String value, Map<String, String> tokenValues) {
        if (value == null || tokenValues.isEmpty()) {
            return value;
        }
        Matcher matcher = TOKEN_REFERENCE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = lookupTokenValue(tokenValues, matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Füllt {@code #}-Positionen aus einer gleich langen konkreten Eingabe, wenn dort
     * Ziffern stehen und sämtliche übrigen Zeichen übereinstimmen. Andernfalls bleibt
     * die Vorlage vollständig unverändert. Dies tokenisiert keine Monats-/Wochentagsnamen
     * und ist keine Ausführung des Timeparsers.
     */
    private String applyTestTokenizedTemplate(String tokenizedTemplate, String input) {
        if (tokenizedTemplate == null
                || input == null
                || !tokenizedTemplate.contains("#")
                || tokenizedTemplate.length() != input.length()) {
            return tokenizedTemplate;
        }

        StringBuilder result = new StringBuilder(tokenizedTemplate.length());
        boolean replacedDigit = false;
        for (int index = 0; index < tokenizedTemplate.length(); index++) {
            char templateCharacter = tokenizedTemplate.charAt(index);
            char inputCharacter = input.charAt(index);
            if (templateCharacter == '#') {
                if (!Character.isDigit(inputCharacter)) {
                    return tokenizedTemplate;
                }
                result.append(inputCharacter);
                replacedDigit = true;
            } else if (templateCharacter == inputCharacter) {
                result.append(templateCharacter);
            } else {
                return tokenizedTemplate;
            }
        }
        return replacedDigit ? result.toString() : tokenizedTemplate;
    }

    private String lookupTokenValue(Map<String, String> tokenValues, String tokenName) {
        String key = tokenLookupKey(tokenName);
        String replacement = tokenValues.get(key);
        if (replacement != null) {
            return replacement;
        }

        for (Map.Entry<String, String> entry : tokenValues.entrySet()) {
            if (tokenLookupKey(entry.getKey()).equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String previewRuleId(RuleGroupForm.RuleVariantForm rule, int ruleIndex) {
        return isBlank(rule.getId()) ? "R" + (ruleIndex + 1) : rule.getId();
    }

    private String previewTestId(RuleGroupForm.TestForm test, int ruleIndex, int testIndex) {
        return isBlank(test.getId()) ? "T" + (ruleIndex + 1) + "_" + (testIndex + 1) : test.getId();
    }

    private boolean isCompleteRule(RuleGroupForm.RuleVariantForm rule) {
        return !isBlank(rule.getInputMask())
                && !isBlank(rule.getInputPattern())
                && !isBlank(rule.getOutputMask())
                && !isBlank(rule.getOutputPattern());
    }

    private boolean isCompleteTest(RuleGroupForm.TestForm test) {
        return !isBlank(test.getInput())
                && !isBlank(test.getTokenized())
                && !isBlank(test.getOutput());
    }

    /**
     * Einfache Zuordnungsprüfung für generierte Tests: gleiche UTF-16-Länge, Ziffern an
     * {@code #}-Stellen und ansonsten identische Zeichen. Keine Priorisierung konkurrierender
     * Regeln. {@code Character.isDigit} ist hier weiter gefasst als die ASCII-Prüfung des
     * Feldvalidators; ein zugeordneter Test kann daher weiterhin einen Feldfehler haben.
     */
    private boolean matchesInputMask(String inputMask, String tokenizedInput) {
        if (inputMask == null || tokenizedInput == null || inputMask.length() != tokenizedInput.length()) {
            return false;
        }
        for (int index = 0; index < inputMask.length(); index++) {
            char maskCharacter = inputMask.charAt(index);
            char inputCharacter = tokenizedInput.charAt(index);
            if (maskCharacter == '#') {
                if (!Character.isDigit(inputCharacter)) {
                    return false;
                }
            } else if (maskCharacter != inputCharacter) {
                return false;
            }
        }
        return true;
    }

    private PlausibilityWarning.PlausibilityEntry plausibilityEntry(GeneratedRuleRow row) {
        return new PlausibilityWarning.PlausibilityEntry(
                row.rule().getId(),
                row.groupId(),
                "rule-" + row.templateId()
        );
    }

    private PlausibilityWarning.PlausibilityEntry plausibilityEntry(RuleTest test, GeneratedRuleRow row) {
        return new PlausibilityWarning.PlausibilityEntry(
                test.getId(),
                row == null ? 0 : row.groupId(),
                row == null ? "" : "rule-" + row.templateId()
        );
    }

    /** Wertgleichheit einer Transformation; IDs und Gruppenzugehörigkeit zählen nicht mit. */
    private String ruleSignature(Rule rule) {
        return signature(rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern());
    }

    /** Testwertgleichheit ohne IDs; der Aufrufer ergänzt den Regelkontext für Dublettenvergleiche. */
    private String testSignature(RuleTest test) {
        return signature(test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan());
    }

    private String signature(String... values) {
        // Ein unsichtbares Trennzeichen verhindert Kollisionen zwischen benachbarten Feldern.
        return String.join("\u001F", java.util.Arrays.stream(values)
                .map(value -> value == null ? "" : value)
                .toList());
    }

    private List<PlausibilityWarning.PlausibilityField> ruleFields(Rule rule) {
        return List.of(
                plausibilityField("Eingabemaske", rule.getInputMask()),
                plausibilityField("Eingabemuster", rule.getInputPattern()),
                plausibilityField("Ausgabemaske", rule.getOutputMask()),
                plausibilityField("Ausgabemuster", rule.getOutputPattern())
        );
    }

    private List<PlausibilityWarning.PlausibilityField> testFields(RuleTest test) {
        return List.of(
                plausibilityField("Eingabe", test.getInput()),
                plausibilityField("Tokenisiert", test.getTokenized()),
                plausibilityField("Ausgabe", test.getOutput()),
                plausibilityField("Zeitraum", test.getTimespan())
        );
    }

    private PlausibilityWarning.PlausibilityField plausibilityField(String label, String value) {
        String safeValue = value == null ? "" : value;
        return new PlausibilityWarning.PlausibilityField(
                label,
                safeValue,
                visibleWhitespace(safeValue),
                safeValue.codePointCount(0, safeValue.length())
        );
    }

    private String visibleWhitespace(String value) {
        return value
                .replace(" ", "␠")
                .replace("\u00A0", "⍽")
                .replace("\u202F", "⎵")
                .replace("\t", "⇥")
                .replace("\r", "⏎")
                .replace("\n", "↵");
    }

    /**
     * Verwendet möglichst die Vorlagen-ID für die erste Variante, danach Suffixe wie
     * {@code R1_2}. Bereits in diesem Ergebnis belegte IDs werden durch Hochzählen umgangen.
     * Die Zuordnung ist an Reihenfolge und Variantenanzahl gebunden und deshalb keine
     * dauerhaft gespeicherte Identität; Oberflächenlinks verwenden die Vorlagen-ID.
     */
    private String generatedId(String baseId, int expansionIndex, int expansionCount, Set<String> usedIds) {
        if (expansionCount == 1 && usedIds.add(baseId)) {
            return baseId;
        }

        String generatedId = expansionIndex == 1 ? baseId : baseId + "_" + expansionIndex;
        int collisionIndex = expansionIndex;
        while (!usedIds.add(generatedId)) {
            collisionIndex++;
            generatedId = baseId + "_" + collisionIndex;
        }
        return generatedId;
    }

    /** Persistiert die Vorlagen in Formularreihenfolge; vorhandene fachliche Inhalte werden nicht expandiert. */
    private void saveRulesForGroup(long groupId, List<RuleGroupForm.RuleVariantForm> rules) {
        // IDs bleiben technische Schlüssel und werden nur bei neuen Formularzeilen automatisch vergeben.
        List<String> existingRuleIds = jdbcTemplate.queryForList("SELECT id FROM rules", String.class);
        List<String> existingTestIds = jdbcTemplate.queryForList("SELECT id FROM tests", String.class);
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = rules.get(ruleIndex);
            String ruleId = rule.getId();
            if (ruleId == null || ruleId.isBlank()) {
                ruleId = nextId(existingRuleIds, "R");
                existingRuleIds.add(ruleId);
            }
            jdbcTemplate.update("""
                    INSERT INTO rules (id, group_id, input_mask, input_pattern, output_mask, output_pattern, position)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, ruleId, groupId, rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern(), ruleIndex);

            for (int testIndex = 0; testIndex < rule.getTests().size(); testIndex++) {
                RuleGroupForm.TestForm test = rule.getTests().get(testIndex);
                String testId = test.getId();
                if (testId == null || testId.isBlank()) {
                    testId = nextId(existingTestIds, "T");
                    existingTestIds.add(testId);
                }
                jdbcTemplate.update("""
                        INSERT INTO tests (id, rule_id, input, tokenized, output, timespan, position)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, testId, ruleId, test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan(), testIndex);
            }
        }
    }

    private long insertGroup(String name, String description) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO rule_groups (name, description) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, name);
            statement.setString(2, description);
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private long insertToken(String name, String description) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tokens (name, description) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, name);
            statement.setString(2, description);
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private void createSchema() {
        jdbcTemplate.execute("PRAGMA foreign_keys = ON");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rule_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    description TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rules (
                    id TEXT PRIMARY KEY,
                    group_id INTEGER NOT NULL,
                    input_mask TEXT NOT NULL,
                    input_pattern TEXT NOT NULL,
                    output_mask TEXT NOT NULL,
                    output_pattern TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (group_id) REFERENCES rule_groups(id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tests (
                    id TEXT PRIMARY KEY,
                    rule_id TEXT NOT NULL,
                    input TEXT NOT NULL,
                    tokenized TEXT NOT NULL,
                    output TEXT NOT NULL,
                    timespan TEXT,
                    position INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (rule_id) REFERENCES rules(id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tokens (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    description TEXT
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS token_values (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    token_id INTEGER NOT NULL,
                    value TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (token_id) REFERENCES tokens(id) ON DELETE CASCADE
                )
                """);
    }

    private int countGroups() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rule_groups", Integer.class);
        return count == null ? 0 : count;
    }

    private boolean csvSeedFilesAvailable() {
        return Files.isRegularFile(rulesFile) && Files.isRegularFile(testsFile);
    }

    /** Entfernt die historische Spalte {@code normalized} durch Kopieren in das aktuelle Tests-Schema. */
    private void migrateTestsSchema() {
        if (!tableColumnExists("tests", "normalized")) {
            return;
        }
        // SQLite kann Spalten nur eingeschränkt entfernen; deshalb wird die Tabelle kontrolliert neu aufgebaut.
        jdbcTemplate.execute("DROP TABLE IF EXISTS tests_migrated");
        jdbcTemplate.execute("""
                CREATE TABLE tests_migrated (
                    id TEXT PRIMARY KEY,
                    rule_id TEXT NOT NULL,
                    input TEXT NOT NULL,
                    tokenized TEXT NOT NULL,
                    output TEXT NOT NULL,
                    timespan TEXT,
                    position INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (rule_id) REFERENCES rules(id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                INSERT INTO tests_migrated (id, rule_id, input, tokenized, output, timespan, position)
                SELECT id, rule_id, input, tokenized, output, timespan, position
                FROM tests
                """);
        jdbcTemplate.execute("DROP TABLE tests");
        jdbcTemplate.execute("ALTER TABLE tests_migrated RENAME TO tests");
    }

    private boolean tableColumnExists(String tableName, String columnName) {
        return jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")").stream()
                .map(row -> (String) row.get("name"))
                .anyMatch(columnName::equalsIgnoreCase);
    }

    /**
     * Übernimmt Seed-Zeilen ohne die Formularvalidierung; pro importierter Regel entsteht
     * eine Gruppe. Fachliche Altfehler bleiben damit untersuchbar und werden später im
     * Plausibilitätscheck sichtbar. SQLite-Schlüssel- und Schemakonflikte sind davon unabhängig.
     */
    private void importCsv() {
        try {
            List<Rule> rules = readRules();
            List<RuleTest> tests = readTests();
            int position = 0;
            for (Rule rule : rules) {
                long groupId = insertGroup(rule.getId() + " " + rule.getInputMask(), "");
                jdbcTemplate.update("""
                        INSERT INTO rules (id, group_id, input_mask, input_pattern, output_mask, output_pattern, position)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, rule.getId(), groupId, rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern(), position++);
            }
            for (int index = 0; index < tests.size(); index++) {
                RuleTest test = tests.get(index);
                jdbcTemplate.update("""
                        INSERT INTO tests (id, rule_id, input, tokenized, output, timespan, position)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, test.getId(), test.getRuleId(), test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan(), index);
            }
        } catch (IOException e) {
            throw new CsvRepositoryException("CSV-Dateien konnten nicht importiert werden.", e);
        }
    }

    private void seedTokens() {
        ensureToken("approx", "Ungefähre Zeitangaben", List.of("um", "ca.", "circa", "zirka", "etwa"));
        ensureToken("vchr", "Zeitangaben vor Christus", List.of("v. Chr.", "v.Chr.", "v. Chr", "v.Chr"));
    }

    private void ensureToken(String name, String description, List<String> values) {
        Long tokenId = tokenIdByName(name);
        if (tokenId == null) {
            tokenId = insertToken(name, description);
        }
        ensureTokenValues(tokenId, values);
    }

    private Long tokenIdByName(String name) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tokens WHERE LOWER(name) = LOWER(?)",
                Long.class,
                name
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void ensureTokenValues(long tokenId, List<String> values) {
        List<String> existingValues = tokenValues(tokenId);
        int position = existingValues.size();
        for (String value : values) {
            if (existingValues.contains(value)) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO token_values (token_id, value, position) VALUES (?, ?, ?)",
                    tokenId,
                    value,
                    position++
            );
        }
    }

    private void insertTokenValues(long tokenId, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            jdbcTemplate.update(
                    "INSERT INTO token_values (token_id, value, position) VALUES (?, ?, ?)",
                    tokenId,
                    values.get(index),
                    index
            );
        }
    }

    private List<String> tokenValues(long tokenId) {
        return jdbcTemplate.queryForList(
                "SELECT value FROM token_values WHERE token_id = ? ORDER BY position, value",
                String.class,
                tokenId
        );
    }

    private Map<Long, List<String>> tokenValuesByTokenId() {
        Map<Long, List<String>> valuesByTokenId = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT token_id, value
                FROM token_values
                ORDER BY token_id, position, value
                """, (RowCallbackHandler) rs -> valuesByTokenId
                .computeIfAbsent(rs.getLong("token_id"), ignored -> new ArrayList<>())
                .add(rs.getString("value")));
        return valuesByTokenId;
    }

    private Map<String, List<String>> tokenValuesByName() {
        Map<String, List<String>> valuesByName = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT t.name, tv.value
                FROM tokens t
                JOIN token_values tv ON tv.token_id = t.id
                ORDER BY t.name, tv.position, tv.value
                """, (RowCallbackHandler) rs -> valuesByName
                .computeIfAbsent(tokenLookupKey(rs.getString("name")), ignored -> new ArrayList<>())
                .add(rs.getString("value")));
        return valuesByName;
    }

    private List<String> tokenValuesForName(String tokenName, Map<String, List<String>> valuesByName) {
        return valuesByName.getOrDefault(tokenLookupKey(tokenName), List.of());
    }

    /** Einheitlicher Schlüssel für Expansion und Abhängigkeiten: Aliasauflösung und {@code Locale.ROOT}. */
    private String tokenLookupKey(String tokenName) {
        return canonicalTokenName(tokenName).toLowerCase(Locale.ROOT);
    }

    private String canonicalTokenName(String tokenName) {
        // Historische Schreibweise aus frühen Entwürfen weiter akzeptieren.
        if ("aprox".equalsIgnoreCase(tokenName)) {
            return "approx";
        }
        return tokenName;
    }

    /** Entfernt leere Zeilen, bewahrt aber reine Leerzeichen und deren fachlich relevante Position. */
    private List<String> splitValues(String values) {
        // Keine Trimmung: führende und folgende Leerzeichen sind fachlich relevante Token-Bestandteile.
        List<String> result = new ArrayList<>();
        for (String value : values.split("\\R", -1)) {
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private List<Rule> readRules() throws IOException {
        try (Reader reader = Files.newBufferedReader(rulesFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader(RULE_HEADERS).setSkipHeaderRecord(true).get().parse(reader)) {
            List<Rule> rules = new ArrayList<>();
            parser.forEach(record -> rules.add(new Rule(
                    record.get("id"),
                    record.get("inputMask"),
                    record.get("inputPattern"),
                    record.get("outputMask"),
                    record.get("outputPattern")
            )));
            return rules;
        }
    }

    private List<RuleTest> readTests() throws IOException {
        try (Reader reader = Files.newBufferedReader(testsFile, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            List<RuleTest> tests = new ArrayList<>();
            parser.forEach(record -> tests.add(new RuleTest(
                    record.get("id"),
                    record.get("for"),
                    record.get("input"),
                    record.get("tokenized"),
                    record.get("output"),
                    record.get("timespan")
            )));
            return tests;
        }
    }

    private String nextId(List<String> ids, String prefix) {
        int next = ids.stream()
                .filter(id -> id != null && id.startsWith(prefix))
                .map(NUMERIC_SUFFIX::matcher)
                .filter(Matcher::matches)
                .filter(matcher -> matcher.group(1).equals(prefix))
                .map(matcher -> matcher.group(2))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0) + 1;
        return prefix + next;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Unexpandierte Regelwerte mit ihrer Herkunft; {@code groupId == 0} steht für eine neue Formulargruppe. */
    private record RuleTemplate(
            String id,
            long groupId,
            String inputMask,
            String inputPattern,
            String outputMask,
            String outputPattern
    ) {
    }

    /** Testvorlage mit Bezug auf eine Regelvorlage, nicht auf eine einzelne generierte Variante. */
    private record RuleTestTemplate(
            String id,
            String ruleId,
            String input,
            String tokenized,
            String output,
            String timespan
    ) {
    }

    /**
     * Konkrete Regel samt Rückverweis auf Vorlage und Gruppe. Die verwendete
     * {@code tokenValues}-Belegung wird an ihre Testvorlagen weitergereicht;
     * {@code expansionIndex} ist einsbasiert und beschreibt die aktuelle Expansion.
     */
    private record GeneratedRuleRow(
            String templateId,
            long groupId,
            int expansionIndex,
            int expansionCount,
            Map<String, String> tokenValues,
            Rule rule
    ) {
    }

    /** Lädt und expandiert den Vergleichsbestand genau einmal für eine Formularprüfung. */
    private ValidationContext validationContext(Map<String, List<String>> tokens) {
        List<GeneratedRuleRow> rows = safeGeneratedRuleRows(ruleTemplates((Long) null), tokens);
        return new ValidationContext(rows, safeGeneratedTests(rows, testTemplates((Long) null), tokens));
    }

    /**
     * Notwendige Vorbedingung für eine statische Maskenüberschneidung: gleiche
     * Ziffern-/Literalstruktur und gleiche Anzahl von #-Stellen. ASCII-Ziffern
     * und # teilen sich eine Kategorie, kompatible Leerzeichen ebenfalls.
     * Der Schlüssel ersetzt nicht die abschließende Prüfung durch
     * {@link RuleMaskOverlap#witness(String, String)}: 1# und 2# haben etwa
     * denselben Schlüssel, aber keine gemeinsame Belegung.
     */
    private MaskShape maskShape(String mask) {
        StringBuilder shape = new StringBuilder(mask.length());
        int hashes = 0;
        for (int index = 0; index < mask.length(); index++) {
            char character = mask.charAt(index);
            if (character == '#') {
                hashes++;
                shape.append('#');
            } else if (character >= '0' && character <= '9') {
                shape.append('#');
            } else {
                shape.append(Character.isSpaceChar(character) ? ' ' : character);
            }
        }
        return new MaskShape(shape.toString(), hashes);
    }

    private record MaskShape(String characters, int hashes) {
    }

    /**
     * Suchindizes eines einzelnen Prüf- oder Exportaufrufs. Regeln und Tests
     * werden einmal expandiert und gemeinsam von allen Gruppen verwendet.
     * Testsignaturen merken sich die Herkunftsgruppen, damit beim Bearbeiten
     * nur die eigene gespeicherte Fassung aus dem Vergleich ausgeschlossen wird.
     * Es gibt keinen aufrufübergreifenden Cache; Änderungen an Vorlagen und
     * Platzhaltern sind bei der nächsten Prüfung sofort sichtbar.
     */
    private final class ValidationContext {
        private final List<GeneratedRuleRow> rows;
        private final List<RuleTest> tests;
        private final Map<Long, List<GeneratedRuleRow>> rowsByGroup = new LinkedHashMap<>();
        private final Map<String, List<GeneratedRuleRow>> rowsByMask = new LinkedHashMap<>();
        private final Map<MaskShape, List<GeneratedRuleRow>> rowsByShape = new LinkedHashMap<>();
        private final Map<String, Set<Long>> testGroupsBySignature = new LinkedHashMap<>();
        private final Set<String> testedRuleIds = new HashSet<>();

        private ValidationContext(List<GeneratedRuleRow> rows, List<RuleTest> tests) {
            this.rows = rows;
            this.tests = tests;
            Map<String, GeneratedRuleRow> rowsById = new LinkedHashMap<>();
            for (GeneratedRuleRow row : rows) {
                rowsByGroup.computeIfAbsent(row.groupId(), ignored -> new ArrayList<>()).add(row);
                rowsByMask.computeIfAbsent(row.rule().getInputMask(), ignored -> new ArrayList<>()).add(row);
                rowsByShape.computeIfAbsent(maskShape(row.rule().getInputMask()), ignored -> new ArrayList<>()).add(row);
                rowsById.put(row.rule().getId(), row);
            }
            for (RuleTest test : tests) {
                GeneratedRuleRow row = rowsById.get(test.getRuleId());
                if (row != null) {
                    String key = signature(ruleSignature(row.rule()), testSignature(test));
                    testGroupsBySignature.computeIfAbsent(key, ignored -> new HashSet<>()).add(row.groupId());
                    // Unvollständige Altvorlagen bleiben diagnostizierbar, gelten
                    // aber wie im Formularpfad nicht als vorhandene Testabdeckung.
                    if (!isBlank(test.getInput()) && !isBlank(test.getTokenized()) && !isBlank(test.getOutput())
                            && !isBlank(row.rule().getInputMask()) && !isBlank(row.rule().getInputPattern())
                            && !isBlank(row.rule().getOutputMask()) && !isBlank(row.rule().getOutputPattern())) {
                        testedRuleIds.add(test.getRuleId());
                    }
                }
            }
        }

        private boolean hasTestOutsideGroup(String signature, Long groupId) {
            Set<Long> groups = testGroupsBySignature.get(signature);
            return groups != null && (groupId == null || groups.size() > 1 || !groups.contains(groupId));
        }
    }

    /** Passende expandierte Testwerte vor der gemeinsamen Vergabe kollisionsfreier Test-IDs. */
    private record GeneratedTestCandidate(
            String templateId,
            String ruleId,
            String input,
            String tokenized,
            String output,
            String timespan
    ) {
    }
}
