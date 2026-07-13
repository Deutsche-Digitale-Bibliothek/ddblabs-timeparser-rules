# DDB Labs Timeparser Rules

Webanwendung zum Anzeigen, Bearbeiten, Prüfen und Exportieren von Timeparser-Regeln und den dazugehörigen Tests.

Die Anwendung kann beim ersten Start `rules.csv` und `tests.csv` in eine lokale SQLite-Datenbank importieren. Danach wird in SQLite gearbeitet. Der CSV-Import ist optional: Sind die Seed-Dateien nicht vorhanden, startet die Anwendung mit leerer Regel-Datenbank und den Standard-Tokens. Der CSV-Export erzeugt wieder einfache, umfangreiche CSV-Dateien für Regeln und Tests.

## Funktionen

- Regelgruppen anzeigen, anlegen, bearbeiten und löschen
- zugehörige Regeln und Tests direkt in der Detailansicht pflegen
- IDs und Beziehungen automatisch vergeben
- Platzhalter/Tokens wie `~approx` verwalten und in Regeln/Tests expandieren
- Plausibilitätscheck für doppelte Regeln und Tests
- sichtbare Darstellung relevanter Leerzeichen in Token-Werten und Plausibilitätsmeldungen
- CSV-Export als Download-ZIP für `rules.csv` und `tests.csv`
- separater CSV-Export für Tokens
- Login-Schutz mit per ENV konfigurierbaren Zugangsdaten
- konfigurierbarer Port, URL-Prefix, SQLite-Pfad und Log-Level
- HTTP-Kompression

## Architektur

- **Backend:** Java 25, Spring Boot, Spring MVC, Spring Security, Spring JDBC
- **Frontend:** Thymeleaf, Bootstrap, Bootstrap Icons, DataTables
- **Persistenz:** SQLite über `sqlite-jdbc`
- **CSV:** Apache Commons CSV

Beim Start erzeugt die Anwendung das Datenbankschema, migriert ältere Datenbanken falls nötig, legt Standard-Tokens an und importiert die CSV-Dateien nur dann, wenn noch keine Regelgruppen vorhanden sind und beide konfigurierten Seed-Dateien existieren.

## Datenmodell

Die Anwendung arbeitet intern mit Regelgruppen:

- `rule_groups`: fachliche Gruppe mit Name und Beschreibung
- `rules`: eine oder mehrere Regelvarianten je Gruppe
- `tests`: Tests zu einer Regel
- `tokens`: Platzhalterdefinitionen
- `token_values`: konkrete Werte eines Platzhalters, inklusive Reihenfolge

Die CSV-Dateien bleiben bewusst einfach:

`rules.csv`

```csv
id,inputMask,inputPattern,outputMask,outputPattern
```

| Spalte | Bedeutung |
| --- | --- |
| `id` | technische Regel-ID, z. B. `R42` |
| `inputMask` | Maske, gegen die die tokenisierte Eingabe geprüft wird |
| `inputPattern` | Muster mit Variablennamen passend zur Eingabemaske |
| `outputMask` | Maske der erzeugten Parser-Eingabe |
| `outputPattern` | Muster, das Variablen aus der Eingabe in die Ausgabe überträgt |

`tests.csv`

```csv
id,for,input,tokenized,output,timespan
```

| Spalte | Bedeutung |
| --- | --- |
| `id` | technische Test-ID, z. B. `T42` |
| `for` | ID der Regel, zu der der Test gehört |
| `input` | ursprüngliche Beispieleingabe |
| `tokenized` | Eingabe nach Monats-/Wochentags-Tokenisierung |
| `output` | erwartete transformierte Parser-Eingabe nach Anwendung der Regel |
| `timespan` | erwarteter Zeitraum als `YYYY-MM-DD/YYYY-MM-DD` |

`tokens.csv`

```csv
name,description,value,position
```

### Masken und Muster

Jede Regel besteht aus Eingabe- und Ausgabe-Maske sowie den passenden Mustern. Maske und Muster müssen jeweils gleich lang sein.

| Maskenzeichen | Musterzeichen | Bedeutung |
| --- | --- | --- |
| `#` | beliebiger Buchstabe außer `M` und `G` | eine Ziffer; mehrere gleiche Musterbuchstaben bilden eine Variable, z. B. `####` / `JJJJ` |
| `MM` | zwei gleiche Buchstaben | Monatsvariable nach der Tokenisierung, z. B. `MM` / `MM` |
| `GG` | zwei gleiche Buchstaben | Wochentagsvariable nach der Tokenisierung |
| sonstiges Zeichen | gleiches Zeichen | Literal, muss exakt passen; das gilt auch für Leerzeichen |

Übliche Variablennamen:

| Muster | Bedeutung |
| --- | --- |
| `JJJJ` | vierstelliges Jahr |
| `J`, `JJ`, `JJJ` | ein-, zwei- oder dreistellige Jahresvariablen |
| `JJJJJ`, `JJJJJJ`, ... | längere Jahresvariablen für große Jahreszahlen |
| `TT` | zweistelliger Tag |
| `MM` | Monatsvariable |
| `WWWW`, `XXXX`, `YYYY`, `ZZZZ` | weitere Jahresvariablen in Bereichen oder zusammengesetzten Ausdrücken |

Beispiel:

```text
Eingabe:        März 2010
tokenized:      MM 2010
inputMask:      MM ####
inputPattern:   MM JJJJ
outputMask:     ####-##
outputPattern:  JJJJ-MM
output:         2010-03
```

Weiteres Beispiel mit Jahresbereich:

```text
Eingabe:        1400-1600
tokenized:      1400-1600
inputMask:      ####-####
inputPattern:   JJJJ-ZZZZ
outputMask:     ####/####
outputPattern:  JJJJ/ZZZZ
output:         1400/1600
```

