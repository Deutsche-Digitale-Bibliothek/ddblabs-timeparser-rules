package de.deutsche_digitale_bibliothek.timeparser.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RuleCharacterDiagnosticTest {

    @Test
    void preservesEveryBmpLiteralAndRejectsADifferentCharacterWithoutCrashingDiagnostics() {
        for (int codeUnit = Character.MIN_VALUE; codeUnit <= Character.MAX_VALUE; codeUnit++) {
            char literal = (char) codeUnit;
            if (literal == '#') {
                continue;
            }
            String mask = "1" + literal + "#";
            String matching = "1" + literal + "9";
            assertThat(RuleGroupValidator.validateTest(mask, null, matching, null, null))
                    .as("matching literal U+%04X", codeUnit).isEmpty();

            String differing = "1" + (literal == 'A' ? 'B' : 'A') + "9";
            assertThat(RuleGroupValidator.validateTest(mask, null, differing, null, null))
                    .as("differing literal U+%04X", codeUnit)
                    .anyMatch(issue -> issue.field().equals("tokenized") && issue.code().equals("mask.mismatch"));
        }
    }

    @ParameterizedTest
    @MethodSource("differentWhitespaceCharacters")
    void identifiesInvisibleLiteralDifferences(String expected, String actual,
                                               String expectedDescription, String actualDescription) {
        var mismatch = RuleGroupValidator.validateTest("####" + expected + "####", null,
                        "1900" + actual + "1950", null, null).stream()
                .filter(issue -> issue.code().equals("mask.mismatch"))
                .findFirst().orElseThrow();

        assertThat(mismatch.field()).isEqualTo("tokenized");
        assertThat(mismatch.message()).contains("Position 5", "Erwartet", expectedDescription,
                "vorhanden", actualDescription);
    }

    private static Stream<Arguments> differentWhitespaceCharacters() {
        return Stream.of(
                Arguments.of(" ", "\u00A0", "[␠] (U+0020)", "[⍽] (U+00A0)"),
                Arguments.of("\u00A0", "\u202F", "[⍽] (U+00A0)", "[⎵] (U+202F)"),
                Arguments.of("\t", " ", "[⇥] (U+0009)", "[␠] (U+0020)"),
                Arguments.of("\r", "\n", "[␍] (U+000D)", "[␊] (U+000A)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "\u0661"})
    void explainsDigitPlaceholdersAsAsciiDigitsRatherThanLiteralHashes(String actual) {
        var mismatch = RuleGroupValidator.validateTest("#", null, actual, null, null).stream()
                .filter(issue -> issue.code().equals("mask.mismatch"))
                .findFirst().orElseThrow();

        assertThat(mismatch.message()).contains("Position 1", "eine ASCII-Ziffer [0–9]", "[" + actual + "]");
        assertThat(mismatch.message()).contains(actual.equals("x") ? "U+0078" : "U+0661");
    }

    @Test
    void describesWholeSupplementaryCharactersAtTheExistingUtf16MismatchPosition() {
        var mismatch = RuleGroupValidator.validateTest("\uD83C\uDF38 ####", null,
                        "\uD83C\uDF3B 1950", null, null).stream()
                .filter(issue -> issue.code().equals("mask.mismatch"))
                .findFirst().orElseThrow();

        assertThat(mismatch.message()).contains("Position 2", "[🌸] (U+1F338)", "[🌻] (U+1F33B)")
                .doesNotContain("U+DF38", "U+DF3B");
        assertThat(RuleGroupValidator.validateTest("\uD83C\uDF38 ####", null,
                "\uD83C\uDF38 1950", null, null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"inputPattern", "outputPattern"})
    void describesTheSameLiteralDifferenceInMaskPatternPairs(String field) {
        var issues = field.equals("inputPattern")
                ? RulePatternValidator.validate("####–####", "JJJJ-ZZZZ", "####/####", "JJJJ/ZZZZ")
                : RulePatternValidator.validate("####/####", "JJJJ/ZZZZ", "####–####", "JJJJ-ZZZZ");
        var mismatch = issues.stream().filter(issue -> issue.code().equals("pattern.literal"))
                .findFirst().orElseThrow();

        assertThat(mismatch.field()).isEqualTo(field);
        assertThat(mismatch.message()).contains("Position 5", "[–] (U+2013)", "[-] (U+002D)");
    }

    @Test
    void alsoDescribesWholeSupplementaryLiteralsInPatterns() {
        var mismatch = RulePatternValidator.validate("\uD83C\uDF38 ####", "\uD83C\uDF3B JJJJ", "####", "JJJJ").stream()
                .filter(issue -> issue.code().equals("pattern.literal"))
                .findFirst().orElseThrow();

        assertThat(mismatch.message()).contains("Position 2", "[🌸] (U+1F338)", "[🌻] (U+1F33B)")
                .doesNotContain("U+DF38", "U+DF3B");
    }
}
