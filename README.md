# DDBlabs Timeparser Rules

Webanwendung zum Anzeigen, Bearbeiten, Prüfen und Exportieren von Timeparser-Regeln und den dazugehörigen Tests.

Die Anwendung kann beim ersten Start `rules.csv` und `tests.csv` in eine lokale SQLite-Datenbank importieren. Danach wird in SQLite gearbeitet. Der CSV-Import ist optional: Sind die Seed-Dateien nicht vorhanden, startet die Anwendung mit leerer Regel-Datenbank und den Standard-Tokens. Der CSV-Export erzeugt wieder einfache, umfangreiche CSV-Dateien für Regeln und Tests.

## Funktionen

- Regelgruppen anzeigen, anlegen, bearbeiten und löschen
- Regelgruppen aus den erzeugten Regeln und Tests direkt öffnen
- zugehörige Regeln und Tests direkt in der Detailansicht pflegen
- IDs und Beziehungen automatisch vergeben
- Platzhalter/Tokens wie `~approx` verwalten und in Regeln/Tests expandieren
- gemeinsame Validierung bereits beim Öffnen sowie beim Anlegen, Bearbeiten, in der Vorschau und im Plausibilitätscheck
- Speichern fehlerhafter Regelgruppen nur nach ausdrücklicher Zustimmung zum angezeigten Prüfstand
- sichtbare Darstellung relevanter Leerzeichen in Token-Werten und Plausibilitätsmeldungen
- CSV-Export als Download-ZIP für `rules.csv` und `tests.csv`
- separater CSV-Export für Tokens
- Login-Schutz mit per ENV konfigurierbaren Zugangsdaten
- konfigurierbarer Port, URL-Prefix, SQLite-Pfad und Log-Level
- HTTP-Kompression

In der Liste der erzeugten Regeln stehen Gruppenname und Vorlagen-ID. Regel-IDs und Regelreferenzen der Testliste öffnen die zugehörige Regelgruppe direkt an der bearbeitbaren Regelvorlage. Auch erzeugte Varianten wie `R370_2` verweisen auf ihre tatsächliche Herkunft.

## Architektur

Für die Pflege des Codes beschreibt der [Entwicklerleitfaden](docs/entwicklung.md) die Aufrufabläufe, Vorlagen- und Variantenmodelle, Validierungsverträge, Platzhalterexpansion sowie die Export- und Testgrenzen. Die Klassen- und Paket-Javadocs dokumentieren die jeweiligen Schnittstellen direkt am Code.

- **Backend:** Java 25, Spring Boot, Spring MVC, Spring Security, Spring JDBC
- **Frontend:** Thymeleaf, Bootstrap, Bootstrap Icons, DataTables 3 (ohne jQuery)
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

Jede Regel besteht aus Eingabemaske, Eingabemuster, Ausgabemaske und Ausgabemuster. Eingabemaske und Eingabemuster beziehen sich auf dieselben Zeichenpositionen der Eingabe; dasselbe gilt für Ausgabemaske und Ausgabemuster in der Ausgabe. **Die Maske beschreibt die Stellen, das Muster kennzeichnet zusammengehörige Werte.** Maske und Muster müssen deshalb jeweils gleich lang sein. Die verwendeten Buchstaben sind frei wählbar.

| Zeichen in der Eingabemaske | Zeichen an denselben Stellen im Eingabemuster | Bedeutung |
| --- | --- | --- |
| `##` | `NN` | Die zwei Ziffern dieser Eingabestellen werden als ein Wert erfasst, z. B. `03`. Im Muster kennzeichnet `NN` diesen Wert. |
| `##` | `MM` | Dieselben zwei Ziffern werden erfasst, z. B. `03`. Diesmal kennzeichnet `MM` den Wert. |
| `MM` | `NN` | Ein erkannter Monatsname wird verarbeitet, z. B. `März`; sein Monatswert `03` wird mit `NN` gekennzeichnet. |
| `GG` | zwei gleiche Buchstaben | Wochentagsvariable nach der Tokenisierung |
| sonstiges Zeichen | gleiches Zeichen | Literal, muss exakt passen; das gilt auch für Leerzeichen |

Ein durchgängiges Beispiel für die Eingabe `03.2010`:

