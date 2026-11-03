package de.deutsche_digitale_bibliothek.timeparser.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMaskOverlapTest {

    @Test
    void suppliesAConcreteExampleForOverlappingEquallySpecificMasks() {
        assertThat(RuleMaskOverlap.witness("1###", "##0#")).contains("1000");
        assertThat(RuleMaskOverlap.witness("##0#", "1###")).contains("1000");
    }

    @Test
    void respectsDigitsAndLiteralSeparatorsInBothMasks() {
        assertThat(RuleMaskOverlap.witness("1###/2#", "##0#/2#")).contains("1000/20");
        assertThat(RuleMaskOverlap.witness("1###/2#", "##0#-2#")).isEmpty();
    }

    @Test
    void rejectsConflictingLiteralCharacters() {
        assertThat(RuleMaskOverlap.witness("1###", "2###")).isEmpty();
        assertThat(RuleMaskOverlap.witness("ab#", "ac#")).isEmpty();
    }

    @Test
    void ignoresIdenticalMasksHandledByTheDuplicateCheck() {
        assertThat(RuleMaskOverlap.witness("####", "####")).isEmpty();
        assertThat(RuleMaskOverlap.witness("", "")).isEmpty();
    }

    @Test
    void ignoresDifferentSpecificityEvenWhenThereIsAnIntersection() {
        assertThat(RuleMaskOverlap.witness("####", "1###")).isEmpty();
        assertThat(RuleMaskOverlap.witness("1900", "####")).isEmpty();
    }

    @Test
    void requiresEqualUtf16Lengths() {
        assertThat(RuleMaskOverlap.witness("1###", "##0# ")).isEmpty();
        assertThat(RuleMaskOverlap.witness("🌸 1###", "🌸 ##0#")).contains("🌸 1000");
        assertThat(RuleMaskOverlap.witness("🌸 1###", "x ##0#")).isEmpty();
    }

    @Test
    void acceptsCompatibleUnicodeSpaceCharacters() {
        assertThat(RuleMaskOverlap.witness("1### ##", "##0#\u00a0##")).contains("1000 00");
        assertThat(RuleMaskOverlap.witness("1###\u202f##", "##0#\u00a0##")).contains("1000\u202f00");
        assertThat(RuleMaskOverlap.witness("1### ##", "##0#\t##")).isEmpty();
    }

    @Test
    void keepsMonthAndWeekdayMarkersLiteral() {
        assertThat(RuleMaskOverlap.witness("MM 1###", "MM ##0#")).contains("MM 1000");
        assertThat(RuleMaskOverlap.witness("GG 1###", "GG ##0#")).contains("GG 1000");
        assertThat(RuleMaskOverlap.witness("MM 1###", "GG ##0#")).isEmpty();
        assertThat(RuleMaskOverlap.witness("MM##", "##MM")).isEmpty();
    }

    @Test
    void treatsOnlyAsciiDigitsAsNumericWitnesses() {
        assertThat(RuleMaskOverlap.witness("#1", "1#")).contains("11");
        assertThat(RuleMaskOverlap.witness("#١", "١#")).isEmpty();
    }

    @Test
    void handlesMissingMasks() {
        assertThat(RuleMaskOverlap.witness(null, "####")).isEmpty();
        assertThat(RuleMaskOverlap.witness("####", null)).isEmpty();
    }
}
