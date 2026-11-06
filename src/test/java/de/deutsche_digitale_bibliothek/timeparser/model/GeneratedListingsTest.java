package de.deutsche_digitale_bibliothek.timeparser.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedListingsTest {

    @Test
    void copiesTheListingCollectionsAndPreventsCollectionMutation() {
        Rule rule = new Rule("R1", "####", "JJJJ", "####", "JJJJ");
        RuleTest test = new RuleTest("T1", "R1", "1900", "1900", "1900", "");
        RuleLocation location = new RuleLocation("R1", "R1", 1, "Jahre");
        List<Rule> rules = new ArrayList<>(List.of(rule));
        List<RuleTest> tests = new ArrayList<>(List.of(test));
        Map<String, RuleLocation> locations = new LinkedHashMap<>(Map.of("R1", location));
        var ruleListing = new GeneratedRuleListing(rules, locations);
        var testListing = new GeneratedTestListing(tests, locations);
        rules.clear();
        tests.clear();
        locations.clear();

        assertThat(ruleListing.rules()).containsExactly(rule);
        assertThat(testListing.tests()).containsExactly(test);
        assertThat(ruleListing.locations()).containsEntry("R1", location);
        assertThat(testListing.locations()).containsEntry("R1", location);
        assertThatThrownBy(() -> ruleListing.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> testListing.tests().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ruleListing.locations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> testListing.locations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void encodesImportedTemplateIdsAsSafeUriFragments() {
        var location = new RuleLocation("R 1#ä_2", "R 1#ä", 1, "Sonderzeichen");

        assertThat(location.fragment()).isEqualTo("rule-R%201%23%C3%A4");
    }
}
