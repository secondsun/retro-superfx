from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass(frozen=True)
class LintIssue:
    code: str
    message: str
    path: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class ContextBudgetReport:
    always_loaded_file_chars: int
    skill_description_chars: int
    total_chars: int
    estimated_tokens: int
    max_tokens: int
    model_invoked_skill_count: int
    manual_only_skill_count: int

    @property
    def within_budget(self) -> bool:
        return self.estimated_tokens <= self.max_tokens

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["within_budget"] = self.within_budget
        return data


@dataclass
class HarnessLintReport:
    passed: bool
    issues: list[LintIssue] = field(default_factory=list)
    context_budget: ContextBudgetReport = field(
        default_factory=lambda: ContextBudgetReport(
            always_loaded_file_chars=0,
            skill_description_chars=0,
            total_chars=0,
            estimated_tokens=0,
            max_tokens=16000,
            model_invoked_skill_count=0,
            manual_only_skill_count=0,
        )
    )
    checked_skills: int = 0
    checked_symlinks: int = 0
    checked_links: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "issues": [issue.to_dict() for issue in self.issues],
            "context_budget": self.context_budget.to_dict(),
            "checked_skills": self.checked_skills,
            "checked_symlinks": self.checked_symlinks,
            "checked_links": self.checked_links,
        }


@dataclass
class EvalScorecard:
    passed: bool
    tier0: dict[str, Any] = field(default_factory=dict)
    tier1: dict[str, Any] = field(default_factory=dict)
    tier2: dict[str, Any] = field(default_factory=dict)
    transcript: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "passed": self.passed,
            "tier0": self.tier0,
            "tier1": self.tier1,
            "tier2": self.tier2,
        }
        if self.transcript:
            data["transcript"] = self.transcript
        return data


