package de.deutsche_digitale_bibliothek.timeparser.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Stellt die allgemeinen Einstiegsseiten der Anwendung bereit.
 */
@Controller
public class NavigationController {

    @GetMapping("/")
    public String redirectToRules() {
        return "redirect:/rules";
    }

    @GetMapping("/login")
    public String login() {
        return "rules/login";
    }
}
