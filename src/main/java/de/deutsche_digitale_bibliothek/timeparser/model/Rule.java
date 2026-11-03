package de.deutsche_digitale_bibliothek.timeparser.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flache Regelzeile mit technischer ID sowie Eingabe- und Ausgabespezifikation.
 *
 * <p>Das Modell wird beim CSV-Import und für expandierte Vorschau- und Exportzeilen
 * verwendet. Bearbeitbare Vorlagen mit zugeordneten Tests werden dagegen über
 * {@link de.deutsche_digitale_bibliothek.timeparser.web.RuleGroupForm} gepflegt.
 * Die Bean selbst expandiert oder validiert keine Werte.</p>
 *
 * <p>Eine Maske beschreibt Zeichenkategorien und Literale; das zugehörige Muster
 * benennt die Variablen. Beispielsweise bezeichnet {@code ####}/{@code JJJJ}
 * eine vierstellige Eingabevariable. In der Ausgabe darf diese Variable mehrfach
 * vorkommen, sofern jede Verwendung in die Eingabebreite passt.</p>
 *
 * <p>Lombok stellt die veränderlichen Eigenschaften sowie Gleichheit und Hashcode
 * über alle Felder bereit. Für die fachliche Deduplizierung wird bewusst eine
 * separate Signatur ohne technische ID verwendet.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Rule {

    /** Technische Regel-ID; generierte Varianten können eine abgeleitete ID erhalten. */
    @NotBlank
    private String id = "";

    /** Maske der tokenisierten Eingabe, etwa {@code MM ####}. */
    private String inputMask = "";
    /** Variablendefinitionen passend zur Eingabemaske, etwa {@code MM JJJJ}. */
    private String inputPattern = "";
    /** Maske des durch die Regel vorgesehenen Parser-Ausdrucks, etwa {@code ####-##}. */
    private String outputMask = "";
    /** Anordnung von Eingabevariablen und Literalen für die Ausgabe, etwa {@code JJJJ-MM}. */
    private String outputPattern = "";
}
