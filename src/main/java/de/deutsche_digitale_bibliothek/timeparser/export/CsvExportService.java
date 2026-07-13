package de.deutsche_digitale_bibliothek.timeparser.export;

import de.deutsche_digitale_bibliothek.timeparser.model.Rule;
import de.deutsche_digitale_bibliothek.timeparser.model.RuleTest;
import de.deutsche_digitale_bibliothek.timeparser.model.Token;
import de.deutsche_digitale_bibliothek.timeparser.repository.CsvRepositoryException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serialisiert bereits ausgewählte Regeln, Tests und Tokens in die Downloadformate.
 */
@Service
public class CsvExportService {

    private static final String[] RULE_HEADERS = {"id", "inputMask", "inputPattern", "outputMask", "outputPattern"};
    private static final String[] TEST_HEADERS = {"id", "for", "input", "tokenized", "output", "timespan"};
    private static final String[] TOKEN_HEADERS = {"name", "description", "value", "position"};

    public byte[] rulesAndTestsZip(List<Rule> rules, List<RuleTest> tests) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                writeZipEntry(zipOutputStream, "rules.csv", rulesCsv(rules));
                writeZipEntry(zipOutputStream, "tests.csv", testsCsv(tests));
            }
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new CsvRepositoryException("CSV-Export konnte nicht erstellt werden.", exception);
        }
    }

    public byte[] tokensCsv(List<Token> tokens) {
        try {
            return tokenCsvText(tokens).getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new CsvRepositoryException("Token-CSV konnte nicht erstellt werden.", exception);
        }
    }

    private void writeZipEntry(ZipOutputStream output, String fileName, String csvText) throws IOException {
        output.putNextEntry(new ZipEntry(fileName));
        output.write(csvText.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private String rulesCsv(List<Rule> rules) throws IOException {
        try (StringWriter writer = new StringWriter();
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(RULE_HEADERS).get())) {
            for (Rule rule : rules) {
                printer.printRecord(
                        rule.getId(),
                        rule.getInputMask(),
                        rule.getInputPattern(),
                        rule.getOutputMask(),
                        rule.getOutputPattern()
                );
            }
            return writer.toString();
        }
    }

    private String testsCsv(List<RuleTest> tests) throws IOException {
        try (StringWriter writer = new StringWriter();
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(TEST_HEADERS).get())) {
            for (RuleTest test : tests) {
                printer.printRecord(
                        test.getId(),
                        test.getRuleId(),
                        test.getInput(),
                        test.getTokenized(),
                        test.getOutput(),
                        test.getTimespan()
                );
            }
            return writer.toString();
        }
    }

    private String tokenCsvText(List<Token> tokens) throws IOException {
        try (StringWriter writer = new StringWriter();
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(TOKEN_HEADERS).get())) {
            for (Token token : tokens) {
                if (token.values().isEmpty()) {
                    printer.printRecord(token.name(), token.description(), "", "");
                    continue;
                }
                for (int index = 0; index < token.values().size(); index++) {
                    printer.printRecord(token.name(), token.description(), token.values().get(index), index);
                }
            }
            return writer.toString();
        }
    }
}
