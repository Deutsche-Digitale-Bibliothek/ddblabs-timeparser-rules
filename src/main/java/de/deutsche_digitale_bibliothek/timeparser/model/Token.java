package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;

/**
 * Platzhalter mit geordneten konkreten Werten, zum Beispiel {@code approx -> um, ca., circa}.
 */
public record Token(
        long id,
        String name,
        String description,
        List<String> values
) {

    public List<ValueDisplay> visibleValues() {
        return values.stream()
                .map(value -> new ValueDisplay(
                        value,
                        visibleWhitespace(value),
                        value.codePointCount(0, value.length())
                ))
                .toList();
    }

    private static String visibleWhitespace(String value) {
        return value
                .replace(" ", "␠")
                .replace("\u00A0", "⍽")
                .replace("\u202F", "⎵")
                .replace("\t", "⇥");
    }

    public record ValueDisplay(
            String value,
            String visibleValue,
            int length
    ) {
    }
}
