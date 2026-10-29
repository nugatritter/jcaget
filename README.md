# jcaget

Einfache `caget`/`camonitor`/`caput`/`cainfo`-Werkzeuge in Java für EPICS-Variablen (PVs) per Channel Access.

- Package: `de.helmholtzberlin.jcaget`
- Klassen: `Main` (Einstieg der JAR), `JCaGet` (lesen), `JCaMonitor` (Änderungen beobachten), `JCaPut` (schreiben),
  `JCaInfo` (alle Informationen zu einer PV)
- Channel-Access-Client: reine Java-Implementierung (CAJ, enthalten in `org.epics:jca:2.4.12`), keine nativen Bibliotheken nötig

## Voraussetzungen

- Java 11 oder neuer
- Zum Bauen: Maven

## Bauen

```
mvn package
```

Ergebnis ist die ausführbare JAR `target/jcaget-1.1.0.jar` mit allen Abhängigkeiten.
(`target/original-jcaget-1.1.0.jar` enthält keine Abhängigkeiten und ist nicht zur Weitergabe gedacht.)

## Verwendung

```
java -jar jcaget-1.1.0.jar [get] <PVName> [PVName ...]
```

Beispiel:

```
$ java -jar jcaget-1.1.0.jar SOME:PV:NAME
SOME:PV:NAME  2026-10-27 14:45:49.123  3.14          NO_ALARM      NO_ALARM
```

`get` und `monitor` geben pro Zeile aus: PV-Name, Zeitstempel (lokale Zeit, ms), Wert, Status und Severity.
Enum-PVs erscheinen mit ihrem Text statt der Zahl. Bei Array-PVs steht die Elementanzahl in eckigen Klammern vor den Werten,
z. B. `[3] 1.0 2.0 3.0`; ab 10 Elementen werden nur die ersten 10 gezeigt (`[100] 1 2 ... 10 ...`).

### Farben

Ist die Ausgabe ein Terminal, werden Zeilen mit Alarm (Status oder Severity ≠ `NO_ALARM`) farbig hervorgehoben (ANSI-Farben, fett):
`MINOR` gelb, `MAJOR` rot, `INVALID` magenta. Zeilen ohne Alarm bleiben ungefärbt.

- Bei Umleitung in eine Datei oder Pipe wird nicht gefärbt; die Umgebungsvariable `NO_COLOR` schaltet die Farben ebenfalls ab.
- Erzwingen oder abschalten: `java -jar jcaget-1.1.0.jar --color=always|never|auto monitor <PVName>`
- Linux/macOS-Terminals und Windows Terminal (Windows 10/11) unterstützen ANSI-Farben. Im alten Windows-`cmd`-Fenster (conhost)
  erscheinen sie ggf. als Steuerzeichen; dann `--color=never` verwenden.

### jcamonitor

Gibt jede Wertänderung aus, bis das Programm mit Strg+C beendet wird:

```
java -jar jcaget-1.1.0.jar monitor <PVName> [PVName ...]
```

### jcaput

Schreibt einen Wert (bei Array-PVs mehrere, durch Leerzeichen getrennt). Die Umwandlung in den Typ des PVs übernimmt Channel Access:

```
java -jar jcaget-1.1.0.jar put <PVName> <Wert> [Wert ...]
```

### jcainfo

Zeigt alles, was Channel Access über eine PV liefert, übersichtlich als Block pro PV (mehrere PVs durch Leerzeile getrennt):

```
java -jar jcaget-1.1.0.jar info <PVName> [PVName ...]
```

Beispiel:

```
$ java -jar jcaget-1.1.0.jar info SOME:AI SOME:VALVE
SOME:AI  (ai)  "Strahlstrom"
    Value:         390 mA
    Alarm:         HIHI_ALARM  MAJOR_ALARM
    Timestamp:     2026-10-29 11:20:15.949  (2 min ago)
    Access:        read only
    Display range: 0 .. 500 mA
    Control range: 0 .. 450 mA
    Alarm limits:  LOW 1  HIGH 350  HIHI 380
    Precision:     3
    Type:          DBF_DOUBLE
    Host:          ioc01.example.org

SOME:VALVE  (mbbi)  "Ventil"
    Value:         Auf
    Alarm:         NO_ALARM
    Timestamp:     2026-10-29 11:20:15.949  (2 min ago)
    Access:        read, write
    States:        0=Zu  [1=Auf]  2=Fehler
    Type:          DBF_ENUM
    Host:          ioc01.example.org
```

| Zeile | Inhalt |
| ----- | ------ |
| Kopfzeile | PV-Name (im Terminal fett), Record-Typ (`RTYP`) und Beschreibung (`DESC`) des zugehörigen Records |
| `Value` | Wert mit Einheit; Arrays wie bei `get` (Anzahl in eckigen Klammern, höchstens 10 Elemente) |
| `Alarm` | Status und Severity, farbig wie bei `get`; ohne Alarm nur `NO_ALARM` |
| `Timestamp` | Zeitstempel (lokale Zeit) und Alter; `undefined`, wenn der Record noch nie verarbeitet wurde |
| `Access` | Zugriffsrechte für den aktuellen Benutzer und Rechner: `read, write`, `read only`, `write only` oder `none` |
| `States` | Bei Enum-PVs alle Zustände mit Index, der aktuelle in eckigen Klammern; lange Listen werden umbrochen |
| `Display range`, `Control range` | Anzeige- und Stellbereich mit Einheit |
| `Alarm limits` | Gesetzte Alarmgrenzen (`LOLO`, `LOW`, `HIGH`, `HIHI`); die Grenze, die den aktuellen Alarm auslöst, ist farbig |
| `Precision` | Anzahl Nachkommastellen (nur bei Gleitkomma-PVs) |
| `Type` | Nativer Datentyp wie bei `cainfo` (`DBF_DOUBLE`, `DBF_LONG`, `DBF_ENUM`, ...), bei Arrays mit Elementanzahl |
| `Host` | Server (IOC), der die PV bereitstellt |

Was nicht vorhanden oder nicht konfiguriert ist, wird weggelassen: z. B. Bereiche mit Unter- gleich Obergrenze, nicht gesetzte
Alarmgrenzen, eine leere Beschreibung oder Einheiten bei String-PVs. Ohne Leserecht erscheint `Value: *** no read access`;
die übrigen Angaben zur Verbindung werden trotzdem gezeigt.

Record-Typ und Beschreibung werden über die Felder `<Record>.RTYP` und `<Record>.DESC` gelesen (bei `SOME:AI.EGU` also von
`SOME:AI`). Server ohne Records (z. B. reine CA-Server in Java oder Python) bieten diese Felder nicht an; dann fehlt die Angabe
in der Kopfzeile und die Ausgabe dauert höchstens 1 s länger.

### Exit-Codes (gilt für alle Programme)

| Code | Bedeutung |
|------|-----------|
| 0    | Erfolgreich (alle PVs erreicht) |
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
String line = de.helmholtzberlin.jcaget.JCaGet.get("SOME:PV:NAME");    // formatierte Zeile wie oben
String block = de.helmholtzberlin.jcaget.JCaInfo.info("SOME:PV:NAME"); // mehrzeiliger Block wie bei info
```

## Einschränkungen

- `get` und `monitor` zeigen keine Einheit und keine Grenzen; dafür `info` verwenden.
- Timeout ist fest auf 5 Sekunden eingestellt (`TIMEOUT_SECONDS` in `JCaGet.java`, `JCaPut.java` und `JCaInfo.java`).
