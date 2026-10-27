"""Executable DESIGN ONLY: global enrollment is not implemented in the Android app.

An abstract oracle exercises allowed state transitions and capability invariants.
Real file/key/AEAD/durability adapters and their failure tests remain required.
"""
from dataclasses import dataclass, field
import unittest


@dataclass
class EnrollmentModel:
    phase: str = "frozen"
    epoch: str = "legacy"
    cleanup_complete: bool = False
    provenance_complete: bool = False
    expected_guards: dict = field(default_factory=dict)
    guards: dict = field(default_factory=dict)
    used_epochs: set = field(default_factory=lambda: {"legacy"})
    approved: set = field(default_factory=set)
    staged: dict = field(default_factory=dict)
    committed: dict = field(default_factory=dict)
    events: list = field(default_factory=list)

    def guards_valid(self):
        return self.provenance_complete and self.guards == self.expected_guards

    def begin(self, epoch):
        if not (self.phase == "frozen" and self.cleanup_complete and self.guards_valid()):
            raise ValueError("No complete cleanup/guard evidence")
        if epoch in self.used_epochs:
            raise ValueError("Epoch reuse")
        # This transition models a verified durable PREPARING commit, before key creation.
        self.used_epochs.add(epoch)
        self.epoch = epoch
        self.approved = {f"index-{epoch}", f"claims-{epoch}", f"contact-{epoch}"}
        self.staged = {}
        self.phase = "preparing"
        self.events.append("preparing")

    def stage(self, kind):
        if self.phase != "preparing" or kind not in ("index", "claims"):
            raise ValueError("No staging authority")
        if kind in self.staged:
            raise ValueError("Never recreate a staged key/file over uncertain state")
        # Each artifact gets a key then an authenticated file then a durability check.
        self.staged[kind] = {"key": False, "file": False, "verified": False}

    def commit_active(self):
        if not (self.phase == "preparing" and self.guards_valid() and
                set(self.staged) == {"index", "claims"} and
                all(all(a.values()) for a in self.staged.values())):
            raise ValueError("Unverified commit")
        self.committed = {k: dict(v) for k, v in self.staged.items()}
        self.phase = "active"
        self.events.append("active")

    def permits(self, captured_epoch, namespace):
        return (self.phase == "active" and self.guards_valid() and
                set(self.committed) == {"index", "claims"} and
                all(all(a.values()) for a in self.committed.values()) and
                captured_epoch == self.epoch and namespace in self.approved)

    def abandon(self):
        if self.phase not in ("preparing", "failed"):
            raise ValueError("No staging to abandon")
        # A real adapter must durably tombstone this epoch BEFORE deleting staging.
        self.events.append("abandoned:" + self.epoch)
        self.phase = "failed"
        self.staged = {}
        self.committed = {}
        self.approved = set()
        self.phase = "frozen"

    def freeze(self):
        self.phase = "frozen"
        self.cleanup_complete = False
        self.provenance_complete = False
        self.approved = set()
        self.events.append("freeze")


