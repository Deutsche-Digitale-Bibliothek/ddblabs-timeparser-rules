/**
 * Bindet HTTP-Requests an die Formularmodelle und Thymeleaf-Ansichten des Editors.
 *
 * <p>Die Controller trennen die Bearbeitung eines übermittelten Formulars von
 * dessen Persistierung. Zeilenaktionen und Vorschauen verändern nur das
 * Formular; erfolgreiche Schreiboperationen leiten mit einer Flash-Meldung
 * weiter. Feldbezogene Fehler landen im Spring-{@code BindingResult},
 * unverbindliche Hinweise als eigene Model-Attribute in der Ansicht.</p>
 *
 * <p>Export-Requests verwenden den aktuellen Export-Snapshot des Repositorys.
 * Eine Hinweisseite erlaubt ausdrücklich den anschließenden Download trotz
 * Befunden. Weder diese Freigabe noch ein leerer Plausibilitätscheck führt die
 * hinterlegten Beispiele durch den eigentlichen Timeparser aus.</p>
 */
package de.deutsche_digitale_bibliothek.timeparser.web;
