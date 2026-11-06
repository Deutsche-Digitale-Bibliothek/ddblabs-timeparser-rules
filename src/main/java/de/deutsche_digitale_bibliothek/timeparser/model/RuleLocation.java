package de.deutsche_digitale_bibliothek.timeparser.model;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * Herkunft einer aktuell erzeugten Regelzeile.
 *
 * <p>Navigation verwendet stets die gespeicherte Vorlagen-ID. Die Herkunft stammt
 * direkt aus der Expansion und bleibt dadurch auch bei gleichnamigen erzeugten IDs
 * und gespeicherten Vorlagen eindeutig.</p>
 *
 * @param id angezeigte erzeugte Regel-ID
 * @param templateId gespeicherte, bearbeitbare Regelvorlage
 * @param groupId Gruppe der Regelvorlage
 * @param groupName angezeigter Gruppenname
 */
public record RuleLocation(String id, String templateId, long groupId, String groupName) {

    /** URI-kodierter Anker der bearbeitbaren Vorlage, auch für importierte IDs mit Sonderzeichen. */
    public String fragment() {
        return UriUtils.encodeFragment("rule-" + templateId, StandardCharsets.UTF_8);
    }
}
