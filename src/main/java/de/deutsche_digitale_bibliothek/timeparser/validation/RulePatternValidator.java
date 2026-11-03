package de.deutsche_digitale_bibliothek.timeparser.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prüft die strukturelle Übertragbarkeit einer bereits expandierten Regel.
 *
 * <p>Eine Maske beschreibt Zeichenkategorien, ihr Muster benennt die Variablen:
 * {@code ####} mit {@code JJJJ} definiert beispielsweise eine vierstellige
 * Variable {@code J}. Unter {@code #}-Stellen bildet jede zusammenhängende Folge
 * desselben Musterbuchstabens eine Variable. Maskenpaare {@code MM} und
 * {@code GG} benötigen jeweils zwei gleiche Musterbuchstaben; einzelne
 * {@code M}- oder {@code G}-Zeichen sind Literale. Sonstige Masken- und
 * Musterzeichen müssen exakt übereinstimmen.</p>
 *
 * <p>Die Identität einer Variable ist ihr erster Java-{@code char}, unabhängig
 * von ihrer Breite und Maskenkategorie. {@code JJJJ/JJ} definiert daher zweimal
 * dieselbe Eingabevariable und ist unzulässig. In der Ausgabe sind wiederholte
 * Verwendungen erlaubt, etwa {@code JJJ0/JJJ9}; jede Verwendung muss auf eine
 * vorhandene, mindestens ebenso breite Eingabevariable verweisen. Die
 * Buchstaben werden nicht als Datumsfelder interpretiert.</p>
 *
 * <p>Die Prüfung liest keine Platzhalterdefinitionen und führt weder
 * Tokenisierung noch Transformation oder Datumsinterpretation aus. Die
 * {@code ~name}-Expansion ist Aufgabe des aufrufenden Repositorys. Längen und
 * Positionen folgen wie im Timeparser Java-UTF-16-Indizes, nicht der Anzahl
 * sichtbarer Zeichen oder Unicode-Codepoints.</p>
 */
public final class RulePatternValidator {

    private RulePatternValidator() {
    }

    /**
     * Prüft beide Masken-/Musterpaare und anschließend ihre Variablenbeziehungen.
     *
     * <p>Ein {@code null}- oder nur aus Leerraum bestehender Teil eines Paares
     * lässt dessen Prüfung aus; Pflichtfelder werden separat geprüft. Sind
     * beide Paare strukturell gültig, werden fehlende Ausgabevariablen und zu
     * breite Verwendungen erkannt. Bei mehrfacher Ausgabe derselben Variable
     * ist dafür ihre längste Verwendung maßgeblich. Bei einem ungültigen Paar
     * entfällt der Beziehungsvergleich, um irreführende Folgefehler zu vermeiden.</p>
     *
     * @param inputMask konkrete Eingabemaske nach der Platzhalterexpansion
     * @param inputPattern konkretes Eingabemuster mit eindeutigen Variablen
     * @param outputMask konkrete Ausgabemaske nach der Platzhalterexpansion
     * @param outputPattern konkretes Ausgabemuster; darf Eingabevariablen wiederverwenden
     * @return unveränderbare Befundliste mit regelrelativen Feldern
     *         {@code inputPattern} bzw. {@code outputPattern}; leer bei fehlenden Befunden
     */
    public static List<Issue> validate(String inputMask, String inputPattern,
                                       String outputMask, String outputPattern) {
        List<Issue> issues = new ArrayList<>();
        Map<Character, Integer> inputVariables = parse(inputMask, inputPattern, "inputPattern", "Eingabe", false, issues);
        Map<Character, Integer> outputVariables = parse(outputMask, outputPattern, "outputPattern", "Ausgabe", true, issues);
        if (inputVariables != null && outputVariables != null) {
            for (Map.Entry<Character, Integer> variable : outputVariables.entrySet()) {
                Integer inputWidth = inputVariables.get(variable.getKey());
                if (inputWidth == null) {
                    issues.add(new Issue("outputPattern", "variable.undefined",
                            "Die Ausgabevariable " + variable.getKey() + " ist im Eingabemuster nicht definiert."));
                } else if (variable.getValue() > inputWidth) {
                    issues.add(new Issue("outputPattern", "variable.length",
                            "Die Ausgabevariable " + variable.getKey() + " darf nicht länger als die Eingabevariable sein."));
                }
            }
        }
        return List.copyOf(issues);
    }

    /**
     * Leitet aus der Herkunft der Ausgabevariablen eine Maske für erwartete Testausgaben ab.
     *
     * <p>Die Ausgabe enthält Werte der Eingabevariablen, deren Kategorie nicht
     * allein aus der Ausgabemaske hervorgeht. Zahlen und Monatsvariablen werden
     * als {@code #}-Stellen geprüft; Wochentagsvariablen bleiben als
     * {@code G}-Zeichen tokenisiert. Literale der Ausgabe bleiben erhalten.
     * Beispielsweise ergibt die Monatsvariable {@code X} aus
     * {@code MM ####}/{@code XX JJJJ} für
     * {@code ####-MM}/{@code JJJJ-XX} die Prüfmaske {@code ####-##}.</p>
     *
     * <p>Die abgeleitete Maske prüft ausschließlich Zeichenarten und Länge. Sie
     * belegt weder, dass eine Monatszahl gültig ist, noch dass die konkreten
     * Ausgabewerte aus einer Testeingabe berechnet wurden. Die Methode führt
     * {@link #validate(String, String, String, String)} zuerst aus und gibt bei
     * Fehlern oder einem fehlenden/blanken Argument {@code null} zurück. Der
     * Aufrufer muss die ursprünglichen Musterfehler weiterhin separat melden.</p>
     *
     * @param inputMask expandierte Eingabemaske als Quelle der Variablenkategorien
     * @param inputPattern zugehörige Variablendefinitionen
     * @param outputMask expandierte Ausgabemaske einschließlich ihrer Literale
     * @param outputPattern zugehörige Verwendung der Eingabevariablen
     * @return effektive Ausgabeprüfmaske oder {@code null}, wenn keine sichere Ableitung möglich ist
     */
    public static String outputTestMask(String inputMask, String inputPattern,
                                         String outputMask, String outputPattern) {
        if (!validate(inputMask, inputPattern, outputMask, outputPattern).isEmpty()
                || inputMask == null || inputPattern == null || outputMask == null || outputPattern == null
                || inputMask.isBlank() || inputPattern.isBlank() || outputMask.isBlank() || outputPattern.isBlank()) {
            return null;
        }
        Map<Character, Character> valueMasks = new LinkedHashMap<>();
        for (int i = 0; i < inputMask.length();) {
            int width = variableWidth(inputMask, inputPattern, i);
            if (width > 0) {
                valueMasks.put(inputPattern.charAt(i), inputMask.charAt(i) == 'G' ? 'G' : '#');
            }
            i += Math.max(1, width);
        }
        StringBuilder mask = new StringBuilder(outputMask);
        for (int i = 0; i < outputMask.length();) {
            int width = variableWidth(outputMask, outputPattern, i);
            if (width > 0) {
                char valueMask = valueMasks.get(outputPattern.charAt(i));
                for (int offset = 0; offset < width; offset++) {
                    mask.setCharAt(i + offset, valueMask);
                }
            }
            i += Math.max(1, width);
        }
        return mask.toString();
    }

    /**
     * Bestimmt die Breite eines Variablenvorkommens in einem zuvor geprüften Paar.
     * Die Vorprüfung garantiert insbesondere gleiche Längen und gültige
     * Kalenderpaare; andernfalls wären die direkten Positionszugriffe unsicher.
     */
    private static int variableWidth(String mask, String pattern, int index) {
        if (mask.charAt(index) == '#') {
            int width = 1;
            while (index + width < mask.length() && mask.charAt(index + width) == '#'
                    && pattern.charAt(index + width) == pattern.charAt(index)) {
                width++;
            }
            return width;
        }
        return (mask.charAt(index) == 'M' || mask.charAt(index) == 'G')
                && index + 1 < mask.length() && mask.charAt(index + 1) == mask.charAt(index) ? 2 : 0;
    }

    /**
     * Sammelt je Variablenbuchstabe die größte vorkommende Breite. Eine
     * fehlerhafte oder nicht vorhandene Spezifikation liefert {@code null};
     * eine gültige reine Literalspezifikation liefert dagegen eine leere Map.
     * Diese Unterscheidung verhindert Beziehungsprüfungen auf ungültiger Basis.
     */
    private static Map<Character, Integer> parse(String mask, String pattern, String field,
                                                  String direction, boolean allowReuse, List<Issue> issues) {
        if (mask == null || pattern == null || mask.isBlank() || pattern.isBlank()) {
            return null;
        }
        // PatternParser arbeitet mit Java-char-Indizes, nicht mit Unicode-Codepoints.
        if (mask.length() != pattern.length()) {
            issues.add(new Issue(field, "length.mismatch",
                    direction + "muster und " + direction + "maske müssen gleich lang sein."));
            return null;
        }

        Map<Character, Integer> variables = new LinkedHashMap<>();
        boolean valid = true;
        for (int index = 0; index < mask.length();) {
            char maskCharacter = mask.charAt(index);
            char patternCharacter = pattern.charAt(index);
            int width = 0;
            if (maskCharacter == '#') {
                width = 1;
                while (index + width < mask.length()
                        && mask.charAt(index + width) == '#'
                        && pattern.charAt(index + width) == patternCharacter) {
                    width++;
                }
            } else if ((maskCharacter == 'M' || maskCharacter == 'G')
                    && index + 1 < mask.length() && mask.charAt(index + 1) == maskCharacter) {
                if (pattern.charAt(index + 1) != patternCharacter) {
                    issues.add(new Issue(field, "pattern.variable",
                            "Die Maske " + maskCharacter + maskCharacter
                                    + " benötigt zwei gleiche Variablenbuchstaben an Position " + (index + 1) + "."));
                    return null;
                }
                width = 2;
            }

            if (width > 0) {
                if (!Character.isLetter(patternCharacter)) {
                    issues.add(new Issue(field, "pattern.variable",
                            "Variablen müssen Buchstaben verwenden (Position " + (index + 1) + ")."));
                    valid = false;
                } else if (!allowReuse && variables.containsKey(patternCharacter)) {
                    issues.add(new Issue(field, "variable.duplicate",
                            "Die Variable " + patternCharacter + " kommt im " + direction
                                    + "muster mehrfach vor. Unterschiedliche Variablennamen wie JJJJ/ZZZZ sind erforderlich."));
                    valid = false;
                } else {
                    // Jede Verwendung muss in die Eingabevariable passen, auch die längste.
                    variables.merge(patternCharacter, width, Math::max);
                }
                index += width;
            } else {
                if (maskCharacter != patternCharacter) {
                    issues.add(new Issue(field, "pattern.literal",
                            direction + "maske und " + direction + "muster müssen an Position "
                                    + (index + 1) + " dasselbe Literal enthalten."));
                    valid = false;
                }
                index++;
            }
        }
        return valid ? variables : null;
    }

    /**
     * Fachlicher Feldbefund für Formularfehler oder separat angezeigte Hinweise.
     *
     * <p>Die Schwere ist nicht Bestandteil dieses Records, sondern ergibt sich
     * aus dem liefernden Prüfpfad: Das Repository stellt Speicherfehler und
     * unverbindliche Hinweise über getrennte Methoden bereit. Der Aufrufer kann
     * einen relativen Feldnamen um einen Formularpfad ergänzen, bevor der Befund
     * an Spring oder die Plausibilitätsansicht übergeben wird.</p>
     *
     * @param field betroffener Feldname oder Formularpfad, z. B. {@code inputPattern}
     * @param code maschinenlesbarer Prüfcode, z. B. {@code variable.duplicate}
     * @param message konkrete, für die Oberfläche bestimmte Erläuterung
     * @param relatedRules andere betroffene Regeln, etwa die bereits vorhandene Dublette;
     *                     die Liste wird unveränderbar kopiert
     */
    public record Issue(String field, String code, String message, List<RelatedRule> relatedRules) {

        /**
         * Erstellt einen Feldbefund ohne zusätzliche Regelfundstellen.
         *
         * @param field betroffener Feldname oder Formularpfad
         * @param code maschinenlesbarer Prüfcode
         * @param message Erläuterung für die Oberfläche
         */
        public Issue(String field, String code, String message) {
            this(field, code, message, List.of());
        }

        /** Übernimmt die Fundstellen unabhängig von einer veränderlichen Aufruferliste. */
        public Issue {
            relatedRules = List.copyOf(relatedRules);
        }
    }

    /**
     * Verweist von einem Befund auf eine konkrete Regel und ihre bearbeitbare Vorlage.
     * Die generierte ID kann ein Variantensuffix tragen; Navigation verwendet dagegen
     * die Vorlagen-ID, damit der Link den vorhandenen HTML-Anker der Detailseite trifft.
     *
     * @param id angezeigte ID der konkreten generierten Regel
     * @param groupId gespeicherte Regelgruppe; {@code 0} bezeichnet einen neuen Entwurf
     * @param templateId ID der zugrunde liegenden bearbeitbaren Regelvorlage
     */
    public record RelatedRule(String id, long groupId, String templateId) {
    }
}
