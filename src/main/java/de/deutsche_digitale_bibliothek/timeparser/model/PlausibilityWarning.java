package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;

/**
 * Darstellbarer Befund einer statischen Prüfung mit optionalen Fundstellen und Feldwerten.
 *
 * <p>Die Plausibilitätsansicht bündelt hier sowohl Fehler, die beim regulären Speichern
 * zurückgewiesen würden, als auch unverbindliche Hinweise, etwa fehlende Tests
 * je expandierter Variante. Der Typ bezeichnet einen Bereich und ist keine
 * Schweregradangabe. Ein Befund sperrt den Export nicht.</p>
 *
 * <p>Einige Exportbefunde besitzen weder verlinkbare Fundstellen noch Feldwerte.
 * Die Listen werden von diesem Record nicht defensiv kopiert.</p>
 *
 * @param type Anzeigebereich, etwa {@code Regel}, {@code Test}, {@code Regelgruppe} oder {@code Export}
 * @param entries betroffene IDs und navigierbare Positionen; darf leer sein
 * @param fields für den Vergleich oder die Diagnose dargestellte Feldwerte; darf leer sein
 * @param message lesbare Erklärung des Befunds
 */
public record PlausibilityWarning(
        String type,
        List<PlausibilityEntry> entries,
        List<PlausibilityField> fields,
        String message
) {

    /**
     * Erstellt einen Dublettenbefund mit einer aus dem Bereich abgeleiteten Standardmeldung.
     *
     * @param type Bereich; nur {@code Test} wählt die Standardmeldung für Testwerte
     * @param entries Fundstellen der Dubletten
     * @param fields gemeinsame Werte der betroffenen Zeilen
     */
    public PlausibilityWarning(String type, List<PlausibilityEntry> entries, List<PlausibilityField> fields) {
        this(type, entries, fields, "Doppelte " + ("Test".equals(type) ? "Testwerte" : "Regelwerte"));
    }

    /**
     * Fundstelle einer Regel, eines Tests oder einer Gruppe.
     *
     * @param id im Befund angezeigte technische ID oder Gruppenbezeichnung
     * @param groupId Zielgruppe der Detailseite; {@code 0} bedeutet eine nicht verlinkte Fundstelle
     * @param anchor Fragment der Detailseite ohne führendes {@code #}, etwa {@code rule-R1};
     *               leer für einen Link auf die Gruppe selbst
     */
    public record PlausibilityEntry(
            String id,
            long groupId,
            String anchor
    ) {
    }

    /**
     * Diagnosewert mit verlustfreier Originaldarstellung und sichtbar gemachten Leerzeichen.
     *
     * @param label lesbare Feldbezeichnung
     * @param value ursprünglicher Feldwert; das Repository ersetzt {@code null} durch einen leeren Text
     * @param visibleValue Anzeigevariante mit Symbolen für relevante Leerraumzeichen
     * @param length Länge des Originalwerts in Unicode-Codepoints; keine UTF-16-Maskenposition
     */
    public record PlausibilityField(
            String label,
            String value,
            String visibleValue,
            int length
    ) {
    }
}
