package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.model.PlausibilityWarning;
import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleGroupSummary;
import de.deutsche_digitale_bibliothek.timeparser.model.RulePreview;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.model.Token;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import de.deutsche_digitale_bibliothek.timeparser.web.TokenForm;
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
 * Repository für Regelgruppen, generierte Regeln, Tests, Tokens und CSV-Import/-Export.
 *
 * <p>Die Anwendung speichert bearbeitbare Vorlagen in SQLite. Beim Anzeigen und
 * Exportieren werden Token-Platzhalter wie {@code ~approx} zu konkreten Regeln
 * und Tests expandiert.</p>
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

    public boolean groupExists(long groupId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rule_groups WHERE id = ?", Integer.class, groupId);
        return count != null && count > 0;
    }

    public boolean ruleExists(long groupId, String ruleId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rules
                WHERE group_id = ? AND id = ?
                """, Integer.class, groupId, ruleId);
        return count != null && count > 0;
    }

    @Transactional
    public long createGroup(RuleGroupForm form) {
        long groupId = insertGroup(form.getName(), form.getDescription());
        saveRulesForGroup(groupId, form.getRules());
        return groupId;
    }

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

    @Transactional
    public void deleteGroup(long groupId) {
        jdbcTemplate.update("""
                DELETE FROM tests
                WHERE rule_id IN (SELECT id FROM rules WHERE group_id = ?)
                """, groupId);
        jdbcTemplate.update("DELETE FROM rules WHERE group_id = ?", groupId);
        jdbcTemplate.update("DELETE FROM rule_groups WHERE id = ?", groupId);
    }

    @Transactional
    public void deleteRule(long groupId, String ruleId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rules WHERE group_id = ?", Integer.class, groupId);
        if (count != null && count <= 1) {
            throw new CsvRepositoryException("Eine Regelgruppe muss mindestens eine Regel haben.");
        }
        jdbcTemplate.update("DELETE FROM tests WHERE rule_id = ?", ruleId);
        jdbcTemplate.update("DELETE FROM rules WHERE id = ? AND group_id = ?", ruleId, groupId);
    }

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

    public boolean testExists(long groupId, String ruleId, String testId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM tests t
                JOIN rules r ON r.id = t.rule_id
                WHERE r.group_id = ? AND r.id = ? AND t.id = ?
                """, Integer.class, groupId, ruleId, testId);
        return count != null && count > 0;
    }

    public List<Rule> generatedRules() {
        return generatedRuleRows((Long) null, tokenValuesByName()).stream()
                .map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR))
                .toList();
    }

    public List<Rule> generatedRulesForGroup(long groupId) {
        return generatedRuleRows(groupId, tokenValuesByName()).stream()
                .map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR))
                .toList();
    }

    public List<RuleTest> generatedTests() {
        return generatedTests(null);
    }

    public List<RuleTest> generatedTestsForGroup(long groupId) {
        return generatedTests(groupId);
    }

    public List<Rule> previewRules(RuleGroupForm form) {
        return generatedRuleRows(ruleTemplates(form), tokenValuesByName()).stream()
                .map(GeneratedRuleRow::rule)
                .sorted(Comparator.comparing(Rule::getId, ID_COMPARATOR))
                .toList();
    }

    public List<RuleTest> previewTests(RuleGroupForm form) {
        return generatedTests(ruleTemplates(form), testTemplates(form), tokenValuesByName());
    }

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

    public List<PlausibilityWarning> plausibilityWarnings() {
        return plausibilityWarnings(null);
    }

    public List<PlausibilityWarning> plausibilityWarningsForGroup(long groupId) {
        return plausibilityWarnings(groupId);
    }

    /**
     * Sucht Token-Referenzen in einem Formular, bevor gespeichert wird.
     */
    public List<String> unknownTokenNames(RuleGroupForm form) {
        Map<String, List<String>> tokenValues = tokenValuesByName();
        Set<String> tokenNames = new LinkedHashSet<>();
        for (RuleGroupForm.RuleVariantForm rule : form.getRules()) {
            collectTokenNames(tokenNames, rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern());
            for (RuleGroupForm.TestForm test : rule.getTests()) {
                collectTokenNames(tokenNames, test.getInput(), test.getTokenized(), test.getOutput(), test.getTimespan());
            }
        }
        return tokenNames.stream()
                .filter(tokenName -> tokenValuesForName(tokenName, tokenValues).isEmpty())
                .toList();
    }

    private List<PlausibilityWarning> plausibilityWarnings(Long groupId) {
        Map<String, List<String>> tokenValues = tokenValuesByName();
        List<RuleTemplate> ruleTemplates = ruleTemplates(groupId);
        List<PlausibilityWarning> warnings = new ArrayList<>();
        List<GeneratedRuleRow> generatedRuleRows = generatedRuleRows(ruleTemplates, tokenValues);
        Map<String, GeneratedRuleRow> rowsByRuleId = new LinkedHashMap<>();
        for (GeneratedRuleRow row : generatedRuleRows) {
            rowsByRuleId.put(row.rule().getId(), row);
        }
        addDuplicateRuleWarnings(warnings, generatedRuleRows);
        addDuplicateTestWarnings(
                warnings,
                generateTestsForRows(generatedRuleRows, testTemplates(groupId), tokenValues),
                rowsByRuleId
        );
        return warnings;
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
            testsByValues.computeIfAbsent(testSignature(test), key -> new ArrayList<>()).add(test);
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

    public byte[] rulesAndTestsZip() {
        List<Rule> exportedRules = exportRules();
        return csvExportService.rulesAndTestsZip(exportedRules, exportTests(exportedRules));
    }

    public byte[] rulesCsv() {
        return csvExportService.rulesCsv(exportRules());
    }

    public byte[] testsCsv() {
        return csvExportService.testsCsv(exportTests());
    }

    public byte[] tokensCsv() {
        return csvExportService.tokensCsv(findTokens());
    }

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

    public boolean tokenExists(long tokenId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tokens WHERE id = ?", Integer.class, tokenId);
        return count != null && count > 0;
    }

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

    private List<RuleTest> generatedTests(Long groupId) {
        return generatedTests(ruleTemplates(groupId), testTemplates(groupId), tokenValuesByName());
    }

    private List<RuleTest> generatedTests(List<RuleTemplate> ruleTemplates,
                                          List<RuleTestTemplate> tests,
                                          Map<String, List<String>> tokenValuesByName) {
        List<GeneratedRuleRow> generatedRules = generatedRuleRows(ruleTemplates, tokenValuesByName);
        return generateTestsForRows(generatedRules, tests, tokenValuesByName);
    }

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

    private List<GeneratedRuleRow> generatedRuleRows(Long groupId, Map<String, List<String>> tokenValuesByName) {
        return generatedRuleRows(ruleTemplates(groupId), tokenValuesByName);
    }

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

    private void collectTokenNames(Set<String> tokenNames, String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            Matcher matcher = TOKEN_REFERENCE.matcher(value);
            while (matcher.find()) {
                tokenNames.add(matcher.group(1));
            }
        }
    }

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
        String replacement = tokenValues.get(tokenName);
        if (replacement != null) {
            return replacement;
        }

        String canonicalTokenName = canonicalTokenName(tokenName);
        replacement = tokenValues.get(canonicalTokenName);
        if (replacement != null) {
            return replacement;
        }

        for (Map.Entry<String, String> entry : tokenValues.entrySet()) {
            if (canonicalTokenName(entry.getKey()).equals(canonicalTokenName)) {
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

    private List<Rule> exportRules() {
        // Der Export soll eine einfache CSV liefern; generierte Duplikate werden bewusst ausgelassen.
        Set<String> exportedSignatures = new LinkedHashSet<>();
        return generatedRules().stream()
                .filter(rule -> exportedSignatures.add(ruleSignature(rule)))
                .toList();
    }

    private List<RuleTest> exportTests(List<Rule> exportedRules) {
        // Tests werden nur exportiert, wenn ihre Regel ebenfalls im deduplizierten Export liegt.
        Set<String> exportedRuleIds = new HashSet<>(exportedRules.stream().map(Rule::getId).toList());
        Set<String> exportedSignatures = new LinkedHashSet<>();
        return generatedTests().stream()
                .filter(test -> exportedRuleIds.contains(test.getRuleId()))
                .filter(test -> exportedSignatures.add(testSignature(test)))
                .toList();
    }

    private List<RuleTest> exportTests() {
        Set<String> exportedSignatures = new LinkedHashSet<>();
        return generatedTests().stream()
                .filter(test -> exportedSignatures.add(testSignature(test)))
                .toList();
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

    private String ruleSignature(Rule rule) {
        return signature(rule.getInputMask(), rule.getInputPattern(), rule.getOutputMask(), rule.getOutputPattern());
    }

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

    private record RuleTemplate(
            String id,
            long groupId,
            String inputMask,
            String inputPattern,
            String outputMask,
            String outputPattern
    ) {
    }

    private record RuleTestTemplate(
            String id,
            String ruleId,
            String input,
            String tokenized,
            String output,
            String timespan
    ) {
    }

    private record GeneratedRuleRow(
            String templateId,
            long groupId,
            int expansionIndex,
            int expansionCount,
            Map<String, String> tokenValues,
            Rule rule
    ) {
    }

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
