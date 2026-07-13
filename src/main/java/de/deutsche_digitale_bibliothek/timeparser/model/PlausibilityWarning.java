package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;

/**
 * Hinweis aus dem Plausibilitätscheck inklusive verlinkbarer Fundstellen.
 */
public record PlausibilityWarning(
        String type,
        List<PlausibilityEntry> entries,
        List<PlausibilityField> fields
) {

    public record PlausibilityEntry(
            String id,
            long groupId,
            String anchor
    ) {
    }

    public record PlausibilityField(
            String label,
            String value,
            String visibleValue,
            String codePoints,
            int length
    ) {
    }
}
