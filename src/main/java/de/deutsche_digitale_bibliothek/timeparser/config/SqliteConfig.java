package de.deutsche_digitale_bibliothek.timeparser.config;

import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Stellt die zentrale SQLite-Verbindung bereit.
 *
 * <p>Der Datenbankpfad ist konfigurierbar, damit lokale Entwicklung, Docker und
 * Serverbetrieb jeweils eigene Speicherorte verwenden können. Es wird eine
 * {@link SQLiteDataSource} bereitgestellt; Schemaerzeugung, optionale Migration
 * und CSV-Erstimport liegen im Repository und nicht in dieser Konfiguration.</p>
 */
@Configuration
public class SqliteConfig {

    /**
     * Erzeugt eine SQLite-DataSource mit aktivierten Foreign-Key-Prüfungen.
     * Die Beziehungen zwischen Gruppen, Regeln und Tests werden damit auch auf
     * Datenbankebene geprüft. Der Pfad wird unverändert in die JDBC-URL übernommen.
     *
     * @param databasePath konfigurierter SQLite-Dateipfad
     * @return zentrale DataSource für JDBC-Zugriffe und Spring-Transaktionen
     */
    @Bean
    public DataSource dataSource(@Value("${timeparser.database.path}") String databasePath) {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.enforceForeignKeys(true);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + databasePath);
        return dataSource;
    }
}
