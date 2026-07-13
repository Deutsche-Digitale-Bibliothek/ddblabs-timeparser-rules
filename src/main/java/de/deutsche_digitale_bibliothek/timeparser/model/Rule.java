package de.deutsche_digitale_bibliothek.timeparser.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Rule {

    @NotBlank
    private String id = "";

    private String inputMask = "";
    private String inputPattern = "";
    private String outputMask = "";
    private String outputPattern = "";
}
