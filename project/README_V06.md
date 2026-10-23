# Equinox v0.6 — Step 21: Generative Orchestra

## Ziel
Step 21 bringt die Audio-Ausgabe auf denselben konzeptionellen Stand wie die Memory-Video-Engine: Der aktuelle Equinox-Zustand wird nicht mehr nur als einige Sinustöne projiziert, sondern als **dynamisches, vollständig synthetisiertes Orchester** interpretiert.

Es gibt weiterhin **keine Sound-Library, keine Samples und keine Audiodateien als Material**. Jeder hörbare Samplewert entsteht zur Laufzeit aus Oszillatoren, Hüllkurven, FM/Detune, Makro-Mitgliedsfrequenzen und deterministischem Rauschen.

## Read-only Output

```text
MemoryModel
   ↓ read-only
SonificationSnapshot + WorkingSetSnapshot
   ↓
GenerativeOrchestraSceneBuilder
   ↓
OrchestraScene
   ↓
GenerativeOrchestraSynth
   ↓
AudioTrack
```

Das Orchester schreibt nichts zurück ins Gedächtnis. Lernen und Klang bleiben getrennt.

## Vier Sektionen aus der Abstraktionshierarchie
Die vorhandenen Makrolevel werden nur für die Ausgabe als Orchesterrollen gelesen:

- **L1 → PULSE**: kurze, rhythmische Transienten
- **L2 → MOTIF**: bewegte Motive / Arpeggien aus den Mitgliedsfrequenzen
- **L3 → HARMONY**: langsamere harmonische Flächen
- **L4+ → DRONE**: tiefe, stabile Fundamente

Das ist keine neue semantische Bedeutung im Core; es ist nur ein Decoder für Audio-Output.

## Was hörbar wird
- **Aktivierung** → unmittelbare Präsenz
- **Erwartung / Predictive Recall** → leise Vorahnung vor dem nächsten Puls
- **Überaustausch** → Spannung, FM und feines Detuning
- **strukturelle Relevanz + Rang** → Präsenz und harmonische Dichte
- **Generality** → offenere/rauschigere Klangfarbe
- **Crossmodal Links** → zusätzliche spektrale Helligkeit/Breath
- **Makro-Mitgliedsfrequenzen** → Akkord-/Motivmaterial statt nur einer Repräsentativfrequenz

## Generatives Tempo
Das Tempo wird nicht als gespeicherte Eigenschaft eines Knotens behandelt. Es ist ausschließlich ein Output-Parameter und wird aus aktueller Aktivitätsdichte, Prediction und Relationstension abgeleitet. Typischer Bereich: ca. 42–138 BPM.

## Continuous Orchestra
Der bisherige Continuous-I/O-Pfad benutzt jetzt den neuen generativen Synthesizer. Mikrofon kann parallel weiterlaufen. `suppressionFrequencies()` enthält zusätzlich wichtige Makro-Mitgliedsfrequenzen und Harmonische, damit der Step-17-Eigenklangfilter weiter funktioniert.

## Memory Orchestra
`MEMORY ALS ORCHESTER SPIELEN` rendert denselben Orchester-Decoder als kurze 8-Sekunden-Performance aus dem aktuellen Memory-State. Dadurch verwenden LIVE/HYBRID und MEMORY denselben Klanggedanken statt zwei voneinander unabhängiger Synthesen.

## Technische Änderungen
- neu: `GenerativeOrchestraSynth.kt`
- `ContinuousOrchestraEngine.kt` auf generatives Scene/Synth-Modell umgestellt
- `OrchestraSonifier.kt` verwendet denselben Synthesizer
- UI-Texte auf **Generative Orchestra** umgestellt
- `versionCode = 35`
- `versionName = 0.6-step21-generative-orchestra`

## Teststatus
Die neue reine Synthese-/Scene-Schicht wurde mit `kotlinc` kompiliert und mit einem synthetischen Working Set ausgeführt. AudioTrack-Komponenten wurden separat gegen Android-Stubs kompiliert. Ein kompletter Gradle/APK-Build ist in der Erstellungsumgebung mangels Android-SDK weiterhin nicht möglich.
