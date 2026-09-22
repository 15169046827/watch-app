# X3VoiceLedger data lifecycle

This document defines the product-specific migration, export, clearing, and recovery behavior for the watch app and phone companion.

## Data inventory

| Data | Location | Upgrade/device migration | Export | Clear | Notes |
| --- | --- | --- | --- | --- | --- |
| Watch ledger records | Watch SQLite `voice_ledger.db` | Schema migration; device migration is watch-to-phone BLE sync before replacement | Included after successful sync in the phone JSON backup | Watch “数据与隐私” | Sensitive financial data; unsynced records exist only on the watch |
| Watch account/category profile | Watch `ledger_profile` preferences | Re-sent from the restored phone profile | Included in the phone JSON backup | Watch “清除配置” | Contains no credentials |
| Payment capture consent | Watch `privacy_choices` | Must be granted again; never migrated | Not exported | Watch “清除配置” | System Accessibility authorization is separate and is never changed by data clearing |
| Payment duplicate cache | Watch `automatic_payment_dedup` | Cache only; never migrated | Not exported | Watch “清除配置” | No payment page text or node tree is retained |
| Phone ledger records and Yimu state | Phone SQLite `phone_ledger.db` | Schema migration or user-selected JSON restore | Included | Phone “数据迁移、导出与清除” | Sensitive financial data |
| Phone accounts, categories, books, and tags | Phone `ledger_profile` preferences | JSON restore, then BLE sync to the watch | Included | Phone data controls | Contains names only, no Yimu credentials |
| Learned category rules and confirmation mode | Phone `category_rules` preferences | JSON restore | Included | Phone data controls | Can be cleared without deleting ledger records |
| Screenshot scan cursor and retry state | Phone `watch_screenshot_monitor` preferences | Cache only; never migrated | Not exported | Phone data controls | Clearing does not delete screenshots |
| User-selected Yimu export files | User-selected storage | Remain under user control | Not copied into backup | Not deleted by the app | The app reads only the selected URI |
| System permissions and Bluetooth pairing | Android system | Re-authorize/re-pair on a new device | Never exported | Only through system settings | Not application credentials |

## Upgrade migration

- Watch and phone databases use schema version 5. Android runs `SQLiteOpenHelper.onUpgrade` in a transaction; version 5 non-destructively backfills missing legacy revision/transaction times and does not drop tables or columns.
- If an upgrade transaction fails, Android rolls it back and the same migration can be retried on the next start. The application must not uninstall or clear data as a workaround.
- A binary downgrade from database version 5 is not supported. Recover by reinstalling the latest correctly signed version over the existing app; use a previously exported JSON backup if device data itself was lost.
- Android cloud backup and automatic device transfer are explicitly excluded for phone financial data. Cross-device migration is user-controlled: sync watch records to the phone, export JSON, install the same signed product on the new device, import JSON, then re-authorize permissions and Bluetooth pairing.

## JSON export and restore

- Format identifier: `x3voiceledger-backup`, schema `1`, UTF-8 JSON.
- Scope: all phone ledger records, Yimu archive state, account/category/book/tag profile, learned category rules, and confirmation mode.
- Excluded: permissions, Bluetooth pairing, screenshot scan cursor, payment page content, raw screenshots, credentials, and source import files.
- The file is not encrypted. The UI labels it as sensitive financial data; the Android Storage Access Framework lets the user choose the exact destination and presents its normal overwrite confirmation.
- Export is limited to 16 MiB. Restore is limited to 16 MiB, 50,000 records, supported schemas, bounded identifiers, and bounded text fields before mutation begins.
- Restore is safely repeatable. Ledger records merge by UUID and `updatedAt`; older records cannot overwrite newer local edits, equal revisions are idempotent, and profile/rules are replaced only after explicit confirmation.

## Clearing and recovery

- Both apps require a second confirmation before destructive clearing.
- Watch controls separate ledger records from profile/payment-consent caches. Phone controls separate ledger records, profile/rules, screenshot cache, and all local data.
- Clearing never revokes Android permissions, alters Bluetooth pairing, deletes external screenshots/import files, or deletes copies already present on the other device or in Yimu.
- Before clearing or replacing a watch, the user must verify “待同步 0”. Before clearing or replacing a phone, the user should export a JSON backup and verify that the destination file is readable.

## Release acceptance still required

Before public signed sideload release, validate on the target watch and phone: covered upgrade without uninstall, migration retry after interrupted startup, watch-to-phone sync, JSON export, JSON restore on a clean install, every clear option, permission refusal/re-authorization, and preservation of system pairing/externally owned files.
