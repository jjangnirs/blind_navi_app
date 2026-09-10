"""Site Leakage Validator for Dataset Splits.

Ensures that no physical intersection or site (site_id) is shared across
train, validation, and test splits, preventing spatial data leakage.
"""

from ml.datasets.manifest_schema import DatasetManifest, SplitType


class SiteLeakageError(Exception):
    """Raised when the same site_id appears in multiple splits."""

    def __init__(self, message: str, leaking_sites: dict[str, list[str]]):
        super().__init__(message)
        self.leaking_sites = leaking_sites


def validate_site_split(manifest: DatasetManifest) -> dict[SplitType, set[str]]:
    """Validate that site_id sets in TRAIN, VAL, and TEST are strictly disjoint.

    Returns:
        Dict mapping each SplitType to its set of site_ids.

    Raises:
        SiteLeakageError: If any site_id appears in more than one split.
    """
    site_to_splits: dict[str, set[SplitType]] = {}

    for item in manifest.items:
        if item.site_id not in site_to_splits:
            site_to_splits[item.site_id] = set()
        site_to_splits[item.site_id].add(item.split)

    leaking_sites: dict[str, list[str]] = {}
    for site_id, splits in site_to_splits.items():
        if len(splits) > 1:
            leaking_sites[site_id] = sorted([s.value for s in splits])

    if leaking_sites:
        msg = (
            f"Data leakage detected! {len(leaking_sites)} site(s) appear across multiple splits: "
            f"{list(leaking_sites.keys())}"
        )
        raise SiteLeakageError(msg, leaking_sites)

    split_sites: dict[SplitType, set[str]] = {
        SplitType.TRAIN: set(),
        SplitType.VAL: set(),
        SplitType.TEST: set(),
    }
    for item in manifest.items:
        split_sites[item.split].add(item.site_id)

    return split_sites
