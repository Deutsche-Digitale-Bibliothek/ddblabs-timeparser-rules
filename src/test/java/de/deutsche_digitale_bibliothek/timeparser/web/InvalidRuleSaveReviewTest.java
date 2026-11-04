package de.deutsche_digitale_bibliothek.timeparser.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.Collections;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class InvalidRuleSaveReviewTest {

    private final InvalidRuleSaveReview review = new InvalidRuleSaveReview();

    @Test
    void acceptsTheSameDraftAndErrorsIndependentlyOfTheirOrder() {
        var form = form();
        var first = errors(form);
        first.rejectValue("name", "name.required", "Der Name ist erforderlich.");
        var reversed = new BeanPropertyBindingResult(form, "groupForm");
        reversed.rejectValue("name", "name.required", "Der Name ist erforderlich.");
        reversed.rejectValue("rules[0].inputPattern", "variable.duplicate", "Doppelte Variable.");

        String token = review.issue(form, first);

        assertThat(token).matches("[0-9a-f]{64}");
        assertThat(review.matches(token, form, reversed)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("draftChanges")
    void rejectsAChangedDraftEvenWhenTheSameErrorRemains(Consumer<RuleGroupForm> change) {
        var form = form();
        String token = review.issue(form, errors(form));

        change.accept(form);

        assertThat(review.matches(token, form, errors(form))).isFalse();
    }

    static Stream<Consumer<RuleGroupForm>> draftChanges() {
        return Stream.of(
                form -> form.setId(8L),
                form -> form.setId(null),
                form -> form.setName("Anderer Name"),
                form -> form.setDescription("Andere Beschreibung"),
                form -> form.getRules().getFirst().setId("RChanged"),
                form -> form.getRules().getFirst().setInputMask("##-####"),
                form -> form.getRules().getFirst().setInputPattern("MM-JJJJ"),
                form -> form.getRules().getFirst().setOutputMask("####-##"),
                form -> form.getRules().getFirst().setOutputPattern("JJJJ-MM"),
                form -> form.getRules().getFirst().getTests().getFirst().setId("TChanged"),
                form -> form.getRules().getFirst().getTests().getFirst().setInput("04.2010"),
                form -> form.getRules().getFirst().getTests().getFirst().setTokenized("04.2010"),
                form -> form.getRules().getFirst().getTests().getFirst().setOutput("2010-04"),
                form -> form.getRules().getFirst().getTests().getFirst().setTimespan("2010-01-01/2010-12-31"),
                form -> Collections.swap(form.getRules(), 0, 1),
                form -> Collections.swap(form.getRules().getFirst().getTests(), 0, 1),
                form -> form.getRules().removeLast(),
                form -> form.getRules().getFirst().getTests().removeLast()
        );
    }

    @Test
    void rejectsNewOrChangedErrors() {
        var form = form();
        String token = review.issue(form, errors(form));
        var additional = errors(form);
        additional.rejectValue("name", "name.required", "Der Name ist erforderlich.");
        var changed = new BeanPropertyBindingResult(form, "groupForm");
        changed.rejectValue("rules[0].inputPattern", "variable.duplicate", "Andere Erläuterung.");
        var moved = new BeanPropertyBindingResult(form, "groupForm");
        moved.rejectValue("rules[1].inputPattern", "variable.duplicate", "Doppelte Variable.");
        var changedCode = new BeanPropertyBindingResult(form, "groupForm");
        changedCode.rejectValue("rules[0].inputPattern", "pattern.literal", "Doppelte Variable.");

        assertThat(review.matches(token, form, additional)).isFalse();
        assertThat(review.matches(token, form, changed)).isFalse();
        assertThat(review.matches(token, form, moved)).isFalse();
        assertThat(review.matches(token, form, changedCode)).isFalse();
        assertThat(review.matches(token, form, new BeanPropertyBindingResult(form, "groupForm"))).isFalse();
    }

    @Test
    void ignoresTheTransientConsentAndSubmittedReviewFields() {
        var form = form();
        String token = review.issue(form, errors(form));

        form.setSaveInvalid(true);
        form.setValidationReview(token);

        assertThat(review.matches(token, form, errors(form))).isTrue();
    }

    @Test
    void keepsAdjacentTextFieldsDistinct() {
        var form = form();
        form.setName("ab");
        form.setDescription("c");
        String token = review.issue(form, errors(form));

        form.setName("a");
        form.setDescription("bc");

        assertThat(review.matches(token, form, errors(form))).isFalse();
    }

    @Test
    void treatsNullAndEmptyStringsAsTheSameHtmlValue() {
        var form = form();
        form.setDescription(null);
        form.getRules().getFirst().getTests().getFirst().setTimespan(null);
        String token = review.issue(form, errors(form));

        form.setDescription("");
        form.getRules().getFirst().getTests().getFirst().setTimespan("");

        assertThat(review.matches(token, form, errors(form))).isTrue();
    }

    @Test
    void rejectsMissingMalformedForgedAndOtherInstanceTokens() {
        var form = form();
        var errors = errors(form);

        assertThat(review.matches(null, form, errors)).isFalse();
        assertThat(review.matches("", form, errors)).isFalse();
        assertThat(review.matches("not-a-review", form, errors)).isFalse();
        assertThat(review.matches("g".repeat(64), form, errors)).isFalse();
        assertThat(review.matches("0".repeat(64), form, errors)).isFalse();
        assertThat(new InvalidRuleSaveReview().matches(review.issue(form, errors), form, errors)).isFalse();
    }

    private BeanPropertyBindingResult errors(RuleGroupForm form) {
        var errors = new BeanPropertyBindingResult(form, "groupForm");
        errors.rejectValue("rules[0].inputPattern", "variable.duplicate", "Doppelte Variable.");
        return errors;
    }

    private RuleGroupForm form() {
        var form = new RuleGroupForm();
        form.setId(7L);
        form.setName("Zu prüfende Gruppe");
        form.setDescription("Beschreibung");
        for (int index = 0; index < 2; index++) {
            var rule = new RuleGroupForm.RuleVariantForm();
            rule.setId("R" + index);
            rule.setInputMask("##.####");
            rule.setInputPattern("MM.JJJJ");
            rule.setOutputMask("####");
            rule.setOutputPattern("JJJJ");
            for (int testIndex = 0; testIndex < 2; testIndex++) {
                var test = new RuleGroupForm.TestForm();
                test.setId("T" + index + "_" + testIndex);
                test.setInput("03.2010");
                test.setTokenized("03.2010");
                test.setOutput("2010");
                rule.getTests().add(test);
            }
            form.getRules().add(rule);
        }
        return form;
    }
}
