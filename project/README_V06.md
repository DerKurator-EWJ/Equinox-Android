# Equinox v0.6 — Step 24: Long-Term Hard Test

Step 24 does **not** add another learning rule. It adds the measurement layer needed to test whether the current architecture actually behaves better over long runs.

## What is measured

Every hard-test sample records:

- total observations
- persistent quant/code count
- macro count and macro-relation count
- crossmodal macro count
- recent adaptive-audio novelty rate
- recent adaptive-vision novelty rate
- current hierarchy compression gain
- cumulative prediction checks/hits
- cumulative memory-competition pruning
- indexed-store bytes
- number of disk-backed macros
- lazy token-read count
- current Java heap use

The app derives useful interval metrics from those snapshots, including:

- macro growth per 10,000 observations
- early vs. recent macro-growth rate
- storage growth per 10,000 observations
- prediction hit rate during the test interval
- compression level
- pruning activity
- heap/storage deltas

This is intentionally observational. A falling macro-growth or novelty rate is evidence of convergence only in context; it is not hard-coded as proof that the system has learned meaning.

## LAB controls

`TEST START 5MIN`

Starts a new controlled session, clears the previous hard-test sample series, captures an initial baseline, then samples automatically every five minutes while the Activity remains alive.

`SAMPLE`

Captures an additional point immediately.

`STOP`

Stops automatic sampling while preserving the captured series.

`CSV`

Writes `equinox_v06_hard_test.csv` into the app's internal files directory.

`RESET`

Deletes the hard-test series only. It does **not** delete Equinox memory.

## Practical test protocol

For a meaningful first test, let LIVE or HYBRID run for several hours in a reasonably repetitive environment. Do not judge the system from a single sample. Look for trends:

1. adaptive audio/visual novelty should usually become lower in familiar conditions;
2. macro creation per 10k observations should eventually stop accelerating indefinitely;
3. compression should remain positive or improve rather than collapse;
4. prediction should accumulate enough checks to become interpretable;
5. memory competition should remove weak material under pressure rather than allowing unbounded useless growth;
6. heap use should not grow in lockstep with the entire persistent store;
7. the indexed store may grow, but its growth per observation should become measurable and comparable across runs.

A changing environment can legitimately raise novelty again, so the hard test records the measurements rather than imposing a universal pass/fail threshold.

## Storage

The Step-23 indexed long-term store remains unchanged. Hard-test measurements live separately in:

- `equinox_v06_hard_test.json`
- `equinox_v06_hard_test.csv` (only after export)

Existing Step-23 memory remains compatible.

## Build

Open the folder `Equinox_v06_Step24_LongTermHardTest` directly in Android Studio and build the APK as before.
