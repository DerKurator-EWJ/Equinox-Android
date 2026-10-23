# Equinox v0.5 — Step 02

Minimaler Audio-Härtetest auf echter Android-Hardware.

Enthalten:
- RECORD_AUDIO Runtime-Berechtigung
- AudioRecord, 48 kHz, Mono, PCM 16 Bit
- 2048-Sample-Frames
- Hann-Fenster
- Radix-2-FFT
- dominante Frequenz 40–18.000 Hz
- logarithmische Frequenz-ID: round(24 * log2(hz / 55))
- Live-Anzeige von Hz, ID, Signalstärke und Framezahl

Noch bewusst NICHT enthalten:
- persistentes Gedächtnis
- Relationen
- Makroknoten
- Kamera
- Save/Load

Ziel: Erst beweisen, dass Audioaufnahme + FFT + Quantisierung auf dem echten Telefon stabil laufen.
