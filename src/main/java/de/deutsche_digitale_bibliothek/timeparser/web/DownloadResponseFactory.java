package de.deutsche_digitale_bibliothek.timeparser.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Erzeugt Download-Antworten mit einem UTC-Zeitstempel im Dateinamen.
 *
 * <p>Die Formatierung verwendet ausdrücklich UTC, unabhängig von der Zeitzone
 * der Maschine oder einer übergebenen {@link Clock}. Die Millisekunden im Namen
 * unterscheiden übliche aufeinanderfolgende Downloads, garantieren aber keine
 * eindeutige ID für mehrere Aufrufe zum selben Zeitpunkt.</p>
 *
 * <p>Die Klasse serialisiert und validiert den Inhalt nicht. Fachliche Hinweise
 * und zusätzliche Header ergänzen die aufrufenden Controller; hier werden nur
 * Content-Type, Content-Length und der Attachment-Dateiname gesetzt.</p>
 */
@Component
public class DownloadResponseFactory {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final Clock clock;

    /** Verwendet für den Anwendungseinsatz die aktuelle Systemzeit in UTC. */
    public DownloadResponseFactory() {
        this(Clock.systemUTC());
    }

    /** Erlaubt Tests einen festen Zeitpunkt ohne Abhängigkeit von der Systemuhr. */
    DownloadResponseFactory(Clock clock) {
        this.clock = clock;
    }

    /**
     * Verpackt bereits erzeugte Dateibytes als HTTP-Attachment.
     * Beispielname: {@code timeparser-rules-20261103T090000123Z.csv}.
     * Dateinamenbestandteile werden intern festgelegt und hier nicht bereinigt.
     *
     * @param content vollständiger Dateiinhalt, dessen Byteanzahl als Länge gesetzt wird
     * @param baseName Dateiname ohne Zeitstempel und Endung
     * @param extension Dateiendung ohne führenden Punkt
     * @param mediaType Medientyp einschließlich einer gegebenenfalls benötigten Zeichencodierung
     * @return Antwort mit Status 200, Download-Headern und unveränderten Dateibytes
     */
    public ResponseEntity<byte[]> download(byte[] content,
                                           String baseName,
                                           String extension,
                                           MediaType mediaType) {
        String filename = baseName + "-" + TIMESTAMP.format(clock.instant()) + "." + extension;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentLength(content.length)
                .contentType(mediaType)
                .body(content);
    }
}
