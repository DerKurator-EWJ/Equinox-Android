# Step 34 — Performance Governor

Step 34 adds a resource governor around the already-adaptive Equinox engine. It does **not** alter learning semantics or persistent memory.

The governor watches recent GPU render cost, camera/analysis cost, dropped-frame ratio and Android thermal pressure. With hysteresis it chooses one of four transient profiles:

- **ECO** — 3 visible fields / 2 visible links, ~55% video work, ~58% visual-learning work, at least 66 ms video interval.
- **BALANCED** — 5 fields / 3 links, ~78% video and learning work, at least 50 ms interval.
- **HIGH** — full 7 fields / 5 links at current reference budgets.
- **ULTRA** — same safe ES2 uniform count, but higher adaptive camera sampling and learning work on cool, fast devices.

Important: the fixed 7/5 numbers are only the conservative **GPU ES 2.0 shader uniform window**, not memory limits. The working set, long-term memory, IDs and abstraction depth remain adaptive/open as established in Step 25.

Quality changes affect only the amount of transient work materialized for the current frame. IDs, macros, relations, RPG tiers, relevance and stored history are never deleted or rewritten by the governor.

The Live-Sync line in Settings now shows `Governor ECO/BALANCED/HIGH/ULTRA` plus the current visible field/link budget.
