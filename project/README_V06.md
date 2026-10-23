# Equinox v0.6 — Step 20: Memory-Driven Video Engine

## Ziel
Step 20 schließt erstmals die visuelle Output-Schleife auf dem realen Kamerabild. Die Kamera ist nicht mehr nur Sensor für Visual-IDs: Ein zweiter, **nicht persistierter** Farbstrom dient als Leinwandmaterial. Der gelernte Core bleibt davon getrennt.

## Pipeline

```text
Kamera
  ├─ 8×6 langsam → Adaptive Visual IDs → Makros / Memory
  └─ 48×27 ~11 fps → reines RGB-Pigment → MemoryVideoView
                                           ↑
Core → Working Set / Recall / Prediction / Überaustausch / Relevanz
```

Der 48×27-Strom wird **nicht** gespeichert, quantisiert oder als zweites Gedächtnis verwendet. Er ist nur Output-Material.

## Was das Bild beeinflusst
- aktuelle Makroaktivierung
- aus dem Gedächtnis geholte Erwartung
- Predictive Recall
- strukturelle Relevanz
- Betrag und Vorzeichen des Überaustauschs
- lokale aktive Makrofelder als reine Renderer-Koordinaten

Dadurch kann derselbe Kameraframe bei unterschiedlichem gelerntem Kontext anders aussehen. Der Renderer schreibt nichts zurück in `MemoryModel`.

## LIVE vs HYBRID
- **LIVE VIDEO**: Verformung durch den gerade tatsächlich aktiven Working-State.
- **HYBRID VIDEO**: derselbe Kamerastrom, aber Recall/Prediction können zusätzlich den Working-State beeinflussen.

Der alte abstrakte State-Canvas bleibt darunter als Debug-/Interpretationsansicht erhalten.

## Performance
Der Video-Prototyp sampelt nur 48×27 = 1296 Farbzellen und zeichnet diese per Android Canvas. Das ist bewusst klein und robust. Ein späterer echter Shader-/OpenGL-Renderer kann dieselben read-only State-Werte bei voller Auflösung verwenden.

## Wichtig
Das ist **keine Behauptung**, dass das Bild eine andere physikalische Realität zeigt. Es ist eine audiovisuelle Projektion des aktuellen, erfahrungsabhängigen Equinox-Zustands auf Live-Sensordaten.
