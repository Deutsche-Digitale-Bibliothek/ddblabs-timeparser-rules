package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Verwaltet Platzhalter und deren CSV-Export.
 */
@Controller
@RequiredArgsConstructor
public class TokenController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final SqliteRuleRepository repository;

    @GetMapping("/tokens")
    public String tokens(Model model) {
        model.addAttribute("tokens", repository.findTokens());
        model.addAttribute("tokenForm", new TokenForm());
        return "rules/tokens";
    }

    @GetMapping("/tokens/export")
    public ResponseEntity<byte[]> exportTokens() {
        return download(repository.tokensCsv(), "tokens.csv", CSV);
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
        return saveToken(tokenForm, bindingResult, model, redirectAttributes, null);
    }

    @PostMapping("/tokens/{tokenId}")
    public String updateToken(@PathVariable long tokenId,
                              @Valid @ModelAttribute("tokenForm") TokenForm tokenForm,
                              BindingResult bindingResult,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        ensureTokenExists(tokenId);
        tokenForm.setId(tokenId);
        return saveToken(tokenForm, bindingResult, model, redirectAttributes, tokenId);
    }

    @PostMapping("/tokens/{tokenId}/delete")
    public String deleteToken(@PathVariable long tokenId, RedirectAttributes redirectAttributes) {
        ensureTokenExists(tokenId);
        repository.deleteToken(tokenId);
        redirectAttributes.addFlashAttribute("message", "Platzhalter wurde gelöscht.");
        return "redirect:/tokens";
    }

    private String saveToken(TokenForm form,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes,
                             Long tokenId) {
        if (repository.tokenNameExists(form.getName(), tokenId)) {
            bindingResult.rejectValue("name", "token.name.duplicate", "Dieser Platzhaltername wird bereits verwendet.");
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("tokens", repository.findTokens());
            return "rules/tokens";
        }
        repository.saveToken(form);
        redirectAttributes.addFlashAttribute("message", "Platzhalter wurde gespeichert.");
        return "redirect:/tokens";
    }

    private void ensureTokenExists(long tokenId) {
        if (!repository.tokenExists(tokenId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Platzhalter " + tokenId + " wurde nicht gefunden.");
        }
    }

    private ResponseEntity<byte[]> download(byte[] content, String filename, MediaType mediaType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentLength(content.length)
                .contentType(mediaType)
                .body(content);
    }
}
