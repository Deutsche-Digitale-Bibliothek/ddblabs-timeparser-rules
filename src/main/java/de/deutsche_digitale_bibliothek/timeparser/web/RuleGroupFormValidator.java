package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

/**
 * Überträgt fachliche Speicherfehler in das Spring-Binding eines Gruppenformulars.
 *
 * <p>Die fachlichen Prüfungen und die Platzhalterexpansion werden zentral durch
 * {@link SqliteRuleRepository#validationIssues(RuleGroupForm)} koordiniert.
 * Dieser Adapter hält keine zweite Regelliste vor: Er übernimmt Feldpfad,
 * Prüfcode und Meldung jedes Befunds unverändert in {@link Errors}. Dadurch
 * verwenden Anlegen, Bearbeiten und Vorschau dieselben Speicherprüfungen wie
 * der Plausibilitätscheck gespeicherter Gruppen.</p>
 *
 * <p>Unverbindliche Hinweise aus
 * {@link SqliteRuleRepository#validationNotices(RuleGroupForm)} gehören nicht in
 * das Binding, weil sie das Speichern nicht verhindern sollen. Der Controller
 * übergibt sie separat an das Ansichtsmodell. Der Adapter bereinigt keine
 * Leerzeilen, verändert das Formular nicht und führt keinen Testfall im
 * Timeparser aus.</p>
 */
@Component
@RequiredArgsConstructor
public class RuleGroupFormValidator {

    private final SqliteRuleRepository repository;

    /**
     * Ergänzt alle fachlichen Speicherfehler im bestehenden Binding.
     *
     * <p>Die Formularstruktur muss bereits durch den Controller vorbereitet
     * sein. Bereits vorhandene Binding- oder Bean-Validation-Fehler bleiben
     * erhalten; diese Methode löscht keine Fehler und schreibt keine Daten.</p>
     *
     * @param form zu prüfendes Formular einschließlich seiner noch expandierbaren Vorlagen
     * @param errors Binding des zugehörigen Formulars; muss dessen Feldpfade auflösen können
     */
    public void validateForSave(RuleGroupForm form, Errors errors) {
        for (var issue : repository.validationIssues(form)) {
            errors.rejectValue(issue.field(), issue.code(), issue.message());
        }
    }

    /**
     * Prüft eine Vorschau mit denselben Anforderungen wie einen Speichervorgang.
     *
     * <p>Die Vorschau ist kein abgeschwächter Prüfmodus. Auch fehlende
     * Pflichtfelder und Strukturfehler werden als konkrete Feldfehler sichtbar;
     * die eigentliche Erzeugung der Vorschau erfolgt anschließend im Controller.</p>
     *
     * @param form zur Vorschau vorgesehenes Gruppenformular
     * @param errors Binding, dem die Speicherfehler hinzugefügt werden
     */
    public void validateForPreview(RuleGroupForm form, Errors errors) {
        validateForSave(form, errors);
    }
}
