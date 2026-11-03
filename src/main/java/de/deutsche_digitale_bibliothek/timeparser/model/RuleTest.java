package de.deutsche_digitale_bibliothek.timeparser.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flache Testzeile mit Regelreferenz und erwarteten Zwischen- und Endwerten.
 *
 * <p>Die Werte beschreiben ein Beispiel für den Timeparser, werden im Editor aber
 * nur statisch auf Plausibilität geprüft. Eine Instanz besagt daher nicht, dass
 * Tokenisierung, Transformation oder Datumsinterpretation erfolgreich ausgeführt
 * wurden. Das Modell wird für CSV-Import sowie expandierte Vorschau- und Exportzeilen
 * verwendet; die bearbeitbare Testvorlage liegt im RuleGroupForm.</p>
 *
 * <p>Die Regelreferenz gehört zum fachlichen Testbezug. Derselbe Beispielwert darf
 * an verschiedenen Regeln vorkommen. Beim Zusammenführen exakter Regeldubletten
 * kann der Export die Referenz auf die verbleibende Regel umschreiben.</p>
 *
 * @see de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm.TestForm
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleTest {

    /** Technische Test-ID; beim Expandieren können mehrere IDs aus einer Vorlage entstehen. */
    @NotBlank
    private String id = "";

    /** Technische Regel-ID, die als CSV-Spalte {@code for} ausgegeben wird. */
    @NotBlank
    private String ruleId = "";

    /** Ursprüngliche Beispieleingabe, etwa {@code März 2010}. */
    private String input = "";
    /** Erwartete Eingabe nach Monats-/Wochentagstokenisierung, etwa {@code MM 2010}. */
    private String tokenized = "";
    /** Erwarteter transformierter Parser-Ausdruck, etwa {@code 2010-03}. */
    private String output = "";
    /** Optionaler erwarteter ISO-Zeitraum, etwa {@code 2010-03-01/2010-03-31}. */
    private String timespan = "";
}
