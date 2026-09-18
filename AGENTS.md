# ecosystem-runtime-localbuild — agent instructions

Local/customer runtime overlay. Plugin contracts are strict (reflection-loaded). Platform source of truth is **`ecosystem-runtime`**.

## Plugins

- Follow `.cursorrules` when editing `plugin/customer`, `plugin/business`, or `plugin/reward` (globs — not always-on).
- Task: `/add-customer-plugin`
- Do not change method names or signatures: `getPrePredict`, `getPostPredict`.
- Return `JSONObject`. Preserve `params` / `final_result` structure.
- Closed loop for callers: `POST /invocations` then `POST /response` (singular) with `uuid` + `offer_name`.

## Do not

- Fork platform core for one customer; use plugins + `ecosystem.properties`.
- Treat this repo as the place to change algorithm defaults — those belong in `ecosystem-runtime` (`ECOSYSTEM_ALGORITHMS.md`).

## Cursor Cloud specific instructions

See `.cursor/CLOUD.md`. Java 17 + Maven. MongoDB/Cassandra and local `ecosystem.properties` are typically required to boot. Verify with `mvn -B test` when tests exist; do not assume Workbench2 is in this workspace.
