# Equinox v0.6 — Step 01: Core / Output Split

Basis: letzter stabiler Step12-Quellstand.

Dieser Schritt ist absichtlich klein und build-orientiert:
- AudioQuantizer kapselt Frequenz -> ID. Die FFT ist weiterhin nur Sensor-Frontend.
- EngineOutputBridge erzeugt read-only Output-Snapshots nur bei Bedarf (lazy).
- Graph und Sonifier greifen über die Output-Schicht auf den Zustand zu.
- Bestehendes Memory-Dateiformat bleibt unverändert, damit vorhandenes Lernen erhalten bleibt.
- Keine Step13/14-API-Annahmen wurden übernommen.

Noch NICHT in diesem Schritt:
- neues Makro-only Relationsmodell
- Überaustausch
- Slot/Templates
- F..SSS..Omega Ranking
- Vollbild Live/Memory/Hybrid UI
- neuer generativer Canvas/Orchester-Renderer

Diese Punkte folgen nach erfolgreichem Build dieser Basis.

Build in Android Studio:
1. Diesen Ordner als eigenes Projekt öffnen (nicht den alten Step12-Ordner).
2. Breadcrumb/Projektpfad auf Equinox_v06_Step01_CoreSplit prüfen.
3. Build > Build APK(s).

Hinweis: In der Erstellungsumgebung ist kein Android SDK installiert; der Quellstand wurde statisch geprüft, aber hier nicht zu einer APK kompiliert.
