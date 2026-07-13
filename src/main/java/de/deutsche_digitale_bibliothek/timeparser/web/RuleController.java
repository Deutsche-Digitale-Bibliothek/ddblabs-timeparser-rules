package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import de.deutsche_digitale_bibliothek.timeparser.repository.CsvRepositoryException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * MVC-Controller für alle HTML-Ansichten und CSV-Downloads.
 *
 * <p>Der Controller hält bewusst nur Web- und Validierungslogik; Persistenz,
 * Token-Expansion und Export werden im Repository gekapselt.</p>
 */
@Controller
public class RuleController {

    private final SqliteRuleRepository repository;

    public RuleController(SqliteRuleRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/")
    public String redirectToRules() {
        return "redirect:/rules";
    }

    @GetMapping("/login")
    public String login() {
        return "rules/login";
    }

    @GetMapping("/rules")
    public String listRules(Model model) {
        model.addAttribute("groups", repository.findGroupSummaries());
        return "rules/list";
    }

    @GetMapping("/generated-rules")
    public String generatedRules(Model model) {
        model.addAttribute("rules", repository.generatedRules());
        return "rules/generated-rules";
    }

    @GetMapping("/generated-tests")
    public String generatedTests(Model model) {
        model.addAttribute("tests", repository.generatedTests());
        return "rules/generated-tests";
    }

    @GetMapping("/plausibility")
    public String plausibility(Model model) {
        model.addAttribute("plausibilityWarnings", repository.plausibilityWarnings());
        return "rules/plausibility";
    }

    @GetMapping("/rules/export")
    public ResponseEntity<byte[]> exportCsv() {
        return download(
                repository.rulesAndTestsZip(),
                "timeparser-rules-csv.zip",
                MediaType.parseMediaType("application/zip")
        );
    }

    @GetMapping("/rules/new")
    public String newGroup(Model model) {
        RuleGroupForm groupForm = newGroupForm();
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
        removeBlankNewRows(groupForm);
        addStructureErrors(groupForm, bindingResult);
        addRequiredFieldErrors(groupForm, bindingResult);
        addPlausibilityErrors(groupForm, bindingResult);
        addTokenErrors(groupForm, bindingResult);
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
        addPlausibilityErrors(groupForm, bindingResult);
        addTokenErrors(groupForm, bindingResult);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules/new/rules")
    public String addRuleToNewGroup(@ModelAttribute("groupForm") RuleGroupForm groupForm, Model model) {
        groupForm.getRules().add(newRuleVariant());
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules/new/rules/{ruleIndex}/remove")
    public String removeRuleFromNewGroup(@PathVariable int ruleIndex,
                                         @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                         Model model) {
        removeRuleAt(ruleIndex, groupForm);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules/new/rules/{ruleIndex}/tests")
    public String addTestToNewGroupRule(@PathVariable int ruleIndex,
                                        @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                        Model model) {
        addTestAt(ruleIndex, groupForm);
        model.addAttribute("newGroup", true);
        addFormPreview(model, groupForm);
        return "rules/detail";
    }

    @PostMapping("/rules/new/rules/{ruleIndex}/tests/{testIndex}/remove")
    public String removeTestFromNewGroupRule(@PathVariable int ruleIndex,
                                             @PathVariable int testIndex,
                                             @ModelAttribute("groupForm") RuleGroupForm groupForm,
                                             Model model) {
        removeTestAt(ruleIndex, testIndex, groupForm);
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
        removeBlankNewRows(groupForm);
        addStructureErrors(groupForm, bindingResult);
        addRequiredFieldErrors(groupForm, bindingResult);
        addPlausibilityErrors(groupForm, bindingResult);
        addTokenErrors(groupForm, bindingResult);
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
        addPlausibilityErrors(groupForm, bindingResult);
        addTokenErrors(groupForm, bindingResult);
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
        groupForm.getRules().add(newRuleVariant());
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
        removeRuleAt(ruleIndex, groupForm);
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
        addTestAt(ruleIndex, groupForm);
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
        removeTestAt(ruleIndex, testIndex, groupForm);
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

    @GetMapping("/tokens")
    public String tokens(Model model) {
        model.addAttribute("tokens", repository.findTokens());
        model.addAttribute("tokenForm", new TokenForm());
        return "rules/tokens";
    }

    @GetMapping("/tokens/export")
    public ResponseEntity<byte[]> exportTokens() {
        return download(
                repository.tokensCsv(),
                "tokens.csv",
                MediaType.parseMediaType("text/csv; charset=UTF-8")
        );
    }

    @GetMapping("/tokens/{tokenId}/edit")
    public String editToken(@PathVariable long tokenId, Model model) {
        ensureTokenExists(tokenId);
        model.addAttribute("tokens", repository.findTokens());
        model.addAttribute("tokenForm", repository.findTokenForm(tokenId));
        return "rules/tokens";
    }

    @PostMapping("/tokens")
    public String saveToken(@Valid @ModelAttribute("tokenForm") TokenForm tokenForm,
                            BindingResult bindingResult,
                            Model model,
                            RedirectAttributes redirectAttributes) {
        addTokenNameErrors(tokenForm, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("tokens", repository.findTokens());
            return "rules/tokens";
        }
        repository.saveToken(tokenForm);
        redirectAttributes.addFlashAttribute("message", "Platzhalter wurde gespeichert.");
        return "redirect:/tokens";
    }

    @PostMapping("/tokens/{tokenId}")
    public String updateToken(@PathVariable long tokenId,
                              @Valid @ModelAttribute("tokenForm") TokenForm tokenForm,
                              BindingResult bindingResult,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        ensureTokenExists(tokenId);
        tokenForm.setId(tokenId);
        addTokenNameErrors(tokenForm, bindingResult);
        if (bindingResult.hasErrors()) {
            model.addAttribute("tokens", repository.findTokens());
            return "rules/tokens";
        }
        repository.saveToken(tokenForm);
        redirectAttributes.addFlashAttribute("message", "Platzhalter wurde gespeichert.");
        return "redirect:/tokens";
    }

    @PostMapping("/tokens/{tokenId}/delete")
    public String deleteToken(@PathVariable long tokenId, RedirectAttributes redirectAttributes) {
        ensureTokenExists(tokenId);
        repository.deleteToken(tokenId);
        redirectAttributes.addFlashAttribute("message", "Platzhalter wurde gelöscht.");
        return "redirect:/tokens";
    }

    private RuleGroupForm newGroupForm() {
        RuleGroupForm form = new RuleGroupForm();
        form.getRules().add(newRuleVariant());
        return form;
    }

    private RuleGroupForm.RuleVariantForm newRuleVariant() {
        RuleGroupForm.RuleVariantForm rule = new RuleGroupForm.RuleVariantForm();
        rule.getTests().add(new RuleGroupForm.TestForm());
        return rule;
    }

    private void addTestAt(int ruleIndex, RuleGroupForm form) {
        if (ruleIndex >= 0 && ruleIndex < form.getRules().size()) {
            form.getRules().get(ruleIndex).getTests().add(new RuleGroupForm.TestForm());
        }
    }

    private void removeRuleAt(int ruleIndex, RuleGroupForm form) {
        if (form.getRules().size() > 1 && ruleIndex >= 0 && ruleIndex < form.getRules().size()) {
            form.getRules().remove(ruleIndex);
        }
    }

    private void removeTestAt(int ruleIndex, int testIndex, RuleGroupForm form) {
        if (ruleIndex < 0 || ruleIndex >= form.getRules().size()) {
            return;
        }
        List<RuleGroupForm.TestForm> tests = form.getRules().get(ruleIndex).getTests();
        if (tests.size() > 1 && testIndex >= 0 && testIndex < tests.size()) {
            tests.remove(testIndex);
        }
    }

    private void addFormPreview(Model model, RuleGroupForm groupForm) {
        try {
            model.addAttribute("generatedRules", repository.previewRules(groupForm));
            model.addAttribute("generatedTests", repository.previewTests(groupForm));
        } catch (CsvRepositoryException e) {
            model.addAttribute("generatedRules", List.of());
            model.addAttribute("generatedTests", List.of());
            model.addAttribute("previewWarning", e.getMessage());
        }
    }

    private void removeBlankNewRows(RuleGroupForm form) {
        for (RuleGroupForm.RuleVariantForm rule : form.getRules()) {
            rule.getTests().removeIf(test -> isBlank(test.getId())
                    && isBlank(test.getInput())
                    && isBlank(test.getTokenized())
                    && isBlank(test.getOutput())
                    && isBlank(test.getTimespan()));
        }
        form.getRules().removeIf(rule -> isBlank(rule.getId())
                && isBlank(rule.getInputMask())
                && isBlank(rule.getInputPattern())
                && isBlank(rule.getOutputMask())
                && isBlank(rule.getOutputPattern())
                && rule.getTests().isEmpty());
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

    private void ensureTokenExists(long tokenId) {
        if (!repository.tokenExists(tokenId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Platzhalter " + tokenId + " wurde nicht gefunden.");
        }
    }

    private void addStructureErrors(RuleGroupForm form, BindingResult bindingResult) {
        if (form.getRules().isEmpty()) {
            bindingResult.addError(new FieldError("groupForm", "rules", "Mindestens eine Regel ist erforderlich."));
            return;
        }
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = form.getRules().get(ruleIndex);
            if (rule.getTests().isEmpty()) {
                bindingResult.addError(new FieldError(
                        "groupForm",
                        "rules[" + ruleIndex + "].tests",
                        "Mindestens ein Test ist erforderlich."
                ));
            }
        }
    }

    private void addRequiredFieldErrors(RuleGroupForm form, BindingResult bindingResult) {
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = form.getRules().get(ruleIndex);
            rejectBlank(bindingResult, "rules[" + ruleIndex + "].inputMask", rule.getInputMask(), "Die Eingabemaske ist erforderlich.");
            rejectBlank(bindingResult, "rules[" + ruleIndex + "].inputPattern", rule.getInputPattern(), "Das Eingabemuster ist erforderlich.");
            rejectBlank(bindingResult, "rules[" + ruleIndex + "].outputMask", rule.getOutputMask(), "Die Ausgabemaske ist erforderlich.");
            rejectBlank(bindingResult, "rules[" + ruleIndex + "].outputPattern", rule.getOutputPattern(), "Das Ausgabemuster ist erforderlich.");

            for (int testIndex = 0; testIndex < rule.getTests().size(); testIndex++) {
                RuleGroupForm.TestForm test = rule.getTests().get(testIndex);
                String fieldPrefix = "rules[" + ruleIndex + "].tests[" + testIndex + "]";
                rejectBlank(bindingResult, fieldPrefix + ".input", test.getInput(), "Die Eingabe ist erforderlich.");
                rejectBlank(bindingResult, fieldPrefix + ".tokenized", test.getTokenized(), "Der tokenisierte Wert ist erforderlich.");
                rejectBlank(bindingResult, fieldPrefix + ".output", test.getOutput(), "Die Ausgabe ist erforderlich.");
            }
        }
    }

    private void addPlausibilityErrors(RuleGroupForm form, BindingResult bindingResult) {
        for (int ruleIndex = 0; ruleIndex < form.getRules().size(); ruleIndex++) {
            RuleGroupForm.RuleVariantForm rule = form.getRules().get(ruleIndex);
            String rulePrefix = "rules[" + ruleIndex + "]";
            rejectLengthMismatch(
                    bindingResult,
                    rulePrefix + ".inputPattern",
                    rule.getInputMask(),
                    rule.getInputPattern(),
                    "Eingabemuster und Eingabemaske müssen gleich lang sein."
            );
            rejectLengthMismatch(
                    bindingResult,
                    rulePrefix + ".outputPattern",
                    rule.getOutputMask(),
                    rule.getOutputPattern(),
                    "Ausgabemuster und Ausgabemaske müssen gleich lang sein."
            );

            for (int testIndex = 0; testIndex < rule.getTests().size(); testIndex++) {
                RuleGroupForm.TestForm test = rule.getTests().get(testIndex);
                String fieldPrefix = rulePrefix + ".tests[" + testIndex + "]";
                rejectGeneratedValueLengthMismatch(
                        bindingResult,
                        fieldPrefix + ".tokenized",
                        rule.getInputMask(),
                        test.getTokenized(),
                        "Der tokenisierte Testwert muss zur Eingabemaske gleich lang sein."
                );
                rejectGeneratedValueLengthMismatch(
                        bindingResult,
                        fieldPrefix + ".output",
                        rule.getOutputMask(),
                        test.getOutput(),
                        "Die erwartete Ausgabe muss zur Ausgabemaske gleich lang sein."
                );
                rejectInvalidTimespan(bindingResult, fieldPrefix + ".timespan", test.getTimespan());
            }
        }
    }

    private void rejectBlank(BindingResult bindingResult, String field, String value, String message) {
        if (isBlank(value)) {
            bindingResult.addError(new FieldError("groupForm", field, message));
        }
    }

    private void rejectLengthMismatch(BindingResult bindingResult,
                                      String field,
                                      String mask,
                                      String pattern,
                                      String message) {
        if (!isBlank(mask) && !isBlank(pattern) && length(mask) != length(pattern)) {
            bindingResult.addError(new FieldError("groupForm", field, message));
        }
    }

    private void rejectGeneratedValueLengthMismatch(BindingResult bindingResult,
                                                    String field,
                                                    String mask,
                                                    String value,
                                                    String message) {
        if (!isBlank(mask)
                && !isBlank(value)
                && !containsToken(mask)
                && !containsToken(value)
                && length(mask) != length(value)) {
            bindingResult.addError(new FieldError("groupForm", field, message));
        }
    }

    private void rejectInvalidTimespan(BindingResult bindingResult, String field, String value) {
        if (isBlank(value)) {
            return;
        }
        String[] dates = value.split("/", -1);
        if (dates.length != 2) {
            bindingResult.addError(new FieldError("groupForm", field, "Der Zeitraum muss zwei ISO-Daten im Format Start/Ende enthalten."));
            return;
        }
        try {
            LocalDate start = LocalDate.parse(dates[0]);
            LocalDate end = LocalDate.parse(dates[1]);
            if (start.isAfter(end)) {
                bindingResult.addError(new FieldError("groupForm", field, "Der Zeitraum darf nicht vor seinem Start enden."));
            }
        } catch (DateTimeParseException e) {
            bindingResult.addError(new FieldError("groupForm", field, "Der Zeitraum muss ISO-Daten im Format YYYY-MM-DD/YYYY-MM-DD verwenden."));
        }
    }

    private void addTokenErrors(RuleGroupForm form, BindingResult bindingResult) {
        for (String tokenName : repository.unknownTokenNames(form)) {
            bindingResult.addError(new FieldError(
                    "groupForm",
                    "rules",
                    "Platzhalter ~" + tokenName + " ist nicht definiert."
            ));
        }
    }

    private void addTokenNameErrors(TokenForm form, BindingResult bindingResult) {
        if (repository.tokenNameExists(form.getName(), form.getId())) {
            bindingResult.addError(new FieldError(
                    "tokenForm",
                    "name",
                    "Dieser Platzhaltername wird bereits verwendet."
            ));
        }
    }

    private ResponseEntity<byte[]> download(byte[] content, String filename, MediaType mediaType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentLength(content.length)
                .contentType(mediaType)
                .body(content);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean containsToken(String value) {
        return value != null && value.contains("~");
    }

    private int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
