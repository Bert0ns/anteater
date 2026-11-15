import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "scripts" / "check_parity.py"
spec = importlib.util.spec_from_file_location("check_parity", SCRIPT)
parity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parity)


def test_parsing_each_language():
    lab = parity.values(
        'MAX_SIDE = 512\n_CHUNK = 8\nclass D:\n    id = "d"\n    version = 2\n',
        parity.LAB_ID,
        parity.LAB_VERSION,
        parity.LAB_CONSTANT,
    )
    android = parity.values(
        'class D {\n  override val id = "d"\n  override val version = 2\n'
        "  const val MAX_SIDE = 512\n  private val CHUNK: Float = 8f\n}\n",
        parity.ANDROID_ID,
        parity.ANDROID_VERSION,
        parity.ANDROID_CONSTANT,
    )
    expected = {"d.MAX_SIDE": "512", "d.CHUNK": "8", "d.version": "2", "d.media_types": "image"}
    assert lab == android == expected


def test_a_different_value_names_the_mismatch():
    problems = parity.mismatches({"d.version": "2", "d.A": "0.5"}, {"d.version": "3", "d.A": "0.5"})
    assert problems == ["d.version: lab 2, android 3"]


def test_a_detector_only_the_lab_has_is_a_mismatch():
    problems = parity.mismatches({"d.version": "1", "e.version": "1"}, {"e.version": "1"})
    assert problems == ["d.version: lab 1, android missing"]


def test_a_detector_listed_as_lab_only_is_skipped(monkeypatch):
    monkeypatch.setattr(parity, "LAB_ONLY", {"d"})
    assert parity.mismatches({"d.version": "1", "e.version": "1"}, {"e.version": "1"}) == []


def test_an_android_file_whose_id_does_not_match_gives_nothing():
    source = 'class D {\n  override val identifier = "d"\n  const val A = 1\n}\n'
    android = parity.values(
        source, parity.ANDROID_ID, parity.ANDROID_VERSION, parity.ANDROID_CONSTANT
    )
    assert parity.mismatches({"d.version": "1", "d.A": "1"}, android) != []


def test_a_trailing_comment_does_not_hide_a_constant():
    lab = parity.values(
        'A = 0.5  # tuned\nclass D:\n    id = "d"\n',
        parity.LAB_ID,
        parity.LAB_VERSION,
        parity.LAB_CONSTANT,
    )
    android = parity.values(
        'override val id = "d"\nconst val A = 0.9f // tuned\n',
        parity.ANDROID_ID,
        parity.ANDROID_VERSION,
        parity.ANDROID_CONSTANT,
    )
    assert lab == {"d.A": "0.5", "d.media_types": "image"}
    assert android == {"d.A": "0.9", "d.media_types": "image"}
    assert parity.mismatches(lab, android) == ["d.A: lab 0.5, android 0.9"]


def test_a_constant_renamed_on_one_side_is_a_mismatch():
    problems = parity.mismatches({"d.FULL_COVERAGE": "0.1"}, {"d.FULL_COV": "0.1"})
    assert problems == [
        "d.FULL_COV: lab missing, android 0.1",
        "d.FULL_COVERAGE: lab 0.1, android missing",
    ]


def test_a_listed_one_sided_constant_is_allowed():
    assert parity.mismatches({"near_duplicate.CHUNK": "1024"}, {}) == []


def test_the_ids_a_sticker_skips_are_read_from_each_pipeline():
    lab = 'SKIPPED_ON_STICKERS = frozenset({"blur", "faces"})\n"""Docs."""\n'
    android = 'val SKIPPED_ON_STICKERS: Set<String> = setOf("faces", "blur")\n'
    assert (
        parity.skipped_on_stickers(lab) == parity.skipped_on_stickers(android) == {"blur", "faces"}
    )
    assert parity.skipped_on_stickers("x = 1\n") is None


def test_a_different_skip_on_stickers_is_a_mismatch():
    assert parity.skipped_mismatch({"blur"}, {"blur"}) == []
    assert parity.skipped_mismatch({"blur", "faces"}, {"blur"}) == [
        "SKIPPED_ON_STICKERS: lab blur, faces, android blur"
    ]
    assert parity.skipped_mismatch(None, {"blur"}) == [
        "SKIPPED_ON_STICKERS: lab missing, android blur"
    ]


def test_the_media_types_a_detector_accepts_are_images_only_unless_it_declares_more():
    lab = 'class D:\n    id = "d"\n    version = 1\n'
    android = 'class D {\n  override val id = "d"\n  override val version = 1\n}\n'
    lab_both = lab + "    media_types = frozenset({MediaType.VIDEO, MediaType.IMAGE})\n"
    android_both = android + "  override val mediaTypes = setOf(MediaType.IMAGE, MediaType.VIDEO)\n"

    def read(source, *patterns):
        return parity.values(source, *patterns)["d.media_types"]

    lab_patterns = (parity.LAB_ID, parity.LAB_VERSION, parity.LAB_CONSTANT)
    android_patterns = (parity.ANDROID_ID, parity.ANDROID_VERSION, parity.ANDROID_CONSTANT)
    assert read(lab, *lab_patterns) == read(android, *android_patterns) == "image"
    assert read(lab_both, *lab_patterns) == read(android_both, *android_patterns) == "image,video"


def test_different_media_types_are_a_mismatch():
    problems = parity.mismatches({"d.media_types": "image,video"}, {"d.media_types": "image"})
    assert problems == ["d.media_types: lab image,video, android image"]


def test_a_media_types_declaration_that_wraps_over_lines_is_read_whole():
    lab = (
        'class D:\n    id = "d"\n    media_types = frozenset(\n'
        "        {MediaType.IMAGE,\n         MediaType.VIDEO}\n    )\n"
    )
    android = (
        'class D {\n  override val id = "d"\n  override val mediaTypes: Set<MediaType> =\n'
        "    setOf(\n      MediaType.IMAGE,\n      MediaType.VIDEO,\n    )\n}\n"
    )
    android_getter = (
        'class D {\n  override val id = "d"\n  override val mediaTypes get() = setOf(\n'
        "    MediaType.IMAGE, MediaType.VIDEO)\n}\n"
    )

    lab_found = parity.values(lab, parity.LAB_ID, parity.LAB_VERSION, parity.LAB_CONSTANT)
    android_found = [
        parity.values(source, parity.ANDROID_ID, parity.ANDROID_VERSION, parity.ANDROID_CONSTANT)
        for source in (android, android_getter)
    ]
    assert [lab_found, *android_found] == [{"d.media_types": "image,video"}] * 3
