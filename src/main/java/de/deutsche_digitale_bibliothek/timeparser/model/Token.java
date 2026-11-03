package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;

/**
 * Platzhalterdefinition des Editors mit geordneten konkreten Ersetzungswerten.
 *
 * <p>Beispielsweise wird {@code ~approx} mit den Werten {@code um}, {@code ca.}
 * und {@code circa} zu mehreren Regel- und Testvarianten expandiert. Diese
 * Vorlagenplatzhalter sind von den Monats- und Wochentagsdefinitionen des
 * Timeparsers in {@code calendar_tokens.csv} zu unterscheiden.</p>
 *
 * <p>Leerzeichen gehören zum Wert und werden nicht getrimmt. Der Record führt
 * keine Kopie der angelieferten Werteliste und keine Normalisierung des Namens
 * durch; Namensauflösung und historische Aliase sind Aufgabe des Repositorys.</p>
 *
 * @param id technische Datenbank-ID der Definition
 * @param name referenzierbarer Name ohne führendes {@code ~}
 * @param description fachliche Beschreibung der Varianten
 * @param values konkrete Ersetzungen in ihrer gespeicherten Reihenfolge
 */
public record Token(
        long id,
        String name,
        String description,
        List<String> values
) {

    /**
     * Bereitet die Werte für die Anzeige auf, ohne die gespeicherten Texte zu verändern.
     *
     * @return geordnete, strukturell unveränderliche Liste aus Originaltext,
     *         sichtbarem Leerraum und Unicode-Codepoint-Länge
     */
    public List<ValueDisplay> visibleValues() {
        return values.stream()
                .map(value -> new ValueDisplay(
                        value,
                        visibleWhitespace(value),
                        value.codePointCount(0, value.length())
                ))
                .toList();
    }

    /** Ersetzt nur ausgewählte Leerraumzeichen durch Anzeigesymbole; der Originalwert bleibt erhalten. */
    private static String visibleWhitespace(String value) {
        return value
                .replace(" ", "␠")
                .replace("\u00A0", "⍽")
                .replace("\u202F", "⎵")
                .replace("\t", "⇥");
    }

    /**
     * Anzeige eines einzelnen Wertes mit ausdrücklich sichtbaren Leerzeichen.
     *
     * @param value unveränderter Ersetzungstext
     * @param visibleValue Text mit Symbolen für Leerzeichen, geschützte Leerzeichen und Tabulatoren
     * @param length Anzahl der Unicode-Codepoints des Originaltexts
     */
    public record ValueDisplay(
            String value,
            String visibleValue,
            int length
    ) {
    }
}
