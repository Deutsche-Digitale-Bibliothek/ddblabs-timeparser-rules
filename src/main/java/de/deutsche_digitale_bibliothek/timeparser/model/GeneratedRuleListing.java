package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;
import java.util.Map;

/**
 * Erzeugte Regelzeilen und ihre Herkunft aus demselben gelesenen Expansionsstand.
 * Die Sammlungen werden defensiv kopiert; die enthaltenen Regel-Beans bleiben veränderlich.
 */
public record GeneratedRuleListing(List<Rule> rules, Map<String, RuleLocation> locations) {

    public GeneratedRuleListing {
        rules = List.copyOf(rules);
        locations = Map.copyOf(locations);
    }
}
