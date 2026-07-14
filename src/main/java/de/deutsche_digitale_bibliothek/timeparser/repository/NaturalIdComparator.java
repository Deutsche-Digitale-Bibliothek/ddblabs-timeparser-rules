package de.deutsche_digitale_bibliothek.timeparser.repository;

import java.util.Comparator;

/**
 * Sortiert technische IDs natürlich und vergleicht Zahlenblöcke ohne Integer-Überlauf.
 */
enum NaturalIdComparator implements Comparator<String> {

    INSTANCE;

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
