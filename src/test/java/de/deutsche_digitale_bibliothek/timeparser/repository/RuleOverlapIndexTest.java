package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.validation.RuleMaskOverlap;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue;
import de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleOverlapIndexTest {

    private static final char[] ALPHABET = {'#', '0', '1', ' ', '\u00A0', 'a'};

    @TempDir
    Path tempDir;

    private SqliteRuleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SqliteRuleRepository(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("rules.sqlite"))), tempDir.resolve("missing-rules.csv"),
                tempDir.resolve("missing-tests.csv"), new CsvExportService());
        repository.initialize();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void indexedCandidatesPreserveEveryBruteForceOverlap(int length) {
        List<String> masks = new ArrayList<>();
        masksOfLength("", length, masks);
        RuleGroupForm form = group("Maskenvergleich");
        for (int index = 0; index < masks.size(); index++) {
            form.getRules().add(rule("R" + (index + 1), masks.get(index)));
        }

        // Referenz ist der vollständige Paarvergleich ohne Vorauswahl nach Maskenform.
        List<Issue> expected = new ArrayList<>();
        for (int current = 0; current < masks.size(); current++) {
            for (int previous = 0; previous < current; previous++) {
                var example = RuleMaskOverlap.witness(masks.get(current), masks.get(previous));
                if (example.isPresent()) {
                    expected.add(overlap(current, "R" + (previous + 1), example.get()));
                }
            }
        }

        assertThat(overlaps(form)).containsExactlyElementsOf(expected);
        assertThat(expected).isNotEmpty();
    }

    @Test
    void comparesStoredGroupsWithCompatibleWhitespaceAndRejectsConflictingDigits() {
        RuleGroupForm compatible = group("Passende Leerzeichen");
        compatible.getRules().add(rule("R1", "1#0 \u00A0"));
        repository.createGroup(compatible);
        RuleGroupForm differentDigit = group("Andere feste Ziffer");
        differentDigit.getRules().add(rule("R2", "2#0 \u00A0"));
        repository.createGroup(differentDigit);
        RuleGroupForm differentSpecificity = group("Andere Anzahl von Ziffernstellen");
        differentSpecificity.getRules().add(rule("R3", "1## \u00A0"));
        repository.createGroup(differentSpecificity);

        RuleGroupForm incoming = group("Neue Maske");
        incoming.getRules().add(rule("R4", "10#\u00A0 "));

        assertThat(overlaps(incoming)).containsExactly(overlap(0, "R1", "100\u00A0 "));
    }

    private List<Issue> overlaps(RuleGroupForm form) {
        return repository.validationNotices(form).stream()
                .filter(issue -> issue.code().equals("rule.mask.overlap"))
                .toList();
    }

    private void masksOfLength(String prefix, int remaining, List<String> masks) {
        if (remaining == 0) {
            if (!prefix.isBlank()) {
                masks.add(prefix);
            }
            return;
        }
        for (char character : ALPHABET) {
            masksOfLength(prefix + character, remaining - 1, masks);
        }
    }

    private Issue overlap(int ruleIndex, String otherId, String example) {
        String visibleExample = example.replace(" ", "␠").replace("\u00A0", "⍽");
        return new Issue("rules[" + ruleIndex + "].inputMask", "rule.mask.overlap",
                "Die Eingabemaske könnte sich mit " + otherId + " überschneiden (Beispiel: " + visibleExample
                        + "). Beide haben gleich viele #-Stellen. Die tatsächliche Regelauswahl ist nicht geprüft.");
    }

    private RuleGroupForm group(String name) {
        RuleGroupForm form = new RuleGroupForm();
        form.setName(name);
        return form;
    }

    private RuleGroupForm.RuleVariantForm rule(String id, String mask) {
        var rule = new RuleGroupForm.RuleVariantForm();
        rule.setId(id);
        rule.setInputMask(mask);
        rule.setInputPattern(mask.replace('#', 'J'));
        rule.setOutputMask(mask);
        rule.setOutputPattern(mask.replace('#', 'J'));
        return rule;
    }
}
