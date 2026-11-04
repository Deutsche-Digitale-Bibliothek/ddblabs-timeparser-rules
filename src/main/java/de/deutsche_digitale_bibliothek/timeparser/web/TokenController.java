package de.deutsche_digitale_bibliothek.timeparser.web;

import de.deutsche_digitale_bibliothek.timeparser.repository.SqliteRuleRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 *
 * <p>Diese Platzhalter erzeugen Varianten aus Regel- und Testvorlagen. Sie sind
 * keine Kalender-Ersetzungen des Timeparsers. Eine Änderung wirkt sich auf die
 * nächste Expansion aller referenzierenden Vorlagen aus, ohne diese Vorlagen
 * umzuschreiben.</p>
 *
 * <p>Vor Änderungen oder Löschungen werden die betroffenen Gruppen ermittelt und
 * als Flash-Attribut {@code tokenAffectedGroups} an die folgende Seite übergeben.
 * Eine Umbenennung führt keine automatische Referenzmigration durch; alte
 * Namen können danach als unbekannte Platzhalter gemeldet werden. Auch die
 * Löschung eines verwendeten Platzhalters bleibt möglich und wird sichtbar
 * gemacht.</p>
 */
@Controller
@RequiredArgsConstructor
public class TokenController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final SqliteRuleRepository repository;
    private final DownloadResponseFactory downloadResponseFactory;

    /** Zeigt vorhandene Platzhalter und ein leeres Formular zum Ergänzen. */
    @GetMapping("/tokens")
    public String tokens(Model model) {
        model.addAttribute("tokens", repository.findTokens());
        model.addAttribute("tokenForm", new TokenForm());
        return "rules/tokens";
    }

    /**
     * Exportiert die Platzhalterverwaltung im Editorformat.
     *
     * @return UTF-8-CSV-Download; kein Ersatz für die Kalenderdatei des Timeparsers
     */
    @GetMapping("/tokens/export")
    public ResponseEntity<byte[]> exportTokens() {
        return downloadResponseFactory.download(repository.tokensCsv(), "timeparser-tokens", "csv", CSV);
    }

    /** Lädt die Werte eines bestehenden Platzhalters zeilenweise ins Formular. */
    @GetMapping("/tokens/{tokenId}/edit")
    public String editToken(@PathVariable long tokenId, Model model) {
        ensureTokenExists(tokenId);
        model.addAttribute("tokens", repository.findTokens());
        model.addAttribute("tokenForm", repository.findTokenForm(tokenId));
        return "rules/tokens";
    }

    /**
     * Validiert und speichert einen neuen Platzhalter. Eine mitgesendete ID
     * wird verworfen; vorhandene Platzhalter verändert nur die Bearbeitungsroute.
     *
     * @param tokenForm gebundener Name, Beschreibung und mehrzeiliger Werteblock
     * @param bindingResult Bean-Validation- und Namenskonfliktfehler
     * @param model erneute Formularanzeige bei Fehlern
     * @param redirectAttributes Erfolgsmeldung nach dem Speichern
     * @return Formularansicht bei Fehlern, sonst Weiterleitung zur Platzhalterliste
     */
    @PostMapping("/tokens")
    public String saveToken(@Valid @ModelAttribute("tokenForm") TokenForm tokenForm,
                            BindingResult bindingResult,
                            Model model,
                            RedirectAttributes redirectAttributes) {
        return saveToken(tokenForm, bindingResult, model, redirectAttributes, null);
    }

    /**
     * Aktualisiert einen Platzhalter und meldet die vor der Änderung bekannten
     * Abhängigkeiten. Der URL-Pfad bestimmt die zu ändernde ID.
     *
     * @param tokenId ID des vorhandenen Platzhalters
     * @param tokenForm neue Verwaltungsdaten; die Formular-ID wird überschrieben
     * @param bindingResult vorhandene und zusätzliche Validierungsfehler
     * @param model Formularattribute bei Fehlern
     * @param redirectAttributes Meldung und betroffene Gruppen nach Erfolg
     * @return Formularansicht bei Fehlern, sonst Weiterleitung zur Liste
     * @throws ResponseStatusException wenn die ID unbekannt ist
     */
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

    /**
     * Löscht den Platzhalter samt Werten und macht betroffene Gruppen sichtbar.
     * Referenzierende Regel- und Testvorlagen bleiben unverändert gespeichert.
     *
     * @param tokenId ID des zu löschenden Platzhalters
     * @param redirectAttributes Löschmeldung und Liste der bisherigen Abhängigkeiten
     * @return Weiterleitung zur Platzhalterliste
     * @throws ResponseStatusException wenn die ID unbekannt ist
     */
    @PostMapping("/tokens/{tokenId}/delete")
    public String deleteToken(@PathVariable long tokenId, RedirectAttributes redirectAttributes) {
        ensureTokenExists(tokenId);
        redirectAttributes.addFlashAttribute("tokenAffectedGroups", repository.groupsUsingToken(tokenId));
        repository.deleteToken(tokenId);
        redirectAttributes.addFlashAttribute("message", "Platzhalter wurde gelöscht.");
        return "redirect:/tokens";
    }

    /**
     * Ergänzt die Bean-Validation um den Namensvergleich ohne Beachtung der
     * Großschreibung. Die eigene ID wird beim Bearbeiten vom Vergleich
     * ausgeschlossen. Abhängigkeiten werden vor der Mutation gelesen, damit
     * auch eine Umbenennung ihre bisherigen Verwendungen sichtbar macht.
     */
    private String saveToken(TokenForm form,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes,
                             Long tokenId) {
        // Der Aufrufpfad bestimmt die Identität: POST /tokens legt immer an;
        // nur die Bearbeitungsroute darf eine vorhandene ID weiterreichen.
        form.setId(tokenId);
        if (repository.tokenNameExists(form.getName(), tokenId)) {
            bindingResult.rejectValue("name", "token.name.duplicate", "Dieser Platzhaltername wird bereits verwendet.");
        }
        if (bindingResult.hasErrors()) {
            model.addAttribute("tokens", repository.findTokens());
            return "rules/tokens";
        }
        if (tokenId != null) {
            redirectAttributes.addFlashAttribute("tokenAffectedGroups", repository.groupsUsingToken(tokenId));
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

}
