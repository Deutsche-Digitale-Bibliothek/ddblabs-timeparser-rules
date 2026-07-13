package de.deutsche_digitale_bibliothek.timeparser.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleTest {

    @NotBlank
    private String id = "";

    @NotBlank
    private String ruleId = "";

    private String input = "";
    private String tokenized = "";
    private String output = "";
    private String timespan = "";
}
