import os, shutil, tempfile, unittest, io, contextlib
import verify_phase9a as v

SRC = os.path.join(os.path.dirname(__file__), "..", "backend")  # real source tree, to count the declared @Test methods


def write(reports, cls, tests, failures=0, errors=0, skipped=0):
    os.makedirs(reports, exist_ok=True)
    with open(os.path.join(reports, f"TEST-com.momocircle.{cls}.xml"), "w") as fh:
        fh.write(f'<testsuite name="com.momocircle.{cls}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}"/>')


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        # reuse the real test sources so expected counts come from the actual files
        shutil.copytree(os.path.join(SRC, "src"), os.path.join(self.tmp, "src"))
        self.reports = os.path.join(self.tmp, "target", "surefire-reports")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_gate(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            code = v.main(["--backend", self.tmp])
        return code, buf.getvalue()

    def all_pass(self):
        for cls, files in v.REQUIRED.items():
            write(self.reports, cls, v.expected_tests(self.tmp, files))

    def test_nothing_run_is_not_accepted(self):
        code, out = self.run_gate()
        self.assertEqual(code, 1)
        self.assertEqual(out.count("**NOT RUN**"), len(v.REQUIRED) + 1)

    def test_all_pass_is_accepted(self):
        self.all_pass()
        code, out = self.run_gate()
        self.assertEqual(code, 0, out)
        self.assertIn("ALL REQUIRED CHECKS PASSED", out)

    def test_a_skipped_postgres_suite_is_not_a_pass(self):
        self.all_pass()
        n = v.expected_tests(self.tmp, v.REQUIRED["service.ledger.LedgerPostgresGuardsTest"])
        write(self.reports, "service.ledger.LedgerPostgresGuardsTest", n, skipped=n)   # what Testcontainers does without Docker
        code, out = self.run_gate()
        self.assertEqual(code, 1)
        self.assertIn("**SKIPPED**", out)

    def test_one_skipped_test_among_many_is_still_not_a_pass(self):
        self.all_pass()
        n = v.expected_tests(self.tmp, v.REQUIRED["service.ledger.LedgerPostgresPersistenceTest"])
        write(self.reports, "service.ledger.LedgerPostgresPersistenceTest", n, skipped=1)
        self.assertEqual(self.run_gate()[0], 1)

    def test_failures_and_errors_fail_the_gate(self):
        self.all_pass()
        write(self.reports, "service.ledger.LedgerH2PersistenceTest", v.expected_tests(self.tmp, v.REQUIRED["service.ledger.LedgerH2PersistenceTest"]), failures=1)
        code, out = self.run_gate()
        self.assertEqual(code, 1)
        self.assertIn("**FAIL**", out)

    def test_a_suite_that_ran_too_few_tests_fails(self):
        self.all_pass()
        write(self.reports, "ledger.LedgerEngineTest", 3)
        self.assertEqual(self.run_gate()[0], 1)

    def test_an_empty_suite_is_not_run_not_pass(self):
        self.all_pass()
        write(self.reports, "service.ledger.MigrationUpgradePostgresTest", 0)
        code, out = self.run_gate()
        self.assertEqual(code, 1)
        self.assertIn("report contains 0 tests", out)

    def test_a_failure_elsewhere_in_the_full_suite_fails_the_gate(self):
        self.all_pass()
        write(self.reports, "service.SomeOtherExistingTest", 10, failures=1)
        self.assertEqual(self.run_gate()[0], 1)


if __name__ == "__main__":
    unittest.main()
