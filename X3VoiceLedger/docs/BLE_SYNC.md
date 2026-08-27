# Bluetooth synchronization direction

The companion phone app acts as the BLE central. The watch app exposes pending ledger records as a
BLE peripheral/GATT server. Hardware advertising, scanning, and GATT capability are verified by the
separate `X3AccessibilityProbe` task rather than by production ledger code.

Each record is identified by its UUID. A phone acknowledgement containing the same UUID is required
before the watch changes `sync_state` from pending to synced. Reconnects may resend records safely;
the phone must upsert by UUID rather than insert blindly.

The payload must preserve:

- schema version and ledger UUID
- creation, update, and editable transaction timestamps
- amount in integer cents, note, and category
- original voice-transcription text
- editable location label
- raw latitude, longitude, accuracy, and location timestamp when available

Sync attempts should run after a save, when the phone reconnects, and from a manual sync action.
No record is deleted automatically after acknowledgement.

The production characteristics require an authenticated encrypted BLE link. Advertising exposes only
the service UUID; financial data is never placed in advertisement packets.
