package de.deutsche_digitale_bibliothek.timeparser.validation;

import java.util.Locale;

/** Sichtbare, eindeutige Zeichenbeschreibung für positionsbezogene Feldfehler. */
final class CharacterDiagnostic {

    private CharacterDiagnostic() {
    }

    static String describe(String value, int index) {
        // Der Vergleich verwendet UTF-16-Positionen; die Beschreibung zeigt das ganze Zeichen.
        int start = index;
        if (index > 0 && Character.isLowSurrogate(value.charAt(index))
                && Character.isHighSurrogate(value.charAt(index - 1))) {
            start--;
        }
        int codePoint = value.codePointAt(start);
        String visible = switch (codePoint) {
            case ' ' -> "␠";
            case '\u00A0' -> "⍽";
            case '\u202F' -> "⎵";
            case '\t' -> "⇥";
            case '\r' -> "␍";
            case '\n' -> "␊";
            default -> Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT
                    ? "unsichtbares Zeichen" : new String(Character.toChars(codePoint));
        };
        return "[" + visible + "] (" + String.format(Locale.ROOT, "U+%04X", codePoint) + ")";
    }
}
