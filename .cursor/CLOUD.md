# Cursor Cloud / Online

Platform source of truth is **`ecosystem-runtime`**. This repo is a plugin overlay.

**Does apply on the same account:** User Rules in Settings; Team Rules.

**Not in the Cloud VM:** `~/.cursor/rules`, unsynced skills, local MCP, sibling runtime checkout unless multi-repo.

Verify with `mvn -B test` when tests exist. Do not boot without properties/Mongo secrets.
