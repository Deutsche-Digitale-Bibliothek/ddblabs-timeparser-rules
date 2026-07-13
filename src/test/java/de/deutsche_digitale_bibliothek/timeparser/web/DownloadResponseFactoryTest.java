package de.deutsche_digitale_bibliothek.timeparser.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadResponseFactoryTest {

    @Test
    void addsUtcTimestampToFilename() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T12:34:56.789Z"), ZoneOffset.UTC);
        DownloadResponseFactory factory = new DownloadResponseFactory(clock);

        ResponseEntity<byte[]> response = factory.download(
                new byte[]{1, 2, 3},
                "timeparser-rules",
                "csv",
                MediaType.parseMediaType("text/csv; charset=UTF-8")
        );

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"timeparser-rules-20260713T123456789Z.csv\"");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
    }
}
