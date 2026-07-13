package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Prüft fachliche Beziehungen innerhalb eines Regelgruppenformulars.
 */
@Component
@RequiredArgsConstructor
public class RuleGroupFormValidator {

    private final SqliteRuleRepository repository;

    public void validateForSave(RuleGroupForm form, Errors errors) {
        validateStructure(form, errors);
        validateRequiredFields(form, errors);
        validatePlausibility(form, errors);
        validateTokens(form, errors);
    }

    public void validateForPreview(RuleGroupForm form, Errors errors) {
        validatePlausibility(form, errors);
        validateTokens(form, errors);
    }

    private void validateStructure(RuleGroupForm form, Errors errors) {
        if (form.getRules().isEmpty()) {
            errors.rejectValue("rules", "rules.required", "Mindestens eine Regel ist erforderlich.");
            return;
        }
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            if (form.getRules().get(ruleIndex).getTests().isEmpty()) {
                errors.rejectValue(
                        ruleField(ruleIndex, "tests"),
                        "tests.required",
                        "Mindestens ein Test ist erforderlich."
                );
            }
        }
    }

    private void validateRequiredFields(RuleGroupForm form, Errors errors) {
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = form.getRules().get(ruleIndex);
            rejectBlank(errors, ruleField(ruleIndex, "inputMask"), rule.getInputMask(), "Die Eingabemaske ist erforderlich.");
            rejectBlank(errors, ruleField(ruleIndex, "inputPattern"), rule.getInputPattern(), "Das Eingabemuster ist erforderlich.");
            rejectBlank(errors, ruleField(ruleIndex, "outputMask"), rule.getOutputMask(), "Die Ausgabemaske ist erforderlich.");
            rejectBlank(errors, ruleField(ruleIndex, "outputPattern"), rule.getOutputPattern(), "Das Ausgabemuster ist erforderlich.");

            for (int testIndex = 0; testIndex < rule.getTests().size(); testIndex++) {
                RuleGroupForm.TestForm test = rule.getTests().get(testIndex);
                rejectBlank(errors, testField(ruleIndex, testIndex, "input"), test.getInput(), "Die Eingabe ist erforderlich.");
                rejectBlank(errors, testField(ruleIndex, testIndex, "tokenized"), test.getTokenized(), "Der tokenisierte Wert ist erforderlich.");
                rejectBlank(errors, testField(ruleIndex, testIndex, "output"), test.getOutput(), "Die Ausgabe ist erforderlich.");
            }
        }
    }

    private void validatePlausibility(RuleGroupForm form, Errors errors) {
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = form.getRules().get(ruleIndex);
            rejectLengthMismatch(
                    errors,
                    ruleField(ruleIndex, "inputPattern"),
                    rule.getInputMask(),
                    rule.getInputPattern(),
                    "Eingabemuster und Eingabemaske müssen gleich lang sein."
            );
            rejectLengthMismatch(
                    errors,
                    ruleField(ruleIndex, "outputPattern"),
                    rule.getOutputMask(),
                    rule.getOutputPattern(),
                    "Ausgabemuster und Ausgabemaske müssen gleich lang sein."
            );

            for (int testIndex = 0; testIndex < rule.getTests().size(); testIndex++) {
                RuleGroupForm.TestForm test = rule.getTests().get(testIndex);
                rejectGeneratedLengthMismatch(
                        errors,
                        testField(ruleIndex, testIndex, "tokenized"),
                        rule.getInputMask(),
                        test.getTokenized(),
                        "Der tokenisierte Testwert muss zur Eingabemaske gleich lang sein."
                );
                rejectGeneratedLengthMismatch(
                        errors,
                        testField(ruleIndex, testIndex, "output"),
                        rule.getOutputMask(),
                        test.getOutput(),
                        "Die erwartete Ausgabe muss zur Ausgabemaske gleich lang sein."
                );
                rejectInvalidTimespan(errors, testField(ruleIndex, testIndex, "timespan"), test.getTimespan());
            }
        }
    }

    private void validateTokens(RuleGroupForm form, Errors errors) {
        for (String tokenName : repository.unknownTokenNames(form)) {
            errors.rejectValue("rules", "token.unknown", "Platzhalter ~" + tokenName + " ist nicht definiert.");
        }
    }

    private void rejectBlank(Errors errors, String field, String value, String message) {
        if (isBlank(value)) {
            errors.rejectValue(field, "field.required", message);
        }
    }

    private void rejectLengthMismatch(Errors errors, String field, String mask, String pattern, String message) {
        if (!isBlank(mask) && !isBlank(pattern) && length(mask) != length(pattern)) {
            errors.rejectValue(field, "length.mismatch", message);
        }
    }

    private void rejectGeneratedLengthMismatch(Errors errors, String field, String mask, String value, String message) {
        if (!isBlank(mask)
                && !isBlank(value)
                && !containsToken(mask)
                && !containsToken(value)
                && length(mask) != length(value)) {
            errors.rejectValue(field, "length.mismatch", message);
        }
    }

    private void rejectInvalidTimespan(Errors errors, String field, String value) {
        if (isBlank(value)) {
            return;
        }
        String[] dates = value.split("/", -1);
        if (dates.length != 2) {
            errors.rejectValue(field, "timespan.format", "Der Zeitraum muss zwei ISO-Daten im Format Start/Ende enthalten.");
            return;
        }
        try {
            LocalDate start = LocalDate.parse(dates[0]);
            LocalDate end = LocalDate.parse(dates[1]);
            if (start.isAfter(end)) {
                errors.rejectValue(field, "timespan.order", "Der Zeitraum darf nicht vor seinem Start enden.");
            }
        } catch (DateTimeParseException exception) {
            errors.rejectValue(field, "timespan.format", "Der Zeitraum muss ISO-Daten im Format YYYY-MM-DD/YYYY-MM-DD verwenden.");
        }
    }

    private String ruleField(int ruleIndex, String field) {
        return "rules[" + ruleIndex + "]." + field;
    }

    private String testField(int ruleIndex, int testIndex, String field) {
        return ruleField(ruleIndex, "tests[" + testIndex + "]." + field);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsToken(String value) {
        return value != null && value.contains("~");
    }

    private int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
