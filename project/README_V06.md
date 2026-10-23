# Equinox v0.6 — Step 03: Active Working Set + Lazy Retrieval + Überaustausch

Basis: der korrigierte Step 02 (`QuantMacroLazy_fixed`). Dieser Schritt baut die neue Laufzeitlogik auf, ohne den funktionierenden Audio-/Kamera-/UI-Pfad neu zu schreiben.

## Neu in Step 03

### 1. Begrenztes Working Set
Das persistente Gedächtnis rechnet nicht permanent komplett. Nur aktuell erkannte Makros und wenige aus ihrem bekannten Umfeld abgerufene Makros werden kurzfristig materialisiert.

- maximal 48 aktive Makros
- aktive Zustände verfallen automatisch
- Working-Set-Zustände werden nicht dauerhaft gespeichert

### 2. Indexed Lazy Retrieval
Makrorelationen bekommen einen lokalen Index. Bei einem Live-Ereignis werden nur bis zu 6 starke bekannte Nachbarn des gerade aktiven Makros abgerufen.

Es gibt keinen Vollscan über alle gespeicherten Makros oder Relationen für die Live-Reaktion.

### 3. Erster Überaustausch
Für Step 03 sind A und B bewusst operational definiert:

- `A_i` = aktuelle/live Aktivierung eines Makros
- `B_i` = aus bekannten Relationen abgerufene Kontext-Erwartung
- `R_ij = A_i * B_j - B_i * A_j`

Der Überaustausch wird nur innerhalb der kleinen gerade aktiven Frontier berechnet. Ein geglätteter Langzeitwert wird pro Makropaar gespeichert.

Das ist noch keine physikalische Interpretation der Spiegelmatrix, sondern ein testbarer Informationsmechanismus.

### 4. Output-State erweitert
`EngineOutputState` enthält jetzt zusätzlich einen read-only `WorkingSetSnapshot` mit:

- aktiven Makros
- tatsächlicher Aktivierung
- Erwartung
- Exchange-Bias
- gespeicherten Überaustauschwerten

Der vorhandene Renderer muss diese Daten noch nicht verwenden; sie stehen ab jetzt sauber für spätere Live-/Memory-Ausgabe bereit.

### 5. Sichtbare Diagnose
Im bestehenden UI erscheint zusätzlich:

`Working Set: ... aktiv | ... erinnert | Überaustausch: ... Paare | Ø|R| ...`

Damit lässt sich auf dem Telefon prüfen, ob der neue Mechanismus tatsächlich nur lokal arbeitet.

## Speicher-Kompatibilität
Step 03 verwendet weiterhin `equinox_v06_memory.json`. Ein Step-02-Gedächtnis kann geladen werden; die neuen Überaustauschdaten fehlen dann zunächst und entstehen erst durch weitere Beobachtungen.

## Build
Projektordner direkt in Android Studio öffnen:

`Equinox_v06_Step03_ActiveLazyExchange`

Dann `Build > Build APK(s)`.

Version: `0.6-step03-active-lazy-exchange` / VersionCode 17.

## Nächster logischer Schritt
Wenn dieser Build stabil läuft: Step 04 — Output-Engine. Live-Modus bekommt einen echten read-only Scene/Audio State; Visualisierung und Klang reagieren auf Aktivierung, Erwartung und Überaustausch, ohne das Gedächtnis selbst zu verändern.
