package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.model.RulePreview;
import de.deutsche_digitale_bibliothek.timeparser.repository.CsvRepositoryException;
import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Verarbeitet das Anlegen, Bearbeiten, Duplizieren und Löschen von Regelgruppen.
 *
 * <p>Formularaktionen wie das Ergänzen oder Kopieren einer Zeile verändern nur
 * das übermittelte {@link RuleGroupForm}. Erst
 * {@link #createGroup(RuleGroupForm, BindingResult, Model, RedirectAttributes)}
 * und {@link #saveGroup(long, RuleGroupForm, BindingResult, Model, RedirectAttributes)}
 * schreiben diese Bearbeitung nach SQLite. Die separaten
 * Löschaktionen am Ende der Klasse löschen dagegen bereits gespeicherte
 * Entitäten.</p>
 *
 * <p>Speichern und Vorschau entfernen zunächst vollständig leere neue Zeilen
 * und verwenden danach dieselben fachlichen Fehlerprüfungen. Fehler werden über
 * {@link BindingResult} an die betroffenen Formularfelder gebunden. Ergänzende
 * Hinweise und expandierte Beispiele liefert {@link #addFormPreview(Model, RuleGroupForm)}; diese
 * Vorschau führt keine Testfälle im Timeparser aus.</p>
 *
 * <p>Erfolgreiche Schreiboperationen folgen dem Muster Post/Redirect/Get und
 * transportieren ihre Erfolgsmeldung als Flash-Attribut. Bei bestehenden Gruppen
 * ersetzt die ID aus dem URL-Pfad die übermittelte Formular-ID. Die
 * Existenzprüfungen prüfen zusätzlich die Zugehörigkeit gespeicherter Regeln
 * und Tests zu dieser Gruppe.</p>
 */
@Controller
@RequiredArgsConstructor
public class RuleController {

    private static final String DUPLICATE_HINT = "Die Kopie wurde eingefügt. Vor dem Speichern ist eine Anpassung erforderlich, damit keine Dublette entsteht.";

    private final SqliteRuleRepository repository;
    private final RuleGroupFormEditor formEditor;
    private final RuleGroupFormValidator formValidator;

    /**
     * Zeigt ein noch nicht gespeichertes Formular mit einer Regel- und Testzeile.
     *
     * @param model Attribute für die Detailansicht
     * @return Name der Formularansicht
     */
    @GetMapping("/rules/new")
    public String newGroup(Model model) {
        RuleGroupForm groupForm = formEditor.newGroupForm();
        model.addAttribute("groupForm", groupForm);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Leitet den bisherigen Einstieg zum Formular für eine neue Gruppe um. */
    @PostMapping("/rules")
    public String redirectCreateRule() {
        return "redirect:/rules/new";
    }

    /**
     * Bereinigt und validiert eine neue Gruppe, bevor ihre IDs vergeben werden.
     * Bei Fehlern bleibt die Benutzereingabe in der Detailansicht erhalten.
     *
     * @param groupForm gebundene Regel- und Testvorlagen
     * @param bindingResult Bindungs- und Validierungsfehler des Formulars
     * @param model Attribute für eine erneute Anzeige bei Fehlern
     * @param redirectAttributes einmalige Meldung nach erfolgreichem Anlegen
     * @return Detailansicht bei Fehlern, sonst Weiterleitung zur gespeicherten Gruppe
     */
    @PostMapping("/rules/new")
    public String createGroup(@Valid @ModelAttribute("groupForm") RuleGroupForm groupForm,
                              BindingResult bindingResult,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        formEditor.removeBlankRows(groupForm);
        formValidator.validateForSave(groupForm, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("newGroup", true);
            addFormPreview(model, groupForm);
            return "rules/detail";
        }

        long groupId = repository.createGroup(groupForm);
        redirectAttributes.addFlashAttribute("message", "Regelgruppe wurde angelegt.");
        return "redirect:/rules/" + groupId;
    }

    /**
     * Prüft den Entwurf wie beim Speichern und expandiert seine Vorschau ohne
     * Datenbankänderung. Validierungsfehler verhindern die Formularanzeige nicht.
     *
     * @param groupForm noch nicht gespeicherte Vorlagen
     * @param bindingResult Fehler für dieselben Felder wie beim Anlegen
     * @param model Vorschau, Hinweise und Formularstatus
     * @return Name der Detailansicht
     */
    @PostMapping("/rules/new/preview")
    public String previewNewGroup(@ModelAttribute("groupForm") RuleGroupForm groupForm,
                                  BindingResult bindingResult,
                                  Model model) {
        formEditor.removeBlankRows(groupForm);
        formValidator.validateForPreview(groupForm, bindingResult);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Ergänzt im Entwurf eine leere Regel einschließlich einer Testzeile. */
    @PostMapping("/rules/new/rules")
    public String addRuleToNewGroup(@ModelAttribute("groupForm") RuleGroupForm groupForm, Model model) {
        formEditor.addRule(groupForm);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Kopiert eine Entwurfsregel samt Tests ohne deren gespeicherte IDs. */
    @PostMapping("/rules/new/rules/{ruleIndex}/duplicate")
    public String duplicateRuleInNewGroup(@PathVariable int ruleIndex,
                                          @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                          Model model) {
        formEditor.duplicateRule(groupForm, ruleIndex);
        model.addAttribute("newGroup", true);
        model.addAttribute("duplicateHint", DUPLICATE_HINT);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Entfernt eine Formularregel, solange mindestens eine Regelzeile verbleibt. */
    @PostMapping("/rules/new/rules/{ruleIndex}/remove")
    public String removeRuleFromNewGroup(@PathVariable int ruleIndex,
                                         @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                         Model model) {
        formEditor.removeRule(groupForm, ruleIndex);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Ergänzt eine leere Testzeile an der über ihren Listenindex gewählten Regel. */
    @PostMapping("/rules/new/rules/{ruleIndex}/tests")
    public String addTestToNewGroupRule(@PathVariable int ruleIndex,
                                        @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                        Model model) {
        formEditor.addTest(groupForm, ruleIndex);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Kopiert einen Entwurfstest direkt hinter das Original, ohne Test-ID. */
    @PostMapping("/rules/new/rules/{ruleIndex}/tests/{testIndex}/duplicate")
    public String duplicateTestInNewGroupRule(@PathVariable int ruleIndex,
                                              @PathVariable int testIndex,
                                              @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                              Model model) {
        formEditor.duplicateTest(groupForm, ruleIndex, testIndex);
        model.addAttribute("newGroup", true);
        model.addAttribute("duplicateHint", DUPLICATE_HINT);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Entfernt eine Entwurfstestzeile, sofern die Regel noch einen Test behält. */
    @PostMapping("/rules/new/rules/{ruleIndex}/tests/{testIndex}/remove")
    public String removeTestFromNewGroupRule(@PathVariable int ruleIndex,
                                             @PathVariable int testIndex,
                                             @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                             Model model) {
        formEditor.removeTest(groupForm, ruleIndex, testIndex);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /**
     * Lädt gespeicherte Vorlagen zum Bearbeiten. Die expandierte Vorschau bleibt
     * zunächst leer und wird erst durch eine Formularaktion erzeugt.
     *
     * @param groupId ID der zu bearbeitenden Gruppe
     * @param model gespeichertes Formular und Status der Detailansicht
     * @return Name der Detailansicht
     * @throws ResponseStatusException wenn die Gruppe nicht existiert
     */
    @GetMapping("/rules/{groupId}")
    public String showGroup(@PathVariable long groupId, Model model) {
        ensureGroupExists(groupId);
        model.addAttribute("groupForm", repository.findGroupForm(groupId));
        model.addAttribute("newGroup", false);
        model.addAttribute("generatedRules", List.of());
        model.addAttribute("generatedTests", List.of());
        return "rules/detail";
    }

    /**
     * Speichert alle bereinigten Vorlagen einer bestehenden Gruppe nach
     * erfolgreicher Feld- und Fachvalidierung. Hinweise allein blockieren nicht.
     *
     * @param groupId maßgebliche Gruppen-ID aus dem URL-Pfad
     * @param groupForm übermittelte Vorlagen; ihre Gruppen-ID wird überschrieben
     * @param bindingResult Bindungs- und Fachfehler vor dem Datenbankschreiben
     * @param model erneute Formularanzeige bei Fehlern
     * @param redirectAttributes Erfolgsmeldung für den folgenden GET-Aufruf
     * @return Detailansicht bei Fehlern, sonst Weiterleitung zur Gruppe
     * @throws ResponseStatusException wenn die Gruppe nicht existiert
     */
    @PostMapping("/rules/{groupId}")
    public String saveGroup(@PathVariable long groupId,
                            @Valid @ModelAttribute("groupForm") RuleGroupForm groupForm,
                            BindingResult bindingResult,
                            Model model,
                            RedirectAttributes redirectAttributes) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.removeBlankRows(groupForm);
        formValidator.validateForSave(groupForm, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("newGroup", false);
            addFormPreview(model, groupForm);
            return "rules/detail";
        }

        repository.updateGroup(groupForm);
        redirectAttributes.addFlashAttribute("message", "Regelgruppe wurde gespeichert.");
        return "redirect:/rules/{groupId}";
    }

    /**
     * Prüft und expandiert den aktuellen Bearbeitungsstand ohne ihn zu speichern.
     * Die Gruppen-ID ermöglicht Vergleiche mit anderen Gruppen, ohne den eigenen
     * gespeicherten Stand als fremde Dublette zu behandeln.
     *
     * @param groupId ID der bestehenden Gruppe
     * @param groupForm aktueller Formularinhalt
     * @param bindingResult feldbezogene Prüffehler
     * @param model Formularstatus und Vorschauergebnis
     * @return Name der Detailansicht
     */
    @PostMapping("/rules/{groupId}/preview")
    public String previewGroup(@PathVariable long groupId,
                               @ModelAttribute("groupForm") RuleGroupForm groupForm,
                               BindingResult bindingResult,
                               Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.removeBlankRows(groupForm);
        formValidator.validateForPreview(groupForm, bindingResult);
        model.addAttribute("newGroup", false);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Ergänzt eine Regel im Bearbeitungsformular; persistiert erst beim Speichern. */
    @PostMapping("/rules/{groupId}/rules")
    public String addRuleToGroup(@PathVariable long groupId,
                                 @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                 Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.addRule(groupForm);
        model.addAttribute("newGroup", false);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Kopiert eine Formularregel samt Tests und zeigt den Hinweis zur Anpassung. */
    @PostMapping("/rules/{groupId}/rules/{ruleIndex}/duplicate")
    public String duplicateRuleInGroup(@PathVariable long groupId,
                                       @PathVariable int ruleIndex,
                                       @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                       Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.duplicateRule(groupForm, ruleIndex);
        model.addAttribute("newGroup", false);
        model.addAttribute("duplicateHint", DUPLICATE_HINT);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Entfernt die Regel nur aus dem Formular, nicht sofort aus der Datenbank. */
    @PostMapping("/rules/{groupId}/rules/{ruleIndex}/remove")
    public String removeRuleFromGroup(@PathVariable long groupId,
                                      @PathVariable int ruleIndex,
                                      @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                      Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.removeRule(groupForm, ruleIndex);
        model.addAttribute("newGroup", false);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Ergänzt einen Test im aktuellen Formularstand einer bestehenden Gruppe. */
    @PostMapping("/rules/{groupId}/rules/{ruleIndex}/tests")
    public String addTestToGroupRule(@PathVariable long groupId,
                                     @PathVariable int ruleIndex,
                                     @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                     Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.addTest(groupForm, ruleIndex);
        model.addAttribute("newGroup", false);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Kopiert einen Formular-Test ohne ID; gespeichert wird erst mit der Gruppe. */
    @PostMapping("/rules/{groupId}/rules/{ruleIndex}/tests/{testIndex}/duplicate")
    public String duplicateTestInGroupRule(@PathVariable long groupId,
                                           @PathVariable int ruleIndex,
                                           @PathVariable int testIndex,
                                           @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                           Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.duplicateTest(groupForm, ruleIndex, testIndex);
        model.addAttribute("newGroup", false);
        model.addAttribute("duplicateHint", DUPLICATE_HINT);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Entfernt eine Formular-Testzeile unter Beibehaltung mindestens eines Tests. */
    @PostMapping("/rules/{groupId}/rules/{ruleIndex}/tests/{testIndex}/remove")
    public String removeTestFromGroupRule(@PathVariable long groupId,
                                          @PathVariable int ruleIndex,
                                          @PathVariable int testIndex,
                                          @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                          Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formEditor.removeTest(groupForm, ruleIndex, testIndex);
        model.addAttribute("newGroup", false);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    /** Zeigt die Bestätigung für das Löschen einer gespeicherten Gruppe. */
    @GetMapping("/rules/{groupId}/delete")
    public String confirmDeleteGroup(@PathVariable long groupId, Model model) {
        ensureGroupExists(groupId);
        model.addAttribute("title", "Regelgruppe löschen");
        model.addAttribute("message", "Soll diese Regelgruppe mit allen generierten Regeln & Tests wirklich gelöscht werden?");
        model.addAttribute("deleteUrl", "/rules/" + groupId + "/delete");
        model.addAttribute("cancelUrl", "/rules/" + groupId);
        return "rules/confirm-delete";
    }

    /** Löscht die gespeicherte Gruppe einschließlich Regeln und Tests und leitet um. */
    @PostMapping("/rules/{groupId}/delete")
    public String deleteGroup(@PathVariable long groupId, RedirectAttributes redirectAttributes) {
        ensureGroupExists(groupId);
        repository.deleteGroup(groupId);
        redirectAttributes.addFlashAttribute("message", "Regelgruppe wurde gelöscht.");
        return "redirect:/rules";
    }

    /** Prüft die Gruppenzugehörigkeit und zeigt die Bestätigung für eine Regel. */
    @GetMapping("/rules/{groupId}/rules/{ruleId}/delete")
    public String confirmDeleteRule(@PathVariable long groupId,
                                    @PathVariable String ruleId,
                                    Model model) {
        ensureGroupExists(groupId);
        ensureRuleExists(groupId, ruleId);
        model.addAttribute("title", "Regel löschen");
        model.addAttribute("message", "Soll Regel " + ruleId + " mit allen Tests wirklich gelöscht werden?");
        model.addAttribute("deleteUrl", "/rules/" + groupId + "/rules/" + ruleId + "/delete");
        model.addAttribute("cancelUrl", "/rules/" + groupId);
        return "rules/confirm-delete";
    }

    /** Löscht eine gespeicherte Regel samt Tests; die Gruppe muss eine Regel behalten. */
    @PostMapping("/rules/{groupId}/rules/{ruleId}/delete")
    public String deleteRule(@PathVariable long groupId,
                             @PathVariable String ruleId,
                             RedirectAttributes redirectAttributes) {
        ensureGroupExists(groupId);
        ensureRuleExists(groupId, ruleId);
        repository.deleteRule(groupId, ruleId);
        redirectAttributes.addFlashAttribute("message", "Regel " + ruleId + " wurde gelöscht.");
        return "redirect:/rules/{groupId}";
    }

    /** Zeigt die Löschbestätigung nach Prüfung der gesamten Gruppe-Regel-Test-Beziehung. */
    @GetMapping("/rules/{groupId}/rules/{ruleId}/tests/{testId}/delete")
    public String confirmDeleteTest(@PathVariable long groupId,
                                    @PathVariable String ruleId,
                                    @PathVariable String testId,
                                    Model model) {
        ensureGroupExists(groupId);
        ensureTestExists(groupId, ruleId, testId);
        model.addAttribute("title", "Test löschen");
        model.addAttribute("message", "Soll Test " + testId + " wirklich gelöscht werden?");
        model.addAttribute("deleteUrl", "/rules/" + groupId + "/rules/" + ruleId + "/tests/" + testId + "/delete");
        model.addAttribute("cancelUrl", "/rules/" + groupId);
        return "rules/confirm-delete";
    }

    /** Löscht einen gespeicherten Test; mindestens ein Test pro Regel muss verbleiben. */
    @PostMapping("/rules/{groupId}/rules/{ruleId}/tests/{testId}/delete")
    public String deleteTest(@PathVariable long groupId,
                             @PathVariable String ruleId,
                             @PathVariable String testId,
                             RedirectAttributes redirectAttributes) {
        ensureGroupExists(groupId);
        ensureTestExists(groupId, ruleId, testId);
        repository.deleteTest(groupId, ruleId, testId);
        redirectAttributes.addFlashAttribute("message", "Test " + testId + " wurde gelöscht.");
        return "redirect:/rules/{groupId}";
    }

    /**
     * Hält nicht blockierende Hinweise von Feldfehlern getrennt und übersetzt
     * nicht auflösbare Platzhalter in einen sichtbaren Vorschauhinweis. Der
     * Formularinhalt bleibt auch bei einer gescheiterten Expansion erhalten.
     */
    private void addFormPreview(Model model, RuleGroupForm groupForm) {
        model.addAttribute("formNotices", repository.validationNotices(groupForm));
        try {
            RulePreview preview = repository.preview(groupForm);
            model.addAttribute("generatedRules", preview.rules());
            model.addAttribute("generatedTests", preview.tests());
        } catch (CsvRepositoryException e) {
            model.addAttribute("generatedRules", List.of());
            model.addAttribute("generatedTests", List.of());
            model.addAttribute("previewWarning", e.getMessage());
        }
    }

    private void ensureGroupExists(long groupId) {
        if (!repository.groupExists(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Regelgruppe " + groupId + " wurde nicht gefunden.");
        }
    }

    private void ensureRuleExists(long groupId, String ruleId) {
        if (!repository.ruleExists(groupId, ruleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Regel " + ruleId + " wurde nicht gefunden.");
        }
    }

    private void ensureTestExists(long groupId, String ruleId, String testId) {
        if (!repository.testExists(groupId, ruleId, testId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Test " + testId + " wurde nicht gefunden.");
        }
    }

}
