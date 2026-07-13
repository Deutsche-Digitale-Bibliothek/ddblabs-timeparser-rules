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
 */
@Component
public class DownloadResponseFactory {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final Clock clock;

    public DownloadResponseFactory() {
        this(Clock.systemUTC());
    }

    DownloadResponseFactory(Clock clock) {
        this.clock = clock;
    }

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
