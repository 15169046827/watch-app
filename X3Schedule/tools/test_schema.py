"""Exercise shipped SQLite migrations and crash/constraint semantics without a device."""
import pathlib
import sqlite3
import unittest

ASSETS = pathlib.Path(__file__).resolve().parents[1] / "shared/src/main/assets"

class SchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.executescript((ASSETS / "schema-v1.sql").read_text(encoding="utf-8-sig"))

    def tearDown(self):
        self.db.close()

    def test_upgrade_preserves_history_and_pending_ack(self):
        self.db.execute("INSERT INTO plans VALUES ('p',1,'synthetic-plan')")
        self.db.execute("INSERT INTO instances VALUES ('p:2026-01-01','p',1,'synthetic-instance')")
        self.db.execute("INSERT INTO events VALUES ('e','p:2026-01-01',2,'synthetic-event')")
        self.db.execute("INSERT INTO outbox VALUES ('msg',3,?)", (b"synthetic-payload",))
        self.db.commit()
        self.db.executescript((ASSETS / "migration-1-2.sql").read_text(encoding="utf-8-sig"))
        self.assertEqual(self.db.execute("SELECT body FROM events").fetchone()[0], "synthetic-event")
        self.assertEqual(self.db.execute("SELECT revision,body FROM outbox").fetchone(), (3, b"synthetic-payload"))
        self.assertEqual(self.db.execute("SELECT count(*) FROM suggestions").fetchone()[0], 0)

    def test_v3_keeps_old_queue_bytes_and_pending_revision(self):
        self.db.executescript((ASSETS / "migration-1-2.sql").read_text(encoding="utf-8-sig"))
        self.db.execute("INSERT INTO plans VALUES ('p',1,'synthetic-plan')")
        self.db.execute("INSERT INTO events VALUES ('e','i',1,'synthetic-event')")
        self.db.execute("INSERT INTO outbox VALUES ('old-msg',8,?)", (b"exact-old-bytes",))
        self.db.execute("INSERT INTO meta VALUES ('revision','9')")
        self.db.execute("INSERT INTO meta VALUES ('acked_revision','4')")
        self.db.execute("INSERT INTO suggestions VALUES ('a','p','accepted-advice',1)")
        self.db.commit()
        self.db.executescript((ASSETS / "migration-2-3.sql").read_text(encoding="utf-8-sig"))
        self.assertEqual(self.db.execute("SELECT * FROM legacy_outbox_v1").fetchone(),
                         ("old-msg", 8, b"exact-old-bytes"))
        self.assertEqual(self.db.execute("SELECT count(*) FROM outbox").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0], "10")
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='acked_revision'").fetchone()[0], "4")
        self.assertEqual(self.db.execute("SELECT body FROM events").fetchone()[0], "synthetic-event")
        self.assertEqual(self.db.execute("SELECT body FROM plans").fetchone()[0], "synthetic-plan")
        self.assertEqual(self.db.execute("SELECT accepted FROM suggestions").fetchone()[0], 1)

    def test_v3_can_upgrade_empty_v1_without_inventing_ack(self):
        self.db.executescript((ASSETS / "migration-1-2.sql").read_text(encoding="utf-8-sig"))
        self.db.executescript((ASSETS / "migration-2-3.sql").read_text(encoding="utf-8-sig"))
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0], "1")
        self.assertIsNone(self.db.execute("SELECT value FROM meta WHERE key='acked_revision'").fetchone())

    def upgrade_four(self):
        for migration in ["migration-1-2.sql", "migration-2-3.sql", "migration-3-4.sql"]:
            self.db.executescript((ASSETS / migration).read_text(encoding="utf-8-sig"))

    def test_v5_discards_only_unfinished_transfers_and_keeps_receipts_and_history(self):
        self.upgrade_four()
        self.db.execute("INSERT INTO plans VALUES ('p',1,'synthetic-plan')")
        self.db.execute("INSERT INTO events VALUES ('e','i',1,'synthetic-event')")
        self.db.execute("INSERT INTO meta VALUES ('settings','synthetic-settings')")
        self.db.execute("INSERT INTO meta VALUES ('peer','synthetic-peer')")
        self.db.execute("INSERT INTO meta VALUES ('acked_revision','1')")
        self.db.execute("INSERT INTO batch_receipts VALUES ('done','manifest','digests')")
        self.db.execute("INSERT INTO batch_out VALUES (1,'out',0)")
        self.db.execute("INSERT INTO batch_out_parts VALUES (0,?)", (b'part',))
        self.db.execute("INSERT INTO batch_in VALUES (1,'in')")
        self.db.execute("INSERT INTO batch_in_parts VALUES (0,?,'digest')", (b'part',))
        self.db.execute("INSERT INTO outbox VALUES ('old',2,?)", (b'old',))
        self.db.commit()
        self.db.executescript((ASSETS / "migration-4-5.sql").read_text(encoding="utf-8-sig"))
        for table in ['batch_out', 'batch_out_parts', 'batch_in', 'batch_in_parts', 'outbox']:
            self.assertEqual(self.db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0], 0)
        for table in ['plans', 'events', 'batch_receipts']:
            self.assertEqual(self.db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0], 1)
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='peer'").fetchone()[0], 'synthetic-peer')
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='settings'").fetchone()[0], 'synthetic-settings')
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='acked_revision'").fetchone()[0], '1')
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0], '3')

    def test_v4_retains_legacy_queue_and_does_not_ack_pending_history(self):
        for migration in ["migration-1-2.sql", "migration-2-3.sql"]:
            self.db.executescript((ASSETS / migration).read_text(encoding="utf-8-sig"))
        self.db.execute("INSERT INTO outbox VALUES ('v2',12,?)", (b"exact-v2-bytes",))
        self.db.execute("INSERT INTO events VALUES ('e','i',1,'synthetic-event')")
        self.db.execute("INSERT INTO meta VALUES ('acked_revision','3')")
        self.db.commit()
        self.db.executescript((ASSETS / "migration-3-4.sql").read_text(encoding="utf-8-sig"))
        self.assertEqual(self.db.execute("SELECT * FROM outbox").fetchone(), ("v2",12,b"exact-v2-bytes"))
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0], "13")
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='acked_revision'").fetchone()[0], "3")
        self.assertEqual(self.db.execute("SELECT body FROM events").fetchone()[0], "synthetic-event")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM batch_out_parts").fetchone()[0], 0)

    def test_v4_restricts_page_size_sequence_and_single_active_batch(self):
        self.upgrade_four()
        self.db.execute("INSERT INTO batch_in VALUES (1,'manifest')")
        for values in [(256,b'x','hash'),(0,b'','hash'),(0,b'x'*131073,'hash')]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute("INSERT INTO batch_in_parts VALUES (?,?,?)",values)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO batch_in VALUES (2,'another')")
        self.db.execute("INSERT INTO batch_in_parts VALUES (0,?,'digest')",(b'x'*131072,))
        self.assertEqual(self.db.execute("SELECT length(body) FROM batch_in_parts").fetchone()[0],131072)

    def test_v4_conflict_rolls_back_merge_receipt_and_staging_cleanup_together(self):
        self.upgrade_four()
        self.db.execute("INSERT INTO batch_in VALUES (1,'manifest')")
        self.db.execute("INSERT INTO batch_in_parts VALUES (0,?,'digest')",(b'part',))
        self.db.execute("INSERT INTO batch_receipts VALUES ('old','manifest','digests')")
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("INSERT INTO plans VALUES ('p',1,'synthetic')")
                self.db.execute("DELETE FROM batch_in_parts")
                self.db.execute("INSERT INTO batch_receipts VALUES ('old','different','digests')")
        self.assertEqual(self.db.execute("SELECT count(*) FROM plans").fetchone()[0],0)
        self.assertEqual(self.db.execute("SELECT body FROM batch_in_parts").fetchone()[0],b'part')
        self.assertEqual(self.db.execute("SELECT count(*) FROM batch_receipts").fetchone()[0],1)

    def test_atomic_failure_does_not_partially_apply_snapshot(self):
        self.db.execute("INSERT INTO inbox VALUES ('msg','digest')")
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("INSERT INTO plans VALUES ('p',2,'synthetic')")
                self.db.execute("INSERT INTO inbox VALUES ('msg','different')")
        self.assertEqual(self.db.execute("SELECT count(*) FROM plans").fetchone()[0], 0)

    def test_invalid_plan_version_and_duplicate_event_rejected(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO plans VALUES ('p',0,'invalid')")
        self.db.execute("INSERT INTO events VALUES ('e','i',1,'synthetic')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO events VALUES ('e','i',2,'changed')")

    def test_v6_preserves_business_settings_and_committed_receipts(self):
        self.upgrade_four()
        self.db.executescript((ASSETS / "migration-4-5.sql").read_text(encoding="utf-8-sig"))
        self.db.execute("INSERT INTO plans VALUES ('p',1,'old-rules')")
        self.db.execute("INSERT INTO events VALUES ('e','i',1,'old-event')")
        self.db.execute("INSERT INTO meta VALUES ('settings','old-settings')")
        self.db.execute("INSERT INTO meta VALUES ('peer','synthetic-peer')")
        self.db.execute("INSERT INTO batch_receipts VALUES ('done','retained','digest')")
        self.db.execute("INSERT INTO batch_out VALUES (1,'old-protocol',0)")
        self.db.commit()
        self.db.executescript((ASSETS / "migration-5-6.sql").read_text(encoding="utf-8-sig"))
        for table in ['plans', 'events', 'batch_receipts']:
            self.assertEqual(self.db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0], 1)
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='settings'").fetchone()[0], 'old-settings')
        self.assertEqual(self.db.execute("SELECT value FROM meta WHERE key='peer'").fetchone()[0], 'synthetic-peer')
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM batch_out").fetchone()[0], 0)

if __name__ == "__main__":
    unittest.main(verbosity=2)
