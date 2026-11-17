"""Validate injected deployment inputs offline, without persisting or logging secrets."""

import os
from pathlib import Path
import sys
from typing import Mapping


PROFILES = {"QA": "qa", "PROD": "prod"}
REQUIRED_VALUES = ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "FANOUT_REGION")


def validate_environment(values: Mapping[str, str]) -> list[str]:
    errors = []
    environment = values.get("TARGET_ENVIRONMENT", "")
    expected_profile = PROFILES.get(environment)
    if expected_profile is None:
        errors.append("TARGET_ENVIRONMENT must be QA or PROD.")
    elif values.get("SPRING_PROFILES_ACTIVE") != expected_profile:
        errors.append("SPRING_PROFILES_ACTIVE must match the selected environment.")

    for name in REQUIRED_VALUES:
        if not values.get(name, "").strip():
            errors.append(f"{name} must be configured in the selected GitHub Environment.")

    url = values.get("DB_URL", "")
    if url.strip() and not url.startswith("jdbc:postgresql:"):
        errors.append("DB_URL must be a PostgreSQL JDBC URL.")
    if len(values.get("FANOUT_REGION", "")) > 200:
        errors.append("FANOUT_REGION must not exceed 200 characters.")
    return errors


def main() -> int:
    errors = validate_environment(os.environ)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1

    # Only allowlisted names reach output. Never output values, lengths or hashes of secrets.
    environment = os.environ["TARGET_ENVIRONMENT"]
    summary = (
        f"{environment} configuration validated (Spring profile {PROFILES[environment]}).\n"
        "Required values are present and their basic format is valid.\n"
        "No database connection, migration, application startup or deployment was performed.\n"
    )
    print(summary, end="")
    summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_file:
        with Path(summary_file).open("a", encoding="utf-8") as output:
            output.write(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
