package de.deutsche_digitale_bibliothek.timeparser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * Formularmodell für die vom Editor expandierten Platzhalter.
 *
 * <p>Token-Werte werden zeilenweise erfasst. Leerzeichen werden nicht getrimmt,
 * weil sie für die spätere Regelgenerierung relevant sind. Das Repository
 * zerlegt {@link #values} an Zeilenumbrüchen und verwirft dabei nur vollständig
 * leere Zeilen. Dieses Verwaltungsformat ist unabhängig von den Kalender-Tokens
 * des Timeparsers.</p>
 */
@Data
public class TokenForm {

    /** Gespeicherte ID beim Bearbeiten, sonst {@code null}. */
    private Long id;

    @NotBlank
    /** Name hinter dem Referenzpräfix {@code ~}; Namenskonflikte prüft der Controller. */
    private String name = "";

    private String description = "";

    @NotEmpty
    /** Ein Wert pro Zeile, einschließlich fachlich relevanter Rand-Leerzeichen. */
    private String values = "";
}
