#!/usr/bin/env python3
"""Phase 9A acceptance-gate checker. Reads REAL Maven Surefire reports; never infers success.

Per required test class it reports exactly one of:
  PASS     executed, >= the expected number of tests, 0 failures, 0 errors, 0 skipped
  FAIL     executed with failures/errors
  SKIPPED  one or more tests were skipped (e.g. Testcontainers without Docker). NOT a pass.
  NOT RUN  no surefire report exists for the class (or it contains zero tests)
Exit status is 0 only when EVERY required class is PASS, so a green exit cannot hide a skipped PostgreSQL suite.

Usage:  python3 verify_phase9a.py --backend backend-web/backend [--markdown out.md]
"""
import argparse, glob, os, re, sys
import xml.etree.ElementTree as ET

P = "com.momocircle."
TESTDIR = "src/test/java/com/momocircle/"

# class -> source files whose @Test methods it runs (JUnit counts inherited tests under the concrete class)
REQUIRED = {
    "ledger.LedgerEngineTest": ["ledger/LedgerEngineTest.java"],
    "ledger.LedgerOperationsAuditTest": ["ledger/LedgerOperationsAuditTest.java"],
    "service.ledger.JpaLedgerStoreRetryTest": ["service/ledger/JpaLedgerStoreRetryTest.java"],
    "service.ledger.LedgerH2PersistenceTest": ["service/ledger/AbstractLedgerPersistenceTest.java", "service/ledger/LedgerH2PersistenceTest.java"],
    "service.ledger.MigrationUpgradeH2Test": ["service/ledger/AbstractMigrationUpgradeTest.java", "service/ledger/MigrationUpgradeH2Test.java"],
    "service.ledger.LedgerPostgresPersistenceTest": ["service/ledger/AbstractLedgerPersistenceTest.java", "service/ledger/LedgerPostgresPersistenceTest.java"],
    "service.ledger.MigrationUpgradePostgresTest": ["service/ledger/AbstractMigrationUpgradeTest.java", "service/ledger/MigrationUpgradePostgresTest.java"],
    "service.ledger.LedgerPostgresGuardsTest": ["service/ledger/AbstractLedgerPersistenceTest.java", "service/ledger/LedgerPostgresGuardsTest.java"],
}


def expected_tests(backend, files):
    n = 0
    for f in files:
        path = os.path.join(backend, TESTDIR, f)
        if not os.path.exists(path):
            return None
        with open(path, encoding="utf-8") as fh:
            n += len(re.findall(r"^\s*@Test\b", fh.read(), re.M))
    return n


def read_suite(backend, cls):
    path = os.path.join(backend, "target", "surefire-reports", f"TEST-{P}{cls}.xml")
    if not os.path.exists(path):
        return None
    root = ET.parse(path).getroot()
    if root.tag == "testsuites":
        root = root[0]
    g = lambda k: int(float(root.get(k, "0")))
    return {"tests": g("tests"), "failures": g("failures"), "errors": g("errors"), "skipped": g("skipped")}


def classify(suite, expected):
    if suite is None or suite["tests"] == 0:
        return "NOT RUN", "no report" if suite is None else "report contains 0 tests"
    if suite["failures"] or suite["errors"]:
        return "FAIL", f"{suite['failures']} failures, {suite['errors']} errors"
    if suite["skipped"]:
        return "SKIPPED", f"{suite['skipped']} of {suite['tests']} skipped (not a pass)"
    if expected is not None and suite["tests"] < expected:
        return "FAIL", f"only {suite['tests']} tests ran, source declares {expected}"
    return "PASS", f"{suite['tests']} passed"


def full_suite_totals(backend):
    t = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "suites": 0}
    for f in glob.glob(os.path.join(backend, "target", "surefire-reports", "TEST-*.xml")):
        root = ET.parse(f).getroot()
        root = root[0] if root.tag == "testsuites" else root
        t["suites"] += 1
        for k in ("tests", "failures", "errors", "skipped"):
            t[k] += int(float(root.get(k, "0")))
    return t


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", required=True)
    ap.add_argument("--markdown")
    a = ap.parse_args(argv)

    rows, ok = [], True
    for cls, files in REQUIRED.items():
        status, detail = classify(read_suite(a.backend, cls), expected_tests(a.backend, files))
        ok &= status == "PASS"
        rows.append((cls.split(".")[-1], status, detail))
    tot = full_suite_totals(a.backend)
    full = "NOT RUN" if tot["suites"] == 0 else ("FAIL" if tot["failures"] or tot["errors"] else "PASS")
    ok &= full == "PASS"

    lines = ["| Required test class | Result | Detail |", "|---|---|---|"]
    lines += [f"| {c} | **{s}** | {d} |" for c, s, d in rows]
    lines.append(f"| FULL backend suite (all surefire reports) | **{full}** | {tot['suites']} suites, {tot['tests']} tests, "
                 f"{tot['failures']} failures, {tot['errors']} errors, {tot['skipped']} skipped (skips elsewhere are reported, not hidden) |")
    out = "\n".join(lines)
    print(out)
    print("\nGATE:", "ALL REQUIRED CHECKS PASSED" if ok else "NOT ACCEPTED (see non-PASS rows)")
    if a.markdown:
        with open(a.markdown, "w", encoding="utf-8") as fh:
            fh.write(out + "\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
