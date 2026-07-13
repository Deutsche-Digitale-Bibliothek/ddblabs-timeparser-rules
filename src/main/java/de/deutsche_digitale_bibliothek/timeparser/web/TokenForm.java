package de.deutsche_digitale_bibliothek.timeparser.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * Formularmodell für Tokens.
 *
 * <p>Token-Werte werden zeilenweise erfasst. Leerzeichen werden nicht getrimmt,
 * weil sie für die spätere Regelgenerierung relevant sind.</p>
 */
@Data
public class TokenForm {

    private Long id;

    @NotBlank
    private String name = "";

    private String description = "";

    @NotEmpty
    private String values = "";
}
