package de.deutsche_digitale_bibliothek.timeparser.model;

import java.util.List;
import java.util.Map;

/**
 * Erzeugte Testzeilen und die Herkunft ihrer Regelreferenzen aus demselben Expansionsstand.
 * Die Sammlungen werden defensiv kopiert; die enthaltenen Test-Beans bleiben veränderlich.
 */
public record GeneratedTestListing(List<RuleTest> tests, Map<String, RuleLocation> locations) {

    public GeneratedTestListing {
        tests = List.copyOf(tests);
        locations = Map.copyOf(locations);
    }
}
