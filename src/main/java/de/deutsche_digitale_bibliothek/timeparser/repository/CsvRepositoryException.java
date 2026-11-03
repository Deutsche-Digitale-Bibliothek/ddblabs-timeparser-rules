package de.deutsche_digitale_bibliothek.timeparser.repository;

/**
 * Meldet fachlich beschreibbare Fehler bei CSV-Verarbeitung und Regelverwaltung.
 * Die ungeprüfte Ausnahme wird auch für nicht expandierbare Platzhalter und
 * verletzte Mindestanzahlen beim Löschen verwendet. Sie ersetzt keine
 * feldbezogenen Validierungsbefunde; Controller können sie beispielsweise als
 * sichtbaren Vorschauhinweis behandeln.
 */
public class CsvRepositoryException extends RuntimeException {

    /**
     * Erzeugt einen Fehler ohne zugrunde liegende technische Ausnahme.
     *
     * @param message Beschreibung des fehlgeschlagenen Vorgangs
     */
    public CsvRepositoryException(String message) {
        super(message);
    }

    /**
     * Ergänzt eine verständliche Beschreibung um die ursprüngliche Fehlerursache.
     *
     * @param message Beschreibung des fehlgeschlagenen Vorgangs
     * @param cause ursprünglicher technischer Fehler, etwa beim Dateizugriff
     */
    public CsvRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
