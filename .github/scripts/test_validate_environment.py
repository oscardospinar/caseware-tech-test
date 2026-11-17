import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from validate_environment import main, validate_environment


class EnvironmentValidationTest(unittest.TestCase):
    def values(self, environment="QA"):
        return {
            "TARGET_ENVIRONMENT": environment,
            "SPRING_PROFILES_ACTIVE": environment.lower(),
            "DB_URL": "jdbc:postgresql://database.invalid:5432/fanout",
            "DB_USERNAME": "test-user",
            "DB_PASSWORD": "test-secret-with-'quotes'-$(no-command)\nand-newline",
            "FANOUT_REGION": "EU",
        }

    def test_qa_and_prod_accept_injected_values(self):
        for environment in ("QA", "PROD"):
            with self.subTest(environment=environment):
                self.assertEqual([], validate_environment(self.values(environment)))

    def test_each_required_value_must_be_present_and_nonblank(self):
        for name in ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "FANOUT_REGION"):
            for replacement in (None, "", " \t\n"):
                with self.subTest(name=name, replacement=replacement):
                    values = self.values()
                    if replacement is None:
                        del values[name]
                    else:
                        values[name] = replacement
                    self.assertTrue(any(name in error for error in validate_environment(values)))

    def test_wrong_or_combined_profile_is_rejected(self):
        for profile in ("local", "prod", "qa,prod", "QA", ""):
            values = self.values()
            values["SPRING_PROFILES_ACTIVE"] = profile
            self.assertTrue(any("SPRING_PROFILES_ACTIVE" in error for error in validate_environment(values)))

    def test_unknown_environment_is_rejected(self):
        values = self.values()
        values["TARGET_ENVIRONMENT"] = "unexpected-secret-text"
        errors = validate_environment(values)
        self.assertTrue(errors)
        self.assertNotIn("unexpected-secret-text", "\n".join(errors))

    def test_non_postgres_url_is_rejected_without_echoing_it(self):
        values = self.values()
        values["DB_URL"] = "jdbc:h2:secret-connection"
        errors = validate_environment(values)
        self.assertIn("DB_URL must be a PostgreSQL JDBC URL.", errors)
        self.assertNotIn("secret-connection", "\n".join(errors))

    def test_oversized_region_is_rejected(self):
        values = self.values()
        values["FANOUT_REGION"] = "r" * 201
        self.assertTrue(any("FANOUT_REGION" in error for error in validate_environment(values)))

    def test_success_output_and_summary_never_contain_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = Path(directory) / "summary.md"
            values = self.values("PROD") | {"GITHUB_STEP_SUMMARY": str(summary)}
            output = io.StringIO()
            with patch.dict(os.environ, values, clear=True), contextlib.redirect_stdout(output):
                self.assertEqual(0, main())
            combined = output.getvalue() + summary.read_text(encoding="utf-8")
            self.assertIn("PROD configuration validated", combined)
            self.assertIn("No database connection", combined)
            for name in ("DB_URL", "DB_USERNAME", "DB_PASSWORD"):
                self.assertNotIn(values[name], combined)

    def test_failure_exits_nonzero_without_printing_secrets(self):
        values = self.values()
        values["DB_URL"] = "invalid-secret-url"
        output = io.StringIO()
        with patch.dict(os.environ, values, clear=True), contextlib.redirect_stderr(output):
            self.assertEqual(1, main())
        self.assertNotIn(values["DB_URL"], output.getvalue())
        self.assertNotIn(values["DB_PASSWORD"], output.getvalue())


if __name__ == "__main__":
    unittest.main()
