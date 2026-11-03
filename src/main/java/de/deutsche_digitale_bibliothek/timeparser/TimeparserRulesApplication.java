package de.deutsche_digitale_bibliothek.timeparser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Einstiegspunkt der Spring-Boot-Anwendung für die Pflege der Timeparser-Regeln.
 * Durch die Paketposition umfasst die automatische Komponentensuche Controller,
 * Validierung, Export und Datenbankkonfiguration des Editors. Der eigentliche
 * Timeparser wird dabei nicht als Laufzeitkomponente eingebunden.
 */
@SpringBootApplication
public class TimeparserRulesApplication {

    /**
     * Startet den Anwendungskontext und den eingebetteten Webserver.
     *
     * @param args an Spring Boot weitergereichte Kommandozeilenargumente
     */
    public static void main(String[] args) {
        SpringApplication.run(TimeparserRulesApplication.class, args);
    }
}