| Feld | Wert | Wirkung |
| --- | --- | --- |
| Eingabemaske | `##.####` | Erwartet zwei Ziffern, einen Punkt und vier Ziffern. |
| Eingabemuster | `NN.JJJJ` | `NN` erfasst `03`; `JJJJ` erfasst `2010`. |
| Ausgabemaske | `####-##` | Beschreibt vier Ausgabestellen, einen Bindestrich und zwei Ausgabestellen. |
| Ausgabemuster | `JJJJ-NN` | Setzt den erfassten Wert `2010`, den Bindestrich und den erfassten Wert `03` zu `2010-03` zusammen. |

Der Buchstabe legt den Namen fest, die Anzahl gleicher Buchstaben legt die Breite fest: `NN` besteht aus zwei `N` und erfasst zwei Zeichen als einen Wert; `JJJJ` besteht aus vier `J` und erfasst vier Zeichen. Wird im Beispiel das Eingabemuster `MM.JJJJ` verwendet, erfasst `MM` die gleichen Ziffern `03`. Das dazu passende Ausgabemuster lautet dann `JJJJ-MM`. Die Buchstaben `MM` im Muster kennzeichnen hier also den erfassten Wert `03`.

Für einen **Monatsnamen** steht dagegen `MM` in der **Eingabemaske**. Beispielsweise wird `März 2010` für die Regelauswahl zu `MM 2010` tokenisiert. Die Eingabemaske `MM ####` und das Eingabemuster `NN JJJJ` erfassen diesen Monatsnamen und das Jahr; bei der Transformation erhält `NN` den Monatswert `03`. Das Ausgabemuster `JJJJ-NN` setzt daraus `2010-03` zusammen. `##` in der Eingabemaske erwartet tatsächlich Ziffern und passt daher zu `03`, nicht zu einem Monatsnamen.

Übliche Variablennamen als Konvention, nicht als reservierte Datumsfelder:

| Muster | Bedeutung |
| --- | --- |
| `JJJJ` | häufig für ein vierstelliges Jahr |
| `J`, `JJ`, `JJJ` | häufig für ein-, zwei- oder dreistellige Jahreswerte |
| `JJJJJ`, `JJJJJJ`, ... | häufig für längere Jahreswerte |
| `TT` | häufig für einen zweistelligen Tag |
| `MM` | häufig für einen Monatswert; bei Eingabemaske `##` ein Wert aus zwei Ziffern, bei Eingabemaske `MM` ein Wert aus einem Monatsnamen |
| `NN`, `WWWW`, `XXXX`, `YYYY`, `ZZZZ` | andere frei wählbare Variablennamen, etwa für weitere Jahres- oder Bereichswerte |

Die Unterscheidung wird an diesen Regeln deutlich:

| Eingabemaske | Eingabemuster | Ausgabemaske | Ausgabemuster | Beispiel und Wirkung |
| --- | --- | --- | --- | --- |
| `##.####` | `NN.JJJJ` | `####` | `JJJJ` | `03.2010` → `2010`: `NN` erfasst `03`, wird aber im Ausgabemuster weggelassen. |
| `##.####` | `MM.JJJJ` | `####-##` | `JJJJ-MM` | `03.2010` → `2010-03`: `MM` erfasst `03` und wird im Ausgabemuster übernommen. |
| `MM ####` | `NN JJJJ` | `####-##` | `JJJJ-NN` | `März 2010` → `2010-03`: Der Monatswert `03` wird mit `NN` erfasst und ausgegeben. |

Die zweite Regel kann ebenso `NN.JJJJ` und `JJJJ-NN` als Muster verwenden. Die Regelauswahl richtet sich nach der **Eingabemaske**, nicht nach den Variablennamen. Deshalb konkurrieren die ersten beiden Regeln trotz `NN` beziehungsweise `MM` um dieselben Eingaben und werden als Transformationskonflikt gemeldet.

Die **Ausgabemaske** legt die Stellen für die Werte fest; das **Ausgabemuster** legt fest, welcher zuvor erfasste Wert dort eingesetzt wird. Im durchgängigen Beispiel kann die Ausgabemaske auch `####-MM` lauten: Zusammen mit dem Ausgabemuster `JJJJ-NN` entsteht weiterhin `2010-03`. Die beiden `M` der Ausgabemaske markieren hier zwei Stellen für den Wert `03`; ausgegeben werden weder die Buchstaben `MM` noch der Name `März`. Für diese numerische Ausgabe ist `####-##` die leichter lesbare Ausgabemaske.

