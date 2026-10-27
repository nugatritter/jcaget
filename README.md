# jcaget

Einfache `caget`/`camonitor`/`caput`-Werkzeuge in Java für EPICS-Variablen (PVs) per Channel Access.

- Package: `de.helmholtzberlin.jcaget`
- Klassen: `Main` (Einstieg der JAR), `JCaGet` (lesen), `JCaMonitor` (Änderungen beobachten), `JCaPut` (schreiben)
- Channel-Access-Client: reine Java-Implementierung (CAJ, enthalten in `org.epics:jca:2.4.12`), keine nativen Bibliotheken nötig

## Voraussetzungen

- Java 11 oder neuer
- Zum Bauen: Maven

## Bauen

```
mvn package
```

Ergebnis ist die ausführbare JAR `target/jcaget-1.0.0.jar` mit allen Abhängigkeiten.
(`target/original-jcaget-1.0.0.jar` enthält keine Abhängigkeiten und ist nicht zur Weitergabe gedacht.)

## Verwendung

```
java -jar jcaget-1.0.0.jar [get] <PVName> [PVName ...]
```

Beispiel:

```
$ java -jar jcaget-1.0.0.jar SOME:PV:NAME
SOME:PV:NAME  2026-10-27 14:45:49.123  3.14          NO_ALARM      NO_ALARM
```

`get` und `monitor` geben pro Zeile aus: PV-Name, Zeitstempel (lokale Zeit, ms), Wert, Status und Severity.
Enum-PVs erscheinen mit ihrem Text statt der Zahl. Bei Array-PVs steht die Elementanzahl in eckigen Klammern vor den Werten,
z. B. `[3] 1.0 2.0 3.0`; ab 10 Elementen werden nur die ersten 10 gezeigt (`[100] 1 2 ... 10 ...`).

### Farben

Ist die Ausgabe ein Terminal, werden Zeilen mit Alarm (Status oder Severity ≠ `NO_ALARM`) farbig hervorgehoben (ANSI-Farben, fett):
`MINOR` gelb, `MAJOR` rot, `INVALID` magenta. Zeilen ohne Alarm bleiben ungefärbt.

- Bei Umleitung in eine Datei oder Pipe wird nicht gefärbt; die Umgebungsvariable `NO_COLOR` schaltet die Farben ebenfalls ab.
- Erzwingen oder abschalten: `java -jar jcaget-1.0.0.jar --color=always|never|auto monitor <PVName>`
- Linux/macOS-Terminals und Windows Terminal (Windows 10/11) unterstützen ANSI-Farben. Im alten Windows-`cmd`-Fenster (conhost)
  erscheinen sie ggf. als Steuerzeichen; dann `--color=never` verwenden.

### jcamonitor

Gibt jede Wertänderung aus, bis das Programm mit Strg+C beendet wird:

```
java -jar jcaget-1.0.0.jar monitor <PVName> [PVName ...]
```

### jcaput

Schreibt einen Wert (bei Array-PVs mehrere, durch Leerzeichen getrennt). Die Umwandlung in den Typ des PVs übernimmt Channel Access:

```
java -jar jcaget-1.0.0.jar put <PVName> <Wert> [Wert ...]
```

### Exit-Codes (gilt für alle drei Programme)

| Code | Bedeutung |
|------|-----------|
| 0    | Wert erfolgreich gelesen |
| 1    | Timeout (PV nicht erreichbar, 5 s) oder Channel-Access-Fehler |
| 2    | Falscher Aufruf (falsche Anzahl Argumente) |

### Netzwerk-Konfiguration

Die Suche nach IOCs wird über die üblichen EPICS-Umgebungsvariablen gesteuert, z. B.:

```
set EPICS_CA_ADDR_LIST=192.168.1.255
set EPICS_CA_AUTO_ADDR_LIST=NO
```

## Verwendung als Bibliothek

Die JAR kann auch als Abhängigkeit genutzt werden:

```java
String line = de.helmholtzberlin.jcaget.JCaGet.get("SOME:PV:NAME"); // formatierte Zeile wie oben
```

## Einschränkungen

- Keine Einheit und keine Anzeigegrenzen in der Ausgabe.
- Timeout ist fest auf 5 Sekunden eingestellt (`TIMEOUT_SECONDS` in `JCaGet.java`).
