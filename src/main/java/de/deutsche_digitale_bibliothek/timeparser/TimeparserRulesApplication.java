package de.deutsche_digitale_bibliothek.timeparser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Einstiegspunkt der Spring-Boot-Anwendung für die Pflege der Timeparser-Regeln.
 */
@SpringBootApplication
public class TimeparserRulesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimeparserRulesApplication.class, args);
    }
}