class EnrollmentSpecificationTest(unittest.TestCase):
    def ready(self):
        return EnrollmentModel(cleanup_complete=True, provenance_complete=True,
                               expected_guards={"old-claims": "ciphertext-commitment", "old-ledger": "reservation-commitment"},
                               guards={"old-claims": "ciphertext-commitment", "old-ledger": "reservation-commitment"})

    def prepared(self):
        m = self.ready()
        m.begin("fresh")
        for kind in ("index", "claims"):
            m.stage(kind)
            m.staged[kind] = {"key": True, "file": True, "verified": True}
        return m

    def test_v1_complete_without_provenance_cannot_infer_empty_guards(self):
        m = EnrollmentModel(cleanup_complete=True)
        with self.assertRaises(ValueError):
            m.begin("fresh")
        self.assertEqual(m.phase, "frozen")
        self.assertFalse(m.staged)

    def test_every_incomplete_or_missing_evidence_prevents_staging(self):
        for failure in ("cleanup", "provenance", "missing", "altered", "unexpected"):
            with self.subTest(failure=failure):
                m = self.ready()
                if failure == "cleanup": m.cleanup_complete = False
                if failure == "provenance": m.provenance_complete = False
                if failure == "missing": del m.guards["old-claims"]
                if failure == "altered": m.guards["old-ledger"] = "older-reservations"
                if failure == "unexpected": m.guards["unknown-cohort"] = "unknown"
                with self.assertRaises(ValueError): m.begin("fresh")
                self.assertEqual(m.used_epochs, {"legacy"})

    def test_all_key_file_verification_boundaries_remain_frozen_until_last_commit(self):
        # Simulate interruption after every successful durable boundary, not just a normal run.
        for interrupted_after in range(10):
            with self.subTest(boundary=interrupted_after):
                m = self.ready()
                events = [lambda: m.begin("fresh")]
                for kind in ("index", "claims"):
                    events.extend([lambda k=kind: m.stage(k),
                                   lambda k=kind: m.staged[k].update(key=True),
                                   lambda k=kind: m.staged[k].update(file=True),
                                   lambda k=kind: m.staged[k].update(verified=True)])
                for event in events[:interrupted_after]: event()
                for owner in ("legacy", "fresh"):
                    self.assertFalse(m.permits(owner, "index-fresh"))
                if interrupted_after < len(events):
                    with self.assertRaises(ValueError): m.commit_active()
                else:
                    m.commit_active()
                    self.assertTrue(m.permits("fresh", "index-fresh"))

    def test_stale_metadata_claims_identity_and_contact_capabilities_never_rebind(self):
        m = self.prepared()
        m.commit_active()
        for namespace in m.approved:
            with self.subTest(namespace=namespace):
                self.assertFalse(m.permits("legacy", namespace))
                self.assertTrue(m.permits("fresh", namespace))
        self.assertFalse(m.permits("fresh", "station-v2"))
        self.assertFalse(m.permits("fresh", "index-legacy"))
        self.assertFalse(m.permits("fresh", "unapproved"))

    def test_guard_loss_between_preparation_and_activation_blocks_commit(self):
        for kind in ("old-claims", "old-ledger"):
            m = self.prepared()
            del m.guards[kind]
            with self.assertRaises(ValueError): m.commit_active()
            self.assertFalse(m.permits("fresh", "index-fresh"))

    def test_guard_or_committed_artifact_loss_after_activation_freezes_access(self):
        for artifact in ("old-claims", "old-ledger", "index-key", "index-file", "claims-file"):
            m = self.prepared()
            m.commit_active()
            if artifact in m.guards: del m.guards[artifact]
            else:
                kind, item = artifact.split("-")
                m.committed[kind][item] = False
            self.assertFalse(m.permits("fresh", "index-fresh"))
            self.assertFalse(m.permits("legacy", "index-fresh"))

    def test_abandoned_epoch_is_never_reused_and_orphan_staging_never_authorizes_access(self):
        m = self.prepared()
        m.staged["index"]["file"] = False  # A surviving key is not an empty index.
        with self.assertRaises(ValueError): m.stage("index")
        with self.assertRaises(ValueError): m.commit_active()
        m.abandon()
        self.assertEqual(m.events[-1], "abandoned:fresh")
        with self.assertRaises(ValueError): m.begin("fresh")
        m.begin("different")
        self.assertFalse(m.permits("fresh", "index-different"))
        self.assertFalse(m.permits("different", "index-different"))

    def test_second_global_freeze_revokes_every_new_owner_before_cleanup(self):
        m = self.prepared()
        m.commit_active()
        self.assertTrue(m.permits("fresh", "claims-fresh"))
        m.freeze()
        self.assertFalse(m.permits("fresh", "claims-fresh"))
        with self.assertRaises(ValueError): m.begin("second")
        self.assertEqual(m.events[-1], "freeze")
        m.cleanup_complete = True
        with self.assertRaises(ValueError): m.begin("second")  # New delta has no sealed provenance yet.

    def test_empty_guard_scope_needs_positive_provenance(self):
        m = EnrollmentModel(cleanup_complete=True)
        with self.assertRaises(ValueError): m.begin("fresh")
        m.provenance_complete = True  # Explicit pre-cleanup evidence, not inferred from missing files.
        m.begin("fresh")
        self.assertEqual(m.phase, "preparing")
        self.assertFalse(m.permits("fresh", "index-fresh"))


if __name__ == "__main__":
    unittest.main()
