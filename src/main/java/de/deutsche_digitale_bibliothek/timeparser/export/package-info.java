/**
 * Bereitet konkrete Regel- und Testzeilen gemeinsam auf und serialisiert sie für Downloads.
 *
 * <p>Die Platzhalterexpansion und die statischen Prüfungen finden im Repository statt.
 * {@link de.deutsche_digitale_bibliothek.timeparser.export.ExportDatasetFactory}
 * übernimmt anschließend exakte Dubletten samt Testzuordnung. Der
 * {@link de.deutsche_digitale_bibliothek.timeparser.export.CsvExportService}
 * schreibt die bereits gewählten Zeilen unverändert als CSV beziehungsweise ZIP.</p>
 *
 * <p>Fehlerhinweise sind ausdrücklich mit dem Export vereinbar. Ausgelassene,
 * nicht zuordenbare Testreferenzen werden gemeldet; Konfliktregeln werden nicht
 * still zugunsten einer Transformation verworfen. Die echte Parser-Verarbeitung
 * ist nicht Bestandteil dieses Pakets.</p>
 */
package de.deutsche_digitale_bibliothek.timeparser.export;
