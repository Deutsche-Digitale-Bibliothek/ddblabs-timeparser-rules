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
 * Verarbeitet das Anlegen, Bearbeiten und Löschen von Regelgruppen.
 */
@Controller
@RequiredArgsConstructor
public class RuleController {

    private final SqliteRuleRepository repository;
    private final RuleGroupFormEditor formEditor;
    private final RuleGroupFormValidator formValidator;

    @GetMapping("/rules/new")
    public String newGroup(Model model) {
        RuleGroupForm groupForm = formEditor.newGroupForm();
        model.addAttribute("groupForm", groupForm);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules")
    public String redirectCreateRule() {
        return "redirect:/rules/new";
    }

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

    @PostMapping("/rules/new/preview")
    public String previewNewGroup(@ModelAttribute("groupForm") RuleGroupForm groupForm,
                                  BindingResult bindingResult,
                                  Model model) {
        formValidator.validateForPreview(groupForm, bindingResult);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules/new/rules")
    public String addRuleToNewGroup(@ModelAttribute("groupForm") RuleGroupForm groupForm, Model model) {
        formEditor.addRule(groupForm);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules/new/rules/{ruleIndex}/remove")
    public String removeRuleFromNewGroup(@PathVariable int ruleIndex,
                                         @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                         Model model) {
        formEditor.removeRule(groupForm, ruleIndex);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules/new/rules/{ruleIndex}/tests")
    public String addTestToNewGroupRule(@PathVariable int ruleIndex,
                                        @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                        Model model) {
        formEditor.addTest(groupForm, ruleIndex);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

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

    @GetMapping("/rules/{groupId}")
    public String showGroup(@PathVariable long groupId, Model model) {
        ensureGroupExists(groupId);
        model.addAttribute("groupForm", repository.findGroupForm(groupId));
        model.addAttribute("newGroup", false);
        model.addAttribute("generatedRules", List.of());
        model.addAttribute("generatedTests", List.of());
        return "rules/detail";
    }

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

    @PostMapping("/rules/{groupId}/preview")
    public String previewGroup(@PathVariable long groupId,
                               @ModelAttribute("groupForm") RuleGroupForm groupForm,
                               BindingResult bindingResult,
                               Model model) {
        ensureGroupExists(groupId);
        groupForm.setId(groupId);
        formValidator.validateForPreview(groupForm, bindingResult);
        model.addAttribute("newGroup", false);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

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

    @GetMapping("/rules/{groupId}/delete")
    public String confirmDeleteGroup(@PathVariable long groupId, Model model) {
        ensureGroupExists(groupId);
        model.addAttribute("title", "Regelgruppe löschen");
        model.addAttribute("message", "Soll diese Regelgruppe mit allen generierten Regeln & Tests wirklich gelöscht werden?");
        model.addAttribute("deleteUrl", "/rules/" + groupId + "/delete");
        model.addAttribute("cancelUrl", "/rules/" + groupId);
        return "rules/confirm-delete";
    }

    @PostMapping("/rules/{groupId}/delete")
    public String deleteGroup(@PathVariable long groupId, RedirectAttributes redirectAttributes) {
        ensureGroupExists(groupId);
        repository.deleteGroup(groupId);
        redirectAttributes.addFlashAttribute("message", "Regelgruppe wurde gelöscht.");
        return "redirect:/rules";
    }

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

    private void addFormPreview(Model model, RuleGroupForm groupForm) {
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
