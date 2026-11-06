package de.deutsche_digitale_bibliothek.timeparser.validation;

import java.util.Optional;

/**
 * Liefert Beispiele möglicher Überschneidungen zweier konkreter Eingabemasken.
 *
 * <p>Geprüft werden nur unterschiedliche Masken gleicher UTF-16-Länge und mit
 * derselben Anzahl von {@code #}-Stellen. Unterschiedliche Anzahlen werden hier
 * ausgelassen, weil die Spezifität bei der späteren Regelauswahl eine Rolle
 * spielt. Identische Masken werden bereits von der gesonderten Dubletten- und
 * Transformationskonfliktprüfung erfasst.</p>
 *
 * <p>Das Ergebnis dient ausschließlich einem unverbindlichen Hinweis. Die
 * Berechnung kennt weder die gesamte Zeichenbehandlung des Timeparsers noch
 * dessen tatsächliche Auswahl- und Priorisierungslogik. Ein Beispiel beweist
 * daher keinen Laufzeitfehler; ein leeres Ergebnis beweist umgekehrt nicht,
 * dass der Timeparser die Regeln niemals gleichzeitig auswählen könnte.</p>
 */
public final class RuleMaskOverlap {

    private RuleMaskOverlap() {
    }

    /**
     * Sucht positionsweise eine gemeinsame Belegung innerhalb des statischen Maskenmodells.
     *
     * <p>{@code #} steht ausschließlich für eine ASCII-Ziffer, alle anderen
     * Zeichen gelten als Literale und müssen exakt übereinstimmen. Dies gilt
     * auch für normale, geschützte und schmale geschützte Leerzeichen.
     * Monats- und Wochentagsmarker sind in diesem Modell ebenfalls Literale;
     * eine Tokenisierung wird nicht ausgeführt.</p>
     *
     * <p>Das Beispiel wird deterministisch aufgebaut: Zwei {@code #}-Stellen
     * erhalten {@code 0}; bei einer Ziffer gegenüber {@code #} wird die Ziffer
     * übernommen; übereinstimmende Literale bleiben erhalten. So ergibt
     * {@code 1###} zusammen mit {@code ##0#} die Beispieleingabe {@code 1000}.</p>
     *
     * @param left erste expandierte Eingabemaske; darf {@code null} sein
     * @param right zweite expandierte Eingabemaske; darf {@code null} sein
     * @return gemeinsame Beispieleingabe oder ein leeres Optional bei fehlenden
     *         Argumenten, identischen Masken, verschiedenen Längen bzw. Anzahlen
     *         von {@code #} oder unvereinbaren Zeichenpositionen
     */
    public static Optional<String> witness(String left, String right) {
        if (left == null || right == null || left.equals(right)
                || left.length() != right.length() || hashCount(left) != hashCount(right)) {
            return Optional.empty();
        }

        StringBuilder example = new StringBuilder(left.length());
        for (int index = 0; index < left.length(); index++) {
            char leftCharacter = left.charAt(index);
            char rightCharacter = right.charAt(index);
            if (leftCharacter == '#' && rightCharacter == '#') {
                example.append('0');
            } else if (leftCharacter == '#') {
                if (!isAsciiDigit(rightCharacter)) {
                    return Optional.empty();
                }
                example.append(rightCharacter);
            } else if (rightCharacter == '#') {
                if (!isAsciiDigit(leftCharacter)) {
                    return Optional.empty();
                }
                example.append(leftCharacter);
            } else if (leftCharacter == rightCharacter) {
                example.append(leftCharacter);
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(example.toString());
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static int hashCount(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '#') {
                count++;
            }
        }
        return count;
    }
}
