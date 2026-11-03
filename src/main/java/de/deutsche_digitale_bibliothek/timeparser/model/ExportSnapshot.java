package de.deutsche_digitale_bibliothek.timeparser.model;

import de.deutsche_digitale_bibliothek.timeparser.export.ExportDataset;
import java.util.List;

/**
 * Zusammengehöriger Datenbestand und Prüfbericht für einen konkreten Download.
 *
 * <p>Das Repository erzeugt den Snapshot in seiner lesenden Transaktion. Der
 * Controller verwendet genau diesen Bestand sowohl für die Download-Dateien als
 * auch für die zugehörigen Hinweise. Der Record selbst eröffnet keine Transaktion,
 * liest keine Daten nach und prüft die übergebenen Inhalte nicht erneut.</p>
 *
 * <p>Ein später angeforderter Download erzeugt einen neuen Snapshot und kann daher
 * einen anderen Datenbankstand enthalten. Ein gemeinsames ZIP vermeidet Änderungen
 * zwischen getrennten Regel- und Testdownloads. Der Snapshot ist keine Bestätigung
 * bestandener Parser-Tests; seine Meldungen stammen aus statischen Prüfungen.</p>
 *
 * @param dataset aufbereitete CSV-Zeilen für Regeln und Tests
 * @param warnings vollständige Hinweise dieses Exports, einschließlich
 *                 Plausibilitätsbefunden und ausdrücklich gemeldeter Auslassungen
 * @param report bereits formulierter Text für {@code pruefhinweise.txt}
 */
public record ExportSnapshot(ExportDataset dataset, List<PlausibilityWarning> warnings, String report) {
    /**
     * Kopiert die Hinweisliste; der Datensatz und seine Beans werden nicht tief kopiert.
     *
     * @param dataset bereits aufbereiteter Bestand für den Download
     * @param warnings Befunde des zugehörigen Prüfzeitpunkts
     * @param report formulierter Prüfbericht
     * @throws NullPointerException wenn die Hinweisliste oder ein Eintrag darin {@code null} ist
     */
    public ExportSnapshot {
        warnings = List.copyOf(warnings);
    }
}
