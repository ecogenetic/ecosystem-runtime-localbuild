---
name: add-customer-plugin
description: Adds a customer plugin in the localbuild overlay without breaking reflection contracts. Invoke with /add-customer-plugin.
disable-model-invocation: true
---

# Add a localbuild plugin

Platform source of truth is **`ecosystem-runtime`**. This repo is an overlay.

1. Keep `getPrePredict` / `getPostPredict` signatures frozen. Return `JSONObject`.
2. Do not fork platform core for one customer — plugins + properties only.
3. Algorithm defaults belong in `ecosystem-runtime` (`ECOSYSTEM_ALGORITHMS.md`), not here.
4. Closed loop for callers: `/invocations` then `/response` with `uuid` + `offer_name`.
