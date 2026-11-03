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
 * Serialisiert bereits ausgewählte Regeln, Tests und Platzhalter als CSV oder ZIP.
 *
 * <p>Der Service übernimmt weder Expansion noch Validierung oder Deduplizierung.
 * Er erhält die Reihenfolge und Werte seiner Eingaben, einschließlich relevanter
 * Leerzeichen. Für Regel- und Testdownloads bereitet das Repository die Zeilen
 * über {@link ExportDatasetFactory} vor und verwendet denselben ExportSnapshot
 * für CSV-Dateien und Prüfbericht.</p>
 *
 * <p>CSV-Dateien enthalten eine Kopfzeile, verwenden Kommas als Trennzeichen und
 * UTF-8 als Zeichencodierung. Apache Commons CSV übernimmt das Quoting von
 * Anführungszeichen, Kommas und Zeilenumbrüchen in Feldwerten. Die technische
 * Serialisierung trifft keine Aussage über die Ausführbarkeit im Timeparser.</p>
 *
 * @see de.deutsche_digitale_bibliothek.timeparser.model.ExportSnapshot
 */
@Service
public class CsvExportService {

    private static final String[] RULE_HEADERS = {"id", "inputMask", "inputPattern", "outputMask", "outputPattern"};
    private static final String[] TEST_HEADERS = {"id", "for", "input", "tokenized", "output", "timespan"};
    private static final String[] TOKEN_HEADERS = {"name", "description", "value", "position"};

    /**
     * Erstellt das reine Zwei-Dateien-Paket ohne Prüfbericht.
     *
     * @param rules bereits ausgewählte Regeln
     * @param tests bereits ausgewählte Tests mit passenden Regelreferenzen
     * @return ZIP-Inhalt mit {@code rules.csv} und {@code tests.csv}
     * @throws CsvRepositoryException wenn das Paket nicht serialisiert werden kann
     */
    public byte[] rulesAndTestsZip(List<Rule> rules, List<RuleTest> tests) {
        return rulesAndTestsZip(rules, tests, null);
    }

    /**
     * Erstellt das gemeinsame Download-Paket mit einem optionalen Prüfbericht.
     *
     * <p>Ein Bericht wird auch dann geschrieben, wenn er leer ist. Nur
     * {@code null} unterdrückt den Eintrag {@code pruefhinweise.txt}. Hinweise
     * im Bericht verhindern die Erstellung des ZIP nicht.</p>
     *
     * @param rules bereits ausgewählte Regeln in Exportreihenfolge
     * @param tests bereits ausgewählte Tests in Exportreihenfolge
     * @param report Prüfhinweise als UTF-8-Text oder {@code null} für ein Paket ohne Bericht
     * @return vollständiger ZIP-Inhalt; alle Einträge sind UTF-8-codiert
     * @throws CsvRepositoryException wenn das Paket nicht serialisiert werden kann
     */
    public byte[] rulesAndTestsZip(List<Rule> rules, List<RuleTest> tests, String report) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                writeZipEntry(zipOutputStream, "rules.csv", rulesCsvText(rules));
                writeZipEntry(zipOutputStream, "tests.csv", testsCsvText(tests));
                if (report != null) {
                    writeZipEntry(zipOutputStream, "pruefhinweise.txt", report);
                }
            }
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new CsvRepositoryException("CSV-Export konnte nicht erstellt werden.", exception);
        }
    }

    /**
     * Exportiert die Platzhalterdefinitionen des Editors.
     *
     * <p>Pro Wert entsteht eine Zeile mit nullbasierter Position. Ein Platzhalter
     * ohne Werte erhält eine Zeile mit leeren Wert- und Positionsfeldern. Dieses
     * Format ist kein Ersatz für die {@code calendar_tokens.csv} des Timeparsers.</p>
     *
     * @param tokens Platzhalter mit geordneten Werten
     * @return UTF-8-CSV mit den Spalten {@code name,description,value,position}
     * @throws CsvRepositoryException wenn die CSV nicht serialisiert werden kann
     */
    public byte[] tokensCsv(List<Token> tokens) {
        try {
            return tokenCsvText(tokens).getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new CsvRepositoryException("Token-CSV konnte nicht erstellt werden.", exception);
        }
    }

    /**
     * Schreibt die für den Timeparser vorgesehene Regel-CSV ohne weitere Aufbereitung.
     *
     * @param rules bereits ausgewählte, üblicherweise expandierte Regeln
     * @return UTF-8-CSV mit {@code id,inputMask,inputPattern,outputMask,outputPattern}
     * @throws CsvRepositoryException wenn die CSV nicht serialisiert werden kann
     */
    public byte[] rulesCsv(List<Rule> rules) {
        try {
            return rulesCsvText(rules).getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new CsvRepositoryException("Regel-CSV konnte nicht erstellt werden.", exception);
        }
    }

    /**
     * Schreibt die Test-CSV; die Eigenschaft {@code ruleId} wird in die Spalte {@code for} übertragen.
     *
     * <p>Die erwarteten Zwischen- und Endwerte werden unverändert serialisiert.
     * Die Methode führt diese Beispiele nicht aus und prüft keine Regelreferenzen.</p>
     *
     * @param tests bereits ausgewählte Tests mit den Regel-IDs des zugehörigen Regelexports
     * @return UTF-8-CSV mit {@code id,for,input,tokenized,output,timespan}
     * @throws CsvRepositoryException wenn die CSV nicht serialisiert werden kann
     */
    public byte[] testsCsv(List<RuleTest> tests) {
        try {
            return testsCsvText(tests).getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new CsvRepositoryException("Test-CSV konnte nicht erstellt werden.", exception);
        }
    }

    private void writeZipEntry(ZipOutputStream output, String fileName, String csvText) throws IOException {
        output.putNextEntry(new ZipEntry(fileName));
        output.write(csvText.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private String rulesCsvText(List<Rule> rules) throws IOException {
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

    private String testsCsvText(List<RuleTest> tests) throws IOException {
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