Jeder Variablenname darf im Eingabemuster nur einmal definiert sein. Maßgeblich ist der Buchstabe, unabhängig von der Länge: `JJJJ/JJJJ` und `JJJJ/JJ` sind als Eingabemuster ungültig, `JJJJ/ZZZZ` ist gültig. In der Ausgabe darf dieselbe Eingabevariable mehrfach verwendet werden, z. B. `JJJ0/JJJ9` für `195?` → `1950/1959`. Jede Verwendung muss im Eingabemuster definiert sein und darf höchstens dessen Variablenlänge haben. Buchstaben in Literaltext zählen nicht als Variablen. Führende Nullen werden als Literale ergänzt, beispielsweise `###` / `JJJ` nach `0###` / `0JJJ`.

Die gemeinsame Prüfung erfasst außerdem Pflichtfelder, fehlende Regeln oder Tests, Literal- und Längenfehler, Testwerte passend zu den Masken, ISO-Zeiträume, unbekannte Platzhalter sowie doppelte Regeln und Tests. Sie prüft die konkreten Varianten nach der Platzhalterexpansion; Fehler in bestehenden oder importierten Gruppen erscheinen mit einer Verknüpfung zur betroffenen Regel im Plausibilitätscheck.

Das Formular prüft konkrete tokenisierte Testwerte und Literalzeichen in Masken-/Musterpaaren bereits während der Eingabe. Bei einer Abweichung nennen Formular, Plausibilitätscheck und Exportbericht die Position sowie das erwartete und vorhandene Zeichen mit Unicodewert, beispielsweise `Erwartet: [–] (U+2013), vorhanden: [-] (U+002D)`. Relevante Leerzeichen werden dabei sichtbar dargestellt. Hinweise auf fehlende Testabdeckung nennen vorhandene vollständige Testvorlagen, damit sich die zugehörigen Feldfehler zuordnen lassen.

Beim Öffnen einer gespeicherten Regelgruppe erscheinen vorhandene Fehler unmittelbar an den betroffenen Feldern. „Speichern“ prüft den aktuellen Stand erneut und speichert fehlerhafte Gruppen zunächst nicht. Nach Anzeige der Fehler ermöglicht die gesonderte Aktion „Fehler akzeptieren und trotzdem speichern“ eine ausdrückliche Zustimmung. Diese gilt nur für den angezeigten Formularinhalt und die aktuellen Fehler; nach Änderungen ist eine erneute Prüfung und Zustimmung erforderlich. Die gespeicherten Fehler bleiben im Plausibilitätscheck sichtbar. Technische Bindungsfehler lassen sich nicht auf diese Weise übergehen.

Zusätzlich erscheinen unverbindliche Hinweise auf erzeugte Varianten ohne passenden Test und auf mögliche Überschneidungen unterschiedlicher Masken mit gleicher Anzahl von `#`-Stellen. Diese Hinweise verhindern das Speichern nicht. Überschneidungshinweise nennen beide konkreten Varianten mit ihren expandierten Eingabemasken und verweisen auf ihre bearbeitbaren Vorlagen. Die Überschneidungsprüfung betrachtet Ziffern und exakt übereinstimmende Literale einschließlich Leerzeichen: Normale, geschützte und schmale geschützte Leerzeichen sind verschiedene Zeichen. Sie bildet nicht die vollständige Auswahl- und Priorisierungslogik des Timeparsers nach.

Die eingetragenen Testfälle werden **nicht ausgeführt**. Der Editor prüft weder die tatsächliche Tokenisierung noch den Zusammenhang zwischen erwarteter Ausgabe und Zeitraum. Eine Integration der echten Parser-Verarbeitung ist für eine spätere gemeinsame Library vorgesehen.

Beispiel:

