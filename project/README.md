# Equinox v0.6 — Step 02: Quant Alphabet + Macro Memory + Lazy Activation

Basis: v0.6 Step 01 Core/Output Split.

## Was sich jetzt wirklich geändert hat

- **Level 0 ist nur noch ein Quant-Alphabet.** Audio- und Kamera-IDs werden erkannt und gezählt, aber zwischen Grundquanten wird **kein persistenter Relationsgraph** mehr aufgebaut.
- **Gedächtnis beginnt bei wiederkehrenden Mustern.** Kurze Folgen von 2–6 Tokens sammeln zunächst nur temporäre Evidenz. Erst bei wiederholtem Auftreten werden sie zu persistenten Makroknoten.
- **Relationen existieren nur zwischen Makroknoten.** Das verhindert den alten Mikro-Relationsgraphen.
- **Lazy / event-driven.** Pro neuem Token werden nur die aktuellen kurzen Stream-Enden geprüft. Es gibt keinen Scan über alle gespeicherten Makros.
- **Rekursive Abstraktion bis L4.** Wird ein Makro erkannt, wird seine ID als einzelnes Token in die nächste Ebene gespeist. So können Muster aus Makros wiederum Makros werden.
- **Audio und Vision bleiben in Step 02 getrennte Streams.** Crossmodal-Bindung kommt später wieder gezielt hinein.
- **Neue Memory-Datei `equinox_v06_memory.json`.** Der alte Step12/Step01-Graph wird absichtlich nicht als neues Modell uminterpretiert.

## Warum das kleiner rechnet

Die Arbeit pro Input hängt primär von der kleinen aktiven Tail-Länge (max. 6) und den maximal vier Abstraktionsebenen ab. Gespeicherte Makros werden direkt über ihre Signatur adressiert. Das gesamte Gedächtnis muss nicht permanent berechnet werden.

## Noch nicht enthalten

- Doppelrekursiver Überaustausch / Spiegelmatrix
- mehrdimensionale Gewichte (Spezifität, Entropie usw.)
- Template-/Slot-Erkennung
- F → SSS → Ω+n Ranking
- neuer Vollbild Live/Memory/Hybrid Output
- neuer Canvas/Orchester-Generator
- neuer direkter Audioencoder ohne FFT

## Build

1. Diesen Ordner als eigenes Projekt in Android Studio öffnen.
2. Prüfen, dass der Projektpfad `Equinox_v06_Step02_QuantMacroLazy` ist.
3. `Build > Build APK(s)`.
4. Wegen gleicher Application-ID aktualisiert die APK die vorhandene App, sofern derselbe Signing-Key verwendet wird.

Hinweis: In der Erstellungsumgebung ist kein Android SDK installiert. Der Quellstand wurde statisch geprüft, aber hier nicht als APK kompiliert.

## Step 02 fixed build note
Fixed the Kotlin named-argument mismatch in `MemoryModel.load()`: `patternSignature()` accepts `level`, not `streamLevel`.