Der Test-Zeitraum ist ein ISO-Datumsbereich im Format `Start/Ende`, z. B. `2010-01-01/2010-12-31`. Die Anwendung prüft das Format als ISO-Datum; dadurch gelten auch die Grenzen des ISO-/Java-Datumsbereichs von `-999999999-01-01` bis `+999999999-12-31`.

## Platzhalter

Platzhalter werden mit `~name` referenziert, zum Beispiel:

```text
~approx ###
```

Wenn der Token `approx` die Werte `um`, `ca.` und `circa` enthält, erzeugt die Anwendung daraus mehrere konkrete Regeln. Im Export werden doppelte generierte Regeln und Tests herausgefiltert.

Leerzeichen in Token-Werten sind fachlich relevant. `ca.` und `ca. ` sind verschiedene Werte und werden im Frontend entsprechend sichtbar gemacht.

## Lokale Entwicklung

Voraussetzungen:

- Java 25
- Maven

Start:

```bash
mvn spring-boot:run
```

Standard-URL:

```text
http://localhost:8080/app/timeparser-rules
```

Standard-Login:

```text
Benutzer: admin
Passwort: admin
```

Tests/Build:

```bash
mvn clean test
```

## Konfiguration

Die wichtigsten Einstellungen sind per Environment-Variable konfigurierbar:

| Variable | Standard | Bedeutung |
| --- | --- | --- |
| `TIMEPARSER_SERVER_PORT` | `8080` | HTTP-Port der Anwendung |
| `TIMEPARSER_URL_PREFIX` | `/app/timeparser-rules` | Context Path, z. B. für `domain.de/app/timeparser-rules` |
| `TIMEPARSER_DATABASE_PATH` | `timeparser-rules.sqlite` | Speicherort der SQLite-Datei |
| `TIMEPARSER_RULES_CSV_PATH` | `rules.csv` | Optionaler Pfad zur privaten `rules.csv` für den initialen Import |
| `TIMEPARSER_TESTS_CSV_PATH` | `tests.csv` | Optionaler Pfad zur privaten `tests.csv` für den initialen Import |
| `TIMEPARSER_LOG_LEVEL` | `INFO` | Root-Log-Level |
| `TIMEPARSER_SPRING_LOG_LEVEL` | Wert von `TIMEPARSER_LOG_LEVEL` | Spring-spezifisches Log-Level |
| `TIMEPARSER_SECURITY_USERNAME` | `admin` | Login-Benutzer |
| `TIMEPARSER_SECURITY_PASSWORD` | `admin` | Login-Passwort |

Beispiel:

```bash
TIMEPARSER_SERVER_PORT=18080 \
TIMEPARSER_DATABASE_PATH=./data/timeparser-rules.sqlite \
TIMEPARSER_RULES_CSV_PATH=./private/rules.csv \
TIMEPARSER_TESTS_CSV_PATH=./private/tests.csv \
TIMEPARSER_SECURITY_USERNAME=editor \
TIMEPARSER_SECURITY_PASSWORD=secret \
mvn spring-boot:run
```

Die CSV-Dateien werden nur für den initialen Import einer leeren Datenbank benötigt. Tests und Container-Builds benötigen keine echten Projektdaten.

## Docker

Das Docker-Image läuft im Runtime-Container als non-root Benutzer `app` mit UID/GID `10001`.

Image lokal bauen:

```bash
docker build -t ddblabs-timeparser-rules .
```

Container starten:

```bash
docker run --rm \
  -p 8080:8080 \
  -v timeparser-rules-data:/data \
  -e TIMEPARSER_SECURITY_USERNAME=editor \
  -e TIMEPARSER_SECURITY_PASSWORD=secret \
  ddblabs-timeparser-rules
```

Im Container liegt die SQLite-Datei standardmäßig unter:

```text
/data/timeparser-rules.sqlite
```

Das Docker-Image enthält keine `rules.csv` und keine `tests.csv`. Für einen privaten Erstimport können die Dateien zur Laufzeit als read-only Volume eingebunden und per ENV referenziert werden:

```bash
docker run --rm \
  -p 8080:8080 \
  -v timeparser-rules-data:/data \
  -v /pfad/zu/privaten-csv:/seed:ro \
  -e TIMEPARSER_RULES_CSV_PATH=/seed/rules.csv \
  -e TIMEPARSER_TESTS_CSV_PATH=/seed/tests.csv \
  -e TIMEPARSER_SECURITY_USERNAME=editor \
  -e TIMEPARSER_SECURITY_PASSWORD=secret \
  ddblabs-timeparser-rules
```

## Container-Veröffentlichung

Die GitHub Action `.github/workflows/container.yml` baut und veröffentlicht das Image nach GitHub Container Registry:

```text
ghcr.io/<owner>/<repository>
```

Der Workflow läuft bei:

- Push auf `main`
- Tags im Format `v*.*.*`
- manueller Ausführung über `workflow_dispatch`

Veröffentlichte Tags werden aus Branch, Git-Tag und Commit-SHA erzeugt. Auf dem Default-Branch wird zusätzlich `latest` gesetzt.

## Betriebshinweise

- In produktionsnahen Umgebungen sollten `TIMEPARSER_SECURITY_USERNAME` und `TIMEPARSER_SECURITY_PASSWORD` immer gesetzt werden.
- Die SQLite-Datei sollte auf ein persistentes Volume gelegt werden.
- Der URL-Prefix ist bereits auf `/app/timeparser-rules` ausgelegt und kann hinter einem Reverse Proxy unverändert verwendet werden.
- HTTP-Kompression ist für HTML, CSS, JavaScript, JSON, XML und CSV aktiviert.
