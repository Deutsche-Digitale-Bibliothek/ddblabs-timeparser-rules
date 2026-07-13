package de.deutsche_digitale_bibliothek.timeparser;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

@SpringBootTest
class TimeparserRulesApplicationTest {

    private static final Path TEST_FILE_PREFIX = Path.of(
            System.getProperty("java.io.tmpdir"),
            "timeparser-rules-context-" + UUID.randomUUID()
    );

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("timeparser.database.path", () -> TEST_FILE_PREFIX + ".sqlite");
        registry.add("timeparser.csv.rules-path", () -> TEST_FILE_PREFIX + "-missing-rules.csv");
        registry.add("timeparser.csv.tests-path", () -> TEST_FILE_PREFIX + "-missing-tests.csv");
    }

    @Test
    void contextLoadsWithoutCsvSeedFiles() {
    }
}
