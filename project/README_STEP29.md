# Step 29 — Stable Live / Tissue Canvas / Bottom Controls

## Änderungen
- LIVE: globalen zeitgetriebenen Wackel-/Sinus-Warp entfernt.
- LIVE: nur noch lokale, zustandsgebundene Verzerrung durch aktive Equinox-Felder.
- CANVAS: kein dominanter Hub-and-Spoke-Plot mehr. Gelernte Relationen bilden lokale Inseln/Fasergewebe.
- CANVAS: Overexchange ist nur noch eine sparsame Overlay-Schicht; stärkste Austausche werden hervorgehoben, statt die ganze Leinwand zu dominieren.
- CANVAS: relationale Komponenten werden renderer-seitig als mehrere lokale Felder angeordnet; keine Koordinaten gehen zurück in den Core.
- UI: CANVAS / LIVE / Einstellungen nach unten verschoben.
- UI: Control-Bar berücksichtigt Android-Navigations-Inset und bleibt oberhalb der Systemtasten.

## Hinweis
Die neue Canvas-Anordnung ist reine Ausgabe. Der Equinox-Core, das Langzeitgedächtnis und die gelernte Relationsstruktur werden dadurch nicht verändert.
