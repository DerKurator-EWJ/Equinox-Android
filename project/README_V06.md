# Equinox v0.6 — Step 22: Autonomous Memory Dream

## Ziel
Step 22 erweitert den bisherigen MEMORY-Modus um einen zweiten, vollständig **read-only autonomen DREAM-Lauf**. Statt immer nur dem stärksten gelernten Pfad zu folgen, kann Equinox während eines internen Laufs zwischen mehreren bereits gelernten Strukturarten wechseln.

Der DREAM-Modus erzeugt **keine neuen Erfahrungen**. Kamera und Mikrofon bleiben aus; Beobachtungszähler, Makrogewichte, Relationsgewichte, Kandidaten und persistentes Überaustausch-Gedächtnis werden nicht verändert.

## Vier mögliche Traum-Sprünge
Ein aktueller Makroknoten kann seinen nächsten internen Fokus aus vier Quellen wählen:

1. **PREDICTION** — bereits gelernte gerichtete Folgemakros aus Step 15.
2. **RELATION** — starke lokale Nachbarn aus dem sparsamen Relationsindex.
3. **SUBSTITUTION** — Makros, die in mehreren gelernten Template-Rollen strukturell austauschbar waren.
4. **CROSSMODAL** — Sprünge zwischen einem Audio-/Visual-Makro und einem bereits gelernten gemeinsamen Crossmodal-Makro; von einem Joint-Makro kann DREAM wieder zu seinen sensorischen Bestandteilen zurückspringen.

Damit bleibt die Suche lokal. Für Crossmodalität gibt es jetzt zusätzlich einen `member -> joint macro`-Index; DREAM muss nicht bei jedem Schritt alle Crossmodal-Makros durchsuchen.

## Reproduzierbare Exploration
DREAM nimmt nicht immer stumpf den höchsten Score. Eine deterministische Explorationsfolge bevorzugt abwechselnd Prediction, Relation, Crossmodalität und Substitution. Kürzlich besuchte Knoten werden vorübergehend abgewertet, damit kurze Schleifen weniger wahrscheinlich werden.

Die Auswahl berücksichtigt zusätzlich die bereits vorhandene strukturelle Relevanz, den Rang und die Nähe der Abstraktionsstufen. Es wird kein Zufall in das persistente Gedächtnis geschrieben.

## Output
Der gewählte interne Fokus materialisiert nur ein temporäres Working Set:

```text
Langzeitgedächtnis
      ↓ read-only
Prediction / Relation / Substitution / Crossmodal
      ↓
Autonomous DREAM Working Set
      ↓
Überaustausch (nur transient)
      ↓
Leinwand + Generative Orchestra
```

Crossmodal-Joint-Makros können ihre beiden gelernten Komponenten ebenfalls als Kontext auf die Leinwand bzw. ins Orchester holen. Auch das ist nur Ausgabe und keine neue Crossmodal-Evidenz.

## UI
Auf der MEMORY-Seite gibt es jetzt zusätzlich:

- `DREAM RUN`
- `DREAM STEP`
- `DREAM STOP`
- einen eigenen DREAM-Status
- DREAM-Diagnostik in den Memory-Statistiken

Der normale MEMORY-Lauf bleibt erhalten. MEMORY und DREAM laufen bewusst nicht gleichzeitig.

## Diagnostik
Die Statistiken zählen separat:

- DREAM-Schritte
- Prediction-Hops
- Relations-Hops
- Substitution-Hops
- Crossmodal-Hops
- Restarts
- zuletzt gewählte Übergangsart

Diese Zähler sind nur Laufzeitdiagnostik und werden nicht als Wissen interpretiert.

## Read-only-Härtetest
Der Core wurde mit einer künstlich wiederkehrenden Sequenz getestet. Nach 30 DREAM-Schritten blieben Beobachtungszahl, Makroanzahl und persistente Relationsanzahl unverändert, während DREAM intern weiterlief und zwischen gelernten Pfaden wechselte.

## Technische Änderungen
- `MemoryModel.autonomousDreamStep()`
- lokaler DREAM-Cursor und kurzer Recent-History-Puffer
- gewichtete, deterministisch explorative Übergangsauswahl
- Crossmodal-Member-Index für lokalen Joint-Abruf
- DREAM-Zähler in `MemoryStats`
- DREAM-Steuerung auf der MEMORY-Seite
- `versionCode = 36`
- `versionName = 0.6-step22-autonomous-memory-dream`

## Build
Wie bei den vorherigen Steps ist das Projekt für Android Studio vorgesehen. In dieser Umgebung wurde der neue Memory-Core mit `kotlinc` gegen Stubs kompiliert und der read-only DREAM-Lauf als JVM-Test ausgeführt. Ein vollständiger Android-Gradle/APK-Build ist hier weiterhin nicht möglich, weil kein Android-SDK installiert ist.
