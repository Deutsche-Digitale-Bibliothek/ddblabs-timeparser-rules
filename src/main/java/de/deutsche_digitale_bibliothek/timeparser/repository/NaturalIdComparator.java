package de.deutsche_digitale_bibliothek.timeparser.repository;

import java.util.Comparator;

/**
 * Sortiert technische IDs natürlich und vergleicht Zahlenblöcke ohne Integer-Überlauf.
 *
 * <p>Beispielsweise steht {@code R2} vor {@code R10}. Nur ASCII-Ziffern bilden
 * Zahlenblöcke; andere Zeichenfolgen werden mit dem case-sensitiven
 * {@link String#compareTo(String)} verglichen. Zahlen werden anhand ihrer
 * signifikanten Stellen verglichen und nicht in einen begrenzten Zahlentyp
 * umgewandelt. Bei gleichem Zahlenwert steht die kürzere Schreibweise vor der
 * mit zusätzlichen führenden Nullen, etwa {@code R2} vor {@code R02}.</p>
 *
 * <p>{@code null} steht vor jedem nicht-null Wert. Der Comparator verwendet
 * keine sprachabhängige Sortierung und ist dadurch unabhängig von der Locale.</p>
 */
enum NaturalIdComparator implements Comparator<String> {

    INSTANCE;

    /**
     * Vergleicht abwechselnde Ziffern- und Textblöcke von links nach rechts.
     * Treffen verschiedene Blockarten aufeinander, steht der Ziffernblock zuerst.
     *
     * @param left linke ID oder {@code null}
     * @param right rechte ID oder {@code null}
     * @return negatives Ergebnis, {@code 0} oder positives Ergebnis nach Sortierreihenfolge
     */
    @Override
    public int compare(String left, String right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }

        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            boolean leftNumeric = isDigit(left.charAt(leftIndex));
            boolean rightNumeric = isDigit(right.charAt(rightIndex));
            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }

            int leftEnd = runEnd(left, leftIndex, leftNumeric);
            int rightEnd = runEnd(right, rightIndex, rightNumeric);
            int comparison = leftNumeric
                    ? compareNumericRuns(left, leftIndex, leftEnd, right, rightIndex, rightEnd)
                    : left.substring(leftIndex, leftEnd).compareTo(right.substring(rightIndex, rightEnd));
            if (comparison != 0) {
                return comparison;
            }
            leftIndex = leftEnd;
            rightIndex = rightEnd;
        }

        return Integer.compare(left.length(), right.length());
    }

    private int runEnd(String value, int start, boolean numeric) {
        int end = start + 1;
        while (end < value.length() && isDigit(value.charAt(end)) == numeric) {
            end++;
        }
        return end;
    }

    /**
     * Vergleicht Länge und Ziffern der Werte ohne führende Nullen. Erst bei
     * gleichem Zahlenwert entscheidet die ursprüngliche Blocklänge, sodass
     * verschiedene Schreibweisen reproduzierbar geordnet bleiben.
     */
    private int compareNumericRuns(String left,
                                   int leftStart,
                                   int leftEnd,
                                   String right,
                                   int rightStart,
                                   int rightEnd) {
        int leftSignificant = skipLeadingZeros(left, leftStart, leftEnd);
        int rightSignificant = skipLeadingZeros(right, rightStart, rightEnd);
        int comparison = Integer.compare(leftEnd - leftSignificant, rightEnd - rightSignificant);
        if (comparison != 0) {
            return comparison;
        }
        while (leftSignificant < leftEnd) {
            comparison = Character.compare(left.charAt(leftSignificant), right.charAt(rightSignificant));
            if (comparison != 0) {
                return comparison;
            }
            leftSignificant++;
            rightSignificant++;
        }
        return Integer.compare(leftEnd - leftStart, rightEnd - rightStart);
    }

    private int skipLeadingZeros(String value, int start, int end) {
        while (start < end && value.charAt(start) == '0') {
            start++;
        }
        return start;
    }

    private boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }
}
