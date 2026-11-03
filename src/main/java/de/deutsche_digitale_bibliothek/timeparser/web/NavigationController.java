package de.deutsche_digitale_bibliothek.timeparser.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Stellt die allgemeinen Einstiegsseiten der Anwendung bereit.
 * Die Formularanmeldung selbst verarbeitet Spring Security; dieser Controller
 * liefert lediglich die dazugehörige Ansicht.
 */
@Controller
public class NavigationController {

    /** Leitet den Anwendungseinstieg zur Liste der Regelgruppen weiter. */
    @GetMapping("/")
    public String redirectToRules() {
        return "redirect:/rules";
    }

    /** Zeigt das Anmeldeformular, ohne Anmeldedaten selbst zu verarbeiten. */
    @GetMapping("/login")
    public String login() {
        return "rules/login";
    }
}
