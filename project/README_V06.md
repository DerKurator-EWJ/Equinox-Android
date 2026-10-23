# Equinox v0.6 — Step 23: Indexed Long-Term Storage

## Ziel
Step 23 ersetzt den bisherigen monolithischen `equinox_v06_memory.json`-Checkpoint durch einen **indexierten, binären Langzeitspeicher** ohne zusätzliche Datenbank- oder Storage-Library.

Der Lernkern aus Step 22 bleibt unverändert. Die Änderung betrifft die Persistenz- und Abrufschicht.

## Neue Speicherstruktur
Im App-internen Speicher entsteht jetzt der Ordner:

```text
equinox_v06_store/
  meta.bin
  quants.bin
  macros.idx
  tokens.bin
  relations.bin
  exchange.bin
```

Dabei werden unterschiedliche Datentypen nicht mehr in einem großen JSON-Dokument zusammengefasst.

### `macros.idx`
Enthält nur kompakte Makro-Header:
- ID
- Abstraktionsstufe
- Modalität
- Gewicht
- Repräsentationsfrequenz
- erste/letzte Aktivierungsbeobachtung
- Offset und Länge der Tokenfolge
- exakte Makrosignatur

### `tokens.bin`
Enthält die eigentlichen Bestandteile der Makros als dicht gepackte 32-Bit-IDs.

Ein Makro kann deshalb im RAM existieren, **ohne dass seine komplette Tokenfolge resident sein muss**. Erst wenn eine Funktion die Bestandteile tatsächlich benötigt, liest eine `DiskTokenList` die entsprechenden Werte über den gespeicherten Offset aus `tokens.bin`.

## Lazy statt Vollmaterialisierung
Beim Start werden weiterhin kompakte Header und die kleinen Runtime-Indizes geladen. Die Makro-Tokenfolgen selbst bleiben disk-backed.

```text
Langzeitspeicher auf Flash
        ↓ Header/Index
leichter Makro-Katalog im RAM
        ↓ nur bei Zugriff
Token-Slice aus tokens.bin
        ↓
Working Set / Template / Recall / DREAM
```

Das bedeutet nicht, dass nie über den gesamten Bestand iteriert wird: beim Wiederaufbau abgeleiteter Indizes kann Equinox die Tokenfolgen einmal sequenziell vom Datenträger lesen. Sie werden dabei aber nicht als vollständiger Makrobestand dauerhaft im RAM gehalten.

## Konsistente Checkpoints
Ein Save schreibt zuerst einen vollständigen neuen Store in einen temporären Ordner. Erst wenn alle Dateien fertig sind, wird der Store atomar ausgetauscht. Danach werden existierende Makros an ihre neuen Token-Offets gebunden.

Damit wird vermieden, dass ein halbfertiger Checkpoint den aktuellen Langzeitspeicher überschreibt.

## Automatische Migration
Wenn beim ersten Start noch ein Step-22/älterer

```text
equinox_v06_memory.json
```

existiert und noch kein Indexed Store vorhanden ist, wird die JSON-Struktur einmal geladen, direkt in das neue Binärformat geschrieben und anschließend als

```text
equinox_v06_memory.migrated.json
```

zur Seite gelegt.

Bestehendes Lernen wird dadurch nicht absichtlich verworfen.

## Save Slots
Save Slots sind jetzt ZIP-Archive statt einzelner JSON-Dateien. Sie enthalten:
- den kompletten `equinox_v06_store`-Ordner
- das adaptive Audio-Codebook
- das adaptive Visual-Codebook

Dadurch gehört der gelernte Quantisierer wieder zum gespeicherten Gedächtnisstand.

## Diagnostik
Im LAB wird zusätzlich angezeigt:
- Storage-Backend
- Größe des Indexed Stores
- Anzahl disk-backed Makros
- Anzahl tatsächlicher Token-Lesezugriffe

Die Langzeitdiagnostik misst nun die Größe des gesamten Store-Ordners statt der alten JSON-Datei.

## Was Step 23 bewusst noch nicht macht
- Kein Remote-/Cloud-Speicher.
- Keine semantische Änderung der Makros.
- Kein neues Lernverfahren.
- Kein vollständiges Paging sämtlicher Header aus RAM; kompakte Makro-Metadaten bleiben verfügbar.
- Kein externer SQL/Room-Stack.

Der Hauptgewinn dieses Steps ist: **große variable Token-Payloads und der monolithische JSON-Parse verschwinden aus dem normalen Startpfad.**

## Version
- `versionCode = 37`
- `versionName = 0.6-step23-indexed-long-term-storage`

## Build/Test
Der neue Storage-Core und `MemoryModel` wurden mit `kotlinc` gegen kleine Android/JSON-Stubs kompiliert. Zusätzlich wurde der Binärstore als JVM-Test geschrieben, neu geöffnet und lazy über Token-Offets gelesen.

Ein vollständiger Android-Gradle/APK-Build ist in dieser Umgebung weiterhin nicht möglich, da kein Android-SDK installiert ist.
