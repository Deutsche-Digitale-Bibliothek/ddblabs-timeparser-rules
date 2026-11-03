/**
 * Stellt die Infrastruktur des Editors als Spring-Beans bereit.
 *
 * <p>Die SQLite-DataSource aktiviert die Datenbank-Beziehungsprüfungen; Aufbau
 * und Migration des Schemas erledigt das Repository. Die Sicherheitskonfiguration
 * richtet eine gemeinsame Formularanmeldung mit einem konfigurierten
 * In-Memory-Benutzer ein. Datenbankpfad und Zugangsdaten werden aus den
 * Anwendungseigenschaften gelesen, deren Umgebungsvariablen in
 * {@code application.properties} zugeordnet sind.</p>
 */
package de.deutsche_digitale_bibliothek.timeparser.config;
