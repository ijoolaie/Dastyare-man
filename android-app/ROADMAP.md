
## 2026-11-14 — Voice chat diagnostics and SQLite lifecycle
- Bumped Android app to 1.36 (versionCode 37).
- Voice chat now records a safe failure stage (`save_user_turn`, `build_request`, `open_connection`, `write_request`, `read_http_status`, `read_response_stream`, or `save_assistant_turn`) plus exception/cause class. No URL, request body, cookie, token, or raw exception message is written to logcat.
- The per-request `RayaMemoryStore` is closed in a `finally` block, including error paths; failure-to-save logging is guarded so it cannot prevent UI recovery.
- Service startup and wake-word history-ID writes now close their short-lived memory helpers. No database deletion, migration, or history clearing was performed.
- Next validation: build CI, install update without uninstall/clear-data, then inspect a real voice request log for the failure stage and verify SQLite leak warnings no longer recur.
