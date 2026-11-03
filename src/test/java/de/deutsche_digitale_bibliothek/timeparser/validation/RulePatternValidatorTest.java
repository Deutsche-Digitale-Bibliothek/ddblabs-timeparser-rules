package de.deutsche_digitale_bibliothek.timeparser.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RulePatternValidatorTest {

    @Test
    void acceptsDistinctVariablesReorderingAndShorterOutput() {
        assertThat(RulePatternValidator.validate("####/####", "JJJJ/ZZZZ", "##-####", "ZZ-JJJJ")).isEmpty();
    }

    @Test
    void rejectsDuplicateDefinitionsInTheInputPattern() {
        assertThat(RulePatternValidator.validate("####/####", "JJJJ/JJJJ", "####/####", "JJJJ/JJJJ"))
                .extracting(RulePatternValidator.Issue::field, RulePatternValidator.Issue::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("inputPattern", "variable.duplicate"));
    }

    @Test
    void acceptsReusingInputVariablesInTheOutputPattern() {
        assertThat(RulePatternValidator.validate("###?", "JJJ?", "###0/###9", "JJJ0/JJJ9")).isEmpty();
        assertThat(RulePatternValidator.validate("####", "JJJJ", "##/####", "JJ/JJJJ")).isEmpty();
        assertThat(RulePatternValidator.outputTestMask("MM ####", "MM JJJJ", "####-##/####-##", "JJJJ-MM/JJJJ-MM"))
                .isEqualTo("####-##/####-##");
    }

    @Test
    void identifiesVariablesByLetterEvenWithDifferentWidthsOrNoSeparator() {
        assertThat(RulePatternValidator.validate("####/##", "JJJJ/JJ", "####", "JJJJ"))
                .extracting(RulePatternValidator.Issue::code).contains("variable.duplicate");
        assertThat(RulePatternValidator.validate("#######", "JJJZZJJ", "###", "JJJ"))
                .extracting(RulePatternValidator.Issue::code).contains("variable.duplicate");
    }

    @Test
    void doesNotTreatLiteralLettersOrNumbersAsVariables() {
        assertThat(RulePatternValidator.validate("Jahr #### J", "Jahr JJJJ J", "2000/####", "2000/JJJJ"))
                .isEmpty();
    }

    @Test
    void acceptsCalendarReplacementVariablesAndNumericMonthOutput() {
        assertThat(RulePatternValidator.validate("GG MM ####", "GG MM JJJJ", "####-##", "JJJJ-MM"))
                .isEmpty();
        assertThat(RulePatternValidator.validate("MM/MM", "MM/NN", "##/##", "MM/NN")).isEmpty();
    }

    @Test
    void rejectsRepeatedCalendarVariablesAndCollisionsWithNumericVariables() {
        assertThat(RulePatternValidator.validate("MM/MM", "MM/MM", "##", "MM"))
                .extracting(RulePatternValidator.Issue::code).contains("variable.duplicate");
        assertThat(RulePatternValidator.validate("GG ##", "GG GG", "##", "GG"))
                .extracting(RulePatternValidator.Issue::code).contains("variable.duplicate");
    }

    @Test
    void treatsSingleCalendarMaskCharactersAsLiterals() {
        assertThat(RulePatternValidator.validate("M G ####", "M G JJJJ", "####", "JJJJ")).isEmpty();
    }

    @Test
    void rejectsMalformedCalendarVariablePairs() {
        assertThat(RulePatternValidator.validate("MM ####", "MN JJJJ", "####", "JJJJ"))
                .extracting(RulePatternValidator.Issue::code).containsExactly("pattern.variable");
    }

    @Test
    void rejectsLengthAndLiteralMismatches() {
        assertThat(RulePatternValidator.validate("####", "JJJ", "####", "JJJJ"))
                .extracting(RulePatternValidator.Issue::code).contains("length.mismatch");
        assertThat(RulePatternValidator.validate("####/##", "JJJJ-MM", "####", "JJJJ"))
                .extracting(RulePatternValidator.Issue::code).contains("pattern.literal");
    }

    @Test
    void rejectsNonLetterVariables() {
        assertThat(RulePatternValidator.validate("####", "1111", "####", "JJJJ"))
                .extracting(RulePatternValidator.Issue::code).containsExactly("pattern.variable");
    }

    @Test
    void rejectsUndefinedAndTooWideOutputVariables() {
        assertThat(RulePatternValidator.validate("####", "JJJJ", "####", "ZZZZ"))
                .extracting(RulePatternValidator.Issue::code).containsExactly("variable.undefined");
        assertThat(RulePatternValidator.validate("###", "JJJ", "####", "JJJJ"))
                .extracting(RulePatternValidator.Issue::code).containsExactly("variable.length");
        assertThat(RulePatternValidator.validate("####", "JJJJ", "##/#####", "JJ/JJJJJ"))
                .extracting(RulePatternValidator.Issue::code).containsExactly("variable.length");
        assertThat(RulePatternValidator.validate("####", "JJJJ", "#####/##", "JJJJJ/JJ"))
                .extracting(RulePatternValidator.Issue::code).containsExactly("variable.length");
    }

    @Test
    void leavesBlankFieldChecksToTheCaller() {
        assertThat(RulePatternValidator.validate(null, "", " ", null)).isEmpty();
    }
}