```text
Eingabe:        März 2010
tokenized:      MM 2010
inputMask:      MM ####
inputPattern:   NN JJJJ
outputMask:     ####-##
outputPattern:  JJJJ-NN
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

Das ausführbare JAR wird mit Tests und Packaging-Prüfung erzeugt:

```bash
mvn clean verify
java -jar target/ddblabs-timeparser-rules.jar
```

Der Dateiname `target/ddblabs-timeparser-rules.jar` enthält keine Versionsnummer. Maven verwendet dafür die `artifactId` als `finalName`; ein Wechsel der Projektversion erfordert keine Anpassung der Startbefehle oder des Dockerfiles.

### Kompatibilität mit dem Timeparser

Die CSV-Spalten, Masken-/Mustersemantik und Variablenübertragung sind mit dem benachbarten Projekt `../ddblabs-timeparser` abgeglichen. Zwei zusätzliche Einschränkungen verhindern Datenverlust beim Einlesen: Eingabemasken müssen eindeutig sein, da der Timeparser nur die erste Regel je Eingabemaske verwendet. Führende und abschließende Leerzeichen in exportierten Masken, Mustern und Testwerten werden beanstandet, da der CSV-Leser des Timeparsers diese entfernt. Innere Leerzeichen bleiben erhalten.

Eingabevariablen müssen eindeutig definiert sein; Ausgabevariablen dürfen wie im Timeparser mehrfach verwendet werden. Der Plausibilitätscheck verändert keine Bestandsdaten. Änderungen und Löschungen von Platzhaltern zeigen die betroffenen Regelgruppen an; der aktuelle Stand wird vor jedem Export erneut geprüft.

### Export mit Prüfhinweisen

Ein Export bleibt auch bei Plausibilitätsfehlern möglich. Bei Befunden zeigt die Anwendung zunächst die Hinweise und einen Link „Trotz Hinweisen herunterladen“. Alle Downloads enthalten die Header `X-Timeparser-Plausibility-Warnings` (Anzahl) und `X-Timeparser-Validation: plausibility-only`. Für automatisierte Abrufe bleibt der direkte Download mit `?download=true` verfügbar.

ZIP und CSV-Einzeldownloads verwenden dieselbe Aufbereitung: Exakte Regeldubletten werden zusammengeführt und ihre unterschiedlichen Tests auf die verbleibende Regel-ID umgehängt. Tests werden je exportierter Regel dedupliziert. Verschiedene Transformationen bei gleicher Eingabemaske bleiben mit Warnung enthalten; der Timeparser wird davon nur die erste laden. Das ZIP enthält zusätzlich `pruefhinweise.txt` mit den Befunden und den Grenzen der Prüfung.

Vorlagen mit nicht auflösbaren Platzhaltern können nicht in konkrete Zeilen übersetzt werden. Diese Regeln bzw. Tests werden im Export ausgelassen und im Prüfbericht ausdrücklich genannt. Andere fehlerhafte, aber expandierbare Zeilen bleiben entsprechend dem Exportwunsch enthalten. Die erzeugten Listen und der Prüfbericht beziehen sich innerhalb eines Downloads auf denselben Datenbankstand. Für zusammengehörige Regeln und Tests aus einem gemeinsamen Stand empfiehlt sich das ZIP; zwischen zwei getrennten Downloads können Daten geändert worden sein.

Der separate `tokens.csv`-Export dient dem Editor und ersetzt nicht die `calendar_tokens.csv` des Timeparsers. Die tatsächliche Parser-Verarbeitung soll später über eine gemeinsame Library angebunden werden.

## Konfiguration

Die wichtigsten Einstellungen sind per Environment-Variable konfigurierbar:

| Variable | Standard | Bedeutung |
| --- | --- | --- |
| `TIMEPARSER_SERVER_PORT` | `8080` | HTTP-Port der Anwendung |
| `TIMEPARSER_URL_PREFIX` | `/app/timeparser-rules` | Context Path, z. B. für `domain.de/app/timeparser-rules` |
| `TIMEPARSER_FORWARD_HEADERS_STRATEGY` | `native` | Auswertung von Reverse-Proxy-Headern; für Tomcat im Container ist `native` empfohlen, alternativ `framework` |
| `TIMEPARSER_PROXY_PROTOCOL_HEADER` | `X-Forwarded-Proto` | Header, aus dem das öffentliche Schema (`https`) gelesen wird |
| `TIMEPARSER_PROXY_PROTOCOL_HEADER_HTTPS_VALUE` | `https` | Wert, der als HTTPS-Verbindung erkannt wird |
| `TIMEPARSER_PROXY_HOST_HEADER` | `X-Forwarded-Host` | Header für den öffentlichen Hostnamen |
| `TIMEPARSER_PROXY_PORT_HEADER` | `X-Forwarded-Port` | Header für den öffentlichen Port |
| `TIMEPARSER_PROXY_REMOTE_IP_HEADER` | `X-Forwarded-For` | Header für die ursprüngliche Client-IP |
| `TIMEPARSER_DATABASE_PATH` | `timeparser-rules.sqlite` | Speicherort der SQLite-Datei |
| `TIMEPARSER_RULES_CSV_PATH` | `rules.csv` | Optionaler Pfad zur privaten `rules.csv` für den initialen Import |
| `TIMEPARSER_TESTS_CSV_PATH` | `tests.csv` | Optionaler Pfad zur privaten `tests.csv` für den initialen Import |
| `TIMEPARSER_LOG_LEVEL` | `INFO` | Root-Log-Level |
| `TIMEPARSER_SPRING_LOG_LEVEL` | Wert von `TIMEPARSER_LOG_LEVEL` | Spring-spezifisches Log-Level |
| `TIMEPARSER_THYMELEAF_CACHE` | `true` | Template-Cache; in Produktion/OpenShift aktiv lassen |
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

Der Workflow prüft Tests, JAR-Packaging und Docker-Build bei:

- Push auf `main`
- Tags im Format `v*.*.*`
- manueller Ausführung über `workflow_dispatch`
- Pull Requests nach `main`

Vor dem Docker-Build wird `mvn clean verify` ausgeführt und das erzeugte `target/ddblabs-timeparser-rules.jar` geprüft. Der mehrstufige Docker-Build bleibt unabhängig davon auch lokal mit `docker build` verwendbar und baut die Anwendung selbst.

Bei Pull Requests findet ausschließlich eine Prüfung statt; GHCR-Anmeldung und Veröffentlichung werden übersprungen. Bei Pushes und manueller Ausführung werden Images veröffentlicht. Die Tags werden aus Branch, Git-Tag und Commit-SHA erzeugt. Auf dem Default-Branch wird zusätzlich `latest` gesetzt. Der Docker-Build verwendet den GitHub-Actions-Layercache.

## Betriebshinweise

- In produktionsnahen Umgebungen sollten `TIMEPARSER_SECURITY_USERNAME` und `TIMEPARSER_SECURITY_PASSWORD` immer gesetzt werden.
- Die SQLite-Datei sollte auf ein persistentes Volume gelegt werden.
- `TIMEPARSER_THYMELEAF_CACHE` sollte in OpenShift auf `true` bleiben. `false` ist nur für lokale Template-Entwicklung sinnvoll.
- Der URL-Prefix ist bereits auf `/app/timeparser-rules` ausgelegt und kann hinter einem Reverse Proxy unverändert verwendet werden.
- Hinter einem TLS-terminierenden Reverse Proxy muss der Proxy mindestens `X-Forwarded-Proto: https` setzen. Sinnvoll sind außerdem `X-Forwarded-Host` und `X-Forwarded-Port`, damit Login- und Logout-Redirects auf die öffentliche HTTPS-URL zeigen.
- Anwendungsinterne Redirects verwenden mit `server.tomcat.use-relative-redirects=true` relative Ziele wie `/app/timeparser-rules/login`. Der Browser behält dabei das öffentliche Schema, den Host und den Port bei; aus einer HTTPS-Anfrage entsteht dadurch kein `http://…:443`-Ziel. Das funktioniert ebenso bei lokalem HTTP-Betrieb und abweichenden öffentlichen Ports.
- `server.tomcat.redirect-context-root=false` verhindert Tomcats vorzeitige Weiterleitung beim Aufruf von `/app/timeparser-rules` ohne abschließenden Schrägstrich. So wird auch dieser Einstieg erst nach der Proxy- und Sicherheitsverarbeitung behandelt. Diese Einstellung entspricht der [Spring-Boot-Empfehlung für TLS-terminierende Proxys](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server).
- Falls die Header trotz korrekter Proxy-Konfiguration nicht greifen, kann `TIMEPARSER_FORWARD_HEADERS_STRATEGY=framework` gesetzt werden. Diese Variante wertet die Forwarded Headers in Spring statt in Tomcat aus.
- HTTP-Kompression ist für HTML, CSS, JavaScript, JSON, XML und CSV aktiviert.
