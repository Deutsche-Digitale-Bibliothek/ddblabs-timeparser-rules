package de.deutsche_digitale_bibliothek.timeparser.export;

import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExportServiceTest {
    private final CsvExportService service = new CsvExportService();
    private final List<Rule> rules = List.of(new Rule("R1", "####", "JJJJ", "####", "JJJJ"));
    private final List<RuleTest> tests = List.of(new RuleTest("T1", "R1", "1900", "1900", "1900", "1900-01-01/1900-12-31"));

    @Test
    void keepsTheExistingTwoFileZipFormatWhenNoReportIsRequested() throws IOException {
        assertThat(entries(service.rulesAndTestsZip(rules, tests))).containsOnlyKeys("rules.csv", "tests.csv");
    }

    @Test
    void addsTheRequestedUtf8ReportWithoutChangingEitherCsv() throws IOException {
        String report = "Prüfhinweise\nRegel R1: Der Zeitraum erfordert eine Prüfung.\n";
        Map<String, String> entries = entries(service.rulesAndTestsZip(rules, tests, report));

        assertThat(entries).containsOnlyKeys("rules.csv", "tests.csv", "pruefhinweise.txt");
        assertThat(entries.get("pruefhinweise.txt")).isEqualTo(report);
        assertThat(entries.get("rules.csv")).isEqualTo(new String(service.rulesCsv(rules), StandardCharsets.UTF_8));
        assertThat(entries.get("tests.csv")).isEqualTo(new String(service.testsCsv(tests), StandardCharsets.UTF_8));
    }

    private Map<String, String> entries(byte[] zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                entries.put(entry.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
