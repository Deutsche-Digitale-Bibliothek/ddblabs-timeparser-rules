package de.deutsche_digitale_bibliothek.timeparser.repository;

import de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService;
import de.deutsche_digitale_bibliothek.timeparser.validation.RuleMaskOverlap;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.Issue;
import de.deutsche_digitale_bibliothek.timeparser.validation.RulePatternValidator.RelatedRule;
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
import static org.assertj.core.api.Assertions.tuple;

class RuleOverlapIndexTest {

    private static final char[] ALPHABET = {'#', '0', '1', ' ', '\u00A0', '\u202F', 'a'};

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
                    expected.add(overlap(current, "R" + (current + 1), "R" + (previous + 1), 0, example.get()));
                }
            }
        }

        assertOverlaps(overlaps(form), expected);
        assertThat(expected).isNotEmpty();
    }

    @Test
    void comparesStoredGroupsWithExactWhitespaceAndRejectsConflictingDigits() {
        RuleGroupForm compatible = group("Passende Leerzeichen");
        compatible.getRules().add(rule("R1", "1#0 \u00A0"));
        long compatibleId = repository.createGroup(compatible);
        RuleGroupForm differentDigit = group("Andere feste Ziffer");
        differentDigit.getRules().add(rule("R2", "2#0 \u00A0"));
        repository.createGroup(differentDigit);
        RuleGroupForm differentSpecificity = group("Andere Anzahl von Ziffernstellen");
        differentSpecificity.getRules().add(rule("R3", "1## \u00A0"));
        repository.createGroup(differentSpecificity);
        RuleGroupForm narrowSpace = group("Schmales geschütztes Leerzeichen");
        narrowSpace.getRules().add(rule("R4", "1#0 \u202F"));
        repository.createGroup(narrowSpace);
        RuleGroupForm swappedSpaces = group("Vertauschte Leerzeichen");
        swappedSpaces.getRules().add(rule("R5", "1#0\u00A0 "));
        repository.createGroup(swappedSpaces);

        RuleGroupForm incoming = group("Neue Maske");
        incoming.getRules().add(rule("R6", "10# \u00A0"));

        assertOverlaps(overlaps(incoming), List.of(overlap(0, "R6", "R1", compatibleId, "100 \u00A0")));
    }

    @Test
    void keepsBeforeChristWhitespaceVariantsDistinctWithinTheSameTemplate() {
        var token = repository.findTokenForm(repository.findTokens().stream()
                .filter(value -> value.name().equals("vchr")).findFirst().orElseThrow().id());
        token.setValues("v. Chr.\nv.\u00A0Chr.\nv.\u202FChr.");
        repository.saveToken(token);
        RuleGroupForm form = group("Vor Christus mit verschiedenen Leerzeichen");
        var beforeChrist = rule("R348", "######## ~vchr");
        beforeChrist.setOutputMask("-########");
        beforeChrist.setOutputPattern("-JJJJJJJJ");
        form.getRules().add(beforeChrist);

        assertThat(repository.previewRules(form)).extracting(value -> value.getInputMask())
                .containsExactly("######## v. Chr.", "######## v.\u00A0Chr.", "######## v.\u202FChr.");
        assertThat(overlaps(form)).isEmpty();

        long groupId = repository.createGroup(form);
        assertThat(overlaps(repository.findGroupForm(groupId))).isEmpty();
        assertThat(repository.plausibilityWarningsForGroup(groupId))
                .noneMatch(warning -> warning.message().contains("überschneiden"));
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

    private void assertOverlaps(List<Issue> actual, List<Issue> expected) {
        assertThat(actual).extracting(Issue::field, Issue::relatedRules)
                .containsExactlyElementsOf(expected.stream().map(issue -> tuple(issue.field(), issue.relatedRules())).toList());
        for (int index = 0; index < expected.size(); index++) {
            assertThat(actual.get(index).message()).contains(expected.get(index).message());
        }
    }

    private Issue overlap(int ruleIndex, String currentId, String otherId, long otherGroupId, String example) {
        String visibleExample = example.replace(" ", "␠").replace("\u00A0", "⍽").replace("\u202F", "⎵");
        return new Issue("rules[" + ruleIndex + "].inputMask", "rule.mask.overlap",
                "Beispiel: " + visibleExample,
                List.of(new RelatedRule(currentId, 0, currentId), new RelatedRule(otherId, otherGroupId, otherId)));
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
