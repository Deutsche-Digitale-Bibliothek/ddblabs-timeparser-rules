package de.deutsche_digitale_bibliothek.timeparser.export;

import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportDatasetFactoryTest {

    @Test
    void remapsAllDistinctTestsOfDuplicateRulesToTheFirstRule() {
        Rule first = yearRule("R1");
        Rule duplicate = yearRule("R2");
        RuleTest firstTest = yearTest("T1", "R1", "1900");
        RuleTest duplicateTest = yearTest("T2", "R2", "1900");
        RuleTest additionalTest = yearTest("T3", "R2", "2000");

        ExportDataset dataset = ExportDatasetFactory.create(List.of(first, duplicate), List.of(firstTest, duplicateTest, additionalTest));

        assertThat(dataset.rules()).extracting(Rule::getId).containsExactly("R1");
        assertThat(dataset.tests()).extracting(RuleTest::getId).containsExactly("T1", "T3");
        assertThat(dataset.tests()).extracting(RuleTest::getRuleId).containsOnly("R1");
        assertThat(dataset.tests()).extracting(RuleTest::getInput).containsExactly("1900", "2000");
        assertThat(dataset.warnings()).isEmpty();
        assertThat(additionalTest.getRuleId()).isEqualTo("R2");
    }

    @Test
    void keepsConflictingTransformationsAndTheirIdenticalTestsForDiagnosis() {
        Rule first = yearRule("R1");
        Rule conflicting = new Rule("R2", "####", "JJJJ", "##", "JJ");

        ExportDataset dataset = ExportDatasetFactory.create(List.of(first, conflicting), List.of(
                yearTest("T1", "R1", "1900"), yearTest("T2", "R2", "1900")));

        assertThat(dataset.rules()).extracting(Rule::getId).containsExactly("R1", "R2");
        assertThat(dataset.tests()).extracting(RuleTest::getRuleId).containsExactly("R1", "R2");
    }

    @Test
    void reportsOmittedOrphanTestsInsteadOfExportingBrokenReferences() {
        ExportDataset dataset = ExportDatasetFactory.create(List.of(yearRule("R1")), List.of(yearTest("T1", "R404", "1900")));

        assertThat(dataset.tests()).isEmpty();
        assertThat(dataset.warnings()).singleElement().asString().contains("T1", "R404", "ausgelassen");
    }

    @Test
    void retainsTestsWithDifferentExpectedValues() {
        RuleTest first = yearTest("T1", "R1", "1900");
        RuleTest differentOutput = yearTest("T2", "R1", "1900");
        differentOutput.setOutput("2000");
        RuleTest differentTimespan = yearTest("T3", "R1", "1900");
        differentTimespan.setTimespan("1900-01-01/1901-12-31");

        ExportDataset dataset = ExportDatasetFactory.create(List.of(yearRule("R1")), List.of(first, differentOutput, differentTimespan));

        assertThat(dataset.tests()).extracting(RuleTest::getId).containsExactly("T1", "T2", "T3");
    }

    @Test
    void signatureValuesCannotCollideThroughSeparatorCharacters() {
        Rule first = new Rule("R1", "a\u001fb", "c", "d", "e");
        Rule second = new Rule("R2", "a", "b\u001fc", "d", "e");

        assertThat(ExportDatasetFactory.create(List.of(first, second), List.of()).rules()).hasSize(2);
    }

    @Test
    void makesImmutableListSnapshotsAndCopiesInputModels() {
        Rule rule = yearRule("R1");
        RuleTest test = yearTest("T1", "R1", "1900");
        var rules = new ArrayList<>(List.of(rule));
        var tests = new ArrayList<>(List.of(test));
        ExportDataset dataset = ExportDatasetFactory.create(rules, tests);
        rules.clear();
        tests.clear();
        rule.setInputMask("changed");
        test.setInput("changed");

        assertThat(dataset.rules()).singleElement().extracting(Rule::getInputMask).isEqualTo("####");
        assertThat(dataset.tests()).singleElement().extracting(RuleTest::getInput).isEqualTo("1900");
        assertThatThrownBy(() -> dataset.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dataset.tests().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> dataset.warnings().add("changed")).isInstanceOf(UnsupportedOperationException.class);
    }

    private Rule yearRule(String id) {
        return new Rule(id, "####", "JJJJ", "####", "JJJJ");
    }

    private RuleTest yearTest(String id, String ruleId, String input) {
        return new RuleTest(id, ruleId, input, input, input, input + "-01-01/" + input + "-12-31");
    }
}
