import importlib.util
from pathlib import Path

from cribrum_lab.labels import Decision

_SCRIPT = Path(__file__).parents[1] / "scripts" / "study_video_size_order.py"
_spec = importlib.util.spec_from_file_location("study_video_size_order", _SCRIPT)
study = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(study)

ORDER = [(Path("a"), 900), (Path("b"), 50), (Path("c"), 300), (Path("d"), 10)]
DECISIONS = {
    Path("a"): Decision.KEEP,
    Path("b"): Decision.DELETE,
    Path("c"): Decision.DELETE,
    Path("d"): Decision.KEEP,
}


def test_found_in_first_counts_the_bytes_of_the_deletes_only():
    deletes = {Path("b"), Path("c")}

    assert [study.found_in_first(ORDER, deletes, n) for n in (1, 2, 3, 4)] == [0, 50, 350, 350]


def test_deletes_first_is_the_share_of_delete_keep_pairs_in_that_order():
    # b and c come after the keep a and before the keep d: 2 of the 4 pairs.
    assert study.deletes_first(ORDER, DECISIONS) == 0.5
    assert study.deletes_first(ORDER, {Path("a"): Decision.KEEP}) is None
