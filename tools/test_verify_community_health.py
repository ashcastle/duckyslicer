from __future__ import annotations

import unittest

from tools.verify_community_health import (
    CommunityHealthError,
    read_sources,
    verify_community_health,
)


class VerifyCommunityHealthTest(unittest.TestCase):
    def test_current_community_health_contract_is_complete(self) -> None:
        verify_community_health(read_sources())

    def test_rejects_bug_form_without_public_data_warning(self) -> None:
        sources = read_sources()
        sources["bug_report.yml"] = sources["bug_report.yml"].replace(
            "printer addresses",
            "server details",
        )
        with self.assertRaisesRegex(CommunityHealthError, "safety terms"):
            verify_community_health(sources)

    def test_rejects_optional_reproduction_steps(self) -> None:
        sources = read_sources()
        source = sources["bug_report.yml"]
        start = source.index("    id: steps")
        end = source.index("  - type:", start)
        sources["bug_report.yml"] = (
            source[:start]
            + source[start:end].replace("validations:\n      required: true\n", "")
            + source[end:]
        )
        with self.assertRaisesRegex(CommunityHealthError, "steps"):
            verify_community_health(sources)

    def test_rejects_blank_public_issues(self) -> None:
        sources = read_sources()
        sources["config.yml"] = sources["config.yml"].replace(
            "blank_issues_enabled: false",
            "blank_issues_enabled: true",
        )
        with self.assertRaisesRegex(CommunityHealthError, "safe routes"):
            verify_community_health(sources)

    def test_rejects_unreviewed_dependabot_ecosystem(self) -> None:
        sources = read_sources()
        sources["dependabot.yml"] += '''
  - package-ecosystem: "pip"
    directory: "/"
    schedule:
      interval: "weekly"
'''
        with self.assertRaisesRegex(CommunityHealthError, "ecosystems changed"):
            verify_community_health(sources)

    def test_rejects_high_frequency_dependency_churn(self) -> None:
        sources = read_sources()
        sources["dependabot.yml"] = sources["dependabot.yml"].replace(
            'interval: "weekly"',
            'interval: "daily"',
            1,
        )
        with self.assertRaisesRegex(CommunityHealthError, "review policy changed"):
            verify_community_health(sources)

    def test_rejects_pull_requests_without_validation_evidence(self) -> None:
        sources = read_sources()
        sources["pull_request_template.md"] = sources["pull_request_template.md"].replace(
            "## Validation",
            "## Checks",
        )
        with self.assertRaisesRegex(CommunityHealthError, "review evidence"):
            verify_community_health(sources)


if __name__ == "__main__":
    unittest.main()
