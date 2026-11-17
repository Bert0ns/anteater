from pathlib import Path

import pytest

from cribrum_lab.detectors import GROUP_KEY, ROLE_KEY, Role
from cribrum_lab.model import (
    Category,
    IndexedMedia,
    MediaRef,
    MediaType,
    Protection,
    ReviewState,
    Signal,
)
from cribrum_lab.ranker import Ranker

THRESHOLDS = {Category.BLURRY: 0.5, Category.SCREENSHOT: 0.8, Category.EXACT_DUPLICATE: 0.5}


def image(name, *signals, state=ReviewState.UNREVIEWED, seen_when_kept=frozenset()):
    return IndexedMedia(MediaRef(Path(name), 0, 0), state, signals, frozenset(seen_when_kept))


def blur(score, version=1):
    return Signal("blur", version, Category.BLURRY, score)


def screenshot(score):
    return Signal("screenshot", 1, Category.SCREENSHOT, score)


def duplicate(group, role):
    score = 0.0 if role == Role.BEST else 1.0
    evidence = {GROUP_KEY: group, ROLE_KEY: role}
    return Signal("exact_duplicate", 1, Category.EXACT_DUPLICATE, score, evidence)


def rank(*images):
    return Ranker(THRESHOLDS).rank(list(images))


def names(candidates):
    return [c.image.ref.path.name for c in candidates]


def test_any_signal_past_its_threshold_makes_a_candidate():
    blurry = image("blurry", blur(0.6), screenshot(0.1))
    screen = image("screen", blur(0.1), screenshot(0.9))
    neither = image("neither", blur(0.4), screenshot(0.7))

    candidates = {c.image.ref.path.name: c for c in rank(blurry, screen, neither)}

    assert candidates["blurry"].reasons == (blur(0.6),)
    assert candidates["screen"].reasons == (screenshot(0.9),)
    assert not candidates["neither"].is_candidate


def test_signal_exactly_at_the_threshold_passes():
    [candidate] = rank(image("a", blur(0.5)))

    assert candidate.is_candidate


def test_category_without_a_threshold_never_passes():
    signal = Signal("document", 1, Category.DOCUMENT, 1.0)

    [candidate] = rank(image("a", signal))

    assert not candidate.is_candidate


def test_review_everything_lists_every_unreviewed_image_candidates_first():
    order = rank(
        image("unflagged", blur(0.45)),
        image("barely", blur(0.55)),
        image("clean"),
        image("sure", blur(0.95)),
        image("trashed", blur(0.9), state=ReviewState.TRASHED),
    )

    assert names(order) == ["sure", "barely", "unflagged", "clean"]


def test_order_compares_categories_by_their_margin_over_the_threshold():
    # 0.85 is barely past the screenshot threshold, 0.7 well past the blur one.
    order = rank(image("screen", screenshot(0.85)), image("blurry", blur(0.7)))

    assert names(order) == ["blurry", "screen"]


def test_by_category_sorts_each_category_by_score():
    order = rank(
        image("a", blur(0.6)),
        image("b", blur(0.9), screenshot(0.95)),
        image("c", screenshot(0.85)),
        image("d", blur(0.1)),
    )

    groups = Ranker.by_category(order)

    assert {category: names(cs) for category, cs in groups.items()} == {
        Category.BLURRY: ["b", "a"],
        Category.SCREENSHOT: ["b", "c"],
    }


def test_best_copy_of_a_group_is_not_a_candidate():
    order = rank(
        image("best", duplicate("g", Role.BEST)), image("copy", duplicate("g", Role.INFERIOR))
    )

    assert [c.is_candidate for c in order] == [True, False]


def test_kept_image_is_not_suggested_again_for_the_same_signals():
    kept = image(
        "kept",
        blur(0.9),
        duplicate("g", Role.INFERIOR),
        state=ReviewState.KEPT,
        seen_when_kept={blur(0.9).identity, duplicate("g", Role.INFERIOR).identity},
    )

    assert rank(kept) == []


def test_new_detector_version_or_score_is_not_a_new_signal():
    kept = image(
        "kept", blur(0.95, version=2), state=ReviewState.KEPT, seen_when_kept={blur(0.2).identity}
    )

    assert rank(kept) == []


def test_kept_image_comes_back_for_a_new_signal_only():
    kept = image(
        "kept",
        blur(0.9),
        duplicate("new-group", Role.INFERIOR),
        state=ReviewState.KEPT,
        seen_when_kept={blur(0.9).identity},
    )

    [candidate] = rank(kept)

    assert candidate.reasons == (duplicate("new-group", Role.INFERIOR),)


def test_kept_best_copy_comes_back_when_it_becomes_the_inferior_one():
    # A higher-resolution copy showed up after the user kept this one.
    kept = image(
        "kept",
        duplicate("g", Role.INFERIOR),
        state=ReviewState.KEPT,
        seen_when_kept={duplicate("g", Role.BEST).identity},
    )

    assert names(rank(kept)) == ["kept"]


def test_kept_image_with_a_new_signal_below_threshold_stays_hidden():
    kept = image("kept", screenshot(0.5), state=ReviewState.KEPT)

    assert rank(kept) == []


def test_threshold_of_one_passes_only_a_perfect_score():
    ranked = Ranker({Category.BLURRY: 1.0}).rank([image("a", blur(1.0)), image("b", blur(0.9))])

    assert [c.is_candidate for c in ranked] == [True, False]


def faces(score=1.0):
    return Signal("faces", 1, Protection.FACES, score, {"faces": "2"})


def test_protection_lowers_an_image_but_keeps_it_a_candidate():
    order = rank(image("people", blur(0.9), faces()), image("wall", blur(0.8)))

    assert names(order) == ["wall", "people"]
    assert all(c.is_candidate for c in order)
    assert order[1].reasons == (blur(0.9),)


def test_protected_candidate_still_comes_before_every_non_candidate():
    order = rank(
        image("people", blur(0.5), faces(), Signal("x", 1, Protection.FAVORITE, 1.0)),
        image("unflagged", blur(0.49)),
    )

    assert names(order) == ["people", "unflagged"]


def test_protection_signal_alone_never_makes_a_candidate():
    [candidate] = rank(image("people", faces()))

    assert not candidate.is_candidate


def test_unique_image_ranks_below_an_equally_scored_duplicate():
    order = rank(
        image("unique", blur(0.9)),
        image("copy", blur(0.9), duplicate("g", Role.INFERIOR)),
        image("best", blur(0.9), duplicate("g", Role.BEST)),
    )

    assert names(order)[-1] == "unique"
    assert order[-1].protection < 1.0


def test_by_category_applies_protection_too():
    order = rank(image("people", blur(0.9), faces()), image("wall", blur(0.8)))

    assert names(Ranker.by_category(order)[Category.BLURRY]) == ["wall", "people"]


def sized(name, size, *signals, media_type=MediaType.VIDEO):
    ref = MediaRef(Path(name), size, 0, media_type)
    return IndexedMedia(ref, ReviewState.UNREVIEWED, signals, frozenset())


def test_by_category_lists_the_larger_of_two_equal_videos_first():
    # The path hash alone would put "small" first.
    order = rank(sized("small", 1_000_000, blur(0.9)), sized("large", 900_000_000, blur(0.9)))

    assert names(Ranker.by_category(order)[Category.BLURRY]) == ["large", "small"]


def test_protection_lowers_a_video_by_its_factor_on_the_size():
    # Faces halve the people video: 1000 MB counts as 500, ahead of 400 MB and behind 600 MB.
    people = sized("people", 1_000_000_000, blur(0.9), faces())

    for size, expected in [(400_000_000, ["people", "plain"]), (600_000_000, ["plain", "people"])]:
        order = rank(people, sized("plain", size, blur(0.9)))
        assert names(Ranker.by_category(order)[Category.BLURRY]) == expected


def test_an_empty_video_keeps_its_score_order():
    # Size 0 would zero both products and leave the order of rank, where the screenshot puts
    # "shot" first, though its blur score is the lower.
    order = rank(sized("blurry", 0, blur(0.9)), sized("shot", 0, blur(0.6), screenshot(1.0)))

    assert names(order) == ["shot", "blurry"]
    assert names(Ranker.by_category(order)[Category.BLURRY]) == ["blurry", "shot"]


def test_an_image_s_size_does_not_change_its_place():
    # The path hash puts "small" first, and an image keeps that order whatever its size.
    order = rank(
        sized("small", 1_000_000, blur(0.9), media_type=MediaType.IMAGE),
        sized("large", 900_000_000, blur(0.9), media_type=MediaType.IMAGE),
    )

    assert names(Ranker.by_category(order)[Category.BLURRY]) == ["small", "large"]


def test_a_sticker_is_listed_only_as_a_sticker():
    sticker = Signal("sticker", 1, Category.STICKER, 1.0)
    order = Ranker({**THRESHOLDS, Category.STICKER: 0.5}).rank(
        [image("sticker", blur(0.9), sticker), image("photo", blur(0.8))]
    )

    groups = Ranker.by_category(order)

    assert {category: names(cs) for category, cs in groups.items()} == {
        Category.BLURRY: ["photo"],
        Category.STICKER: ["sticker"],
    }
    assert all(c.is_candidate for c in order)  # still a candidate, in the same order
    assert names(order) == ["sticker", "photo"]


def test_images_in_an_excluded_folder_are_never_listed_whatever_their_signals():
    ranker = Ranker(THRESHOLDS)
    order = ranker.rank(
        [
            image("/pics/family/a.jpg", blur(0.9)),
            image("/pics/family/2020/b.jpg"),
            image("/pics/family2/c.jpg", blur(0.9)),
        ],
        excluded_folders=[Path("/pics/family")],
    )

    assert names(order) == ["c.jpg"]


def test_equal_likelihoods_are_not_ordered_by_the_date_in_the_file_name():
    dated = [
        f"/DCIM/Camera/IMG_{day}_100000.jpg"
        for day in ("20190101", "20210615", "20240101", "20250301")
    ]

    forward = names(rank(*(image(n, blur(0.9)) for n in dated)))
    backward = names(rank(*(image(n, blur(0.9)) for n in reversed(dated))))

    # Ordered by a hash of the path (xxh3), so neither oldest first nor newest first.
    assert forward == backward == [Path(n).name for n in (dated[1], dated[2], dated[3], dated[0])]


def favorite(score=1.0):
    return Signal("favorite", 1, Protection.FAVORITE, score)


def album(score=1.0):
    return Signal("album", 1, Protection.ALBUM, score)


def test_each_protection_lowers_the_likelihood_by_its_weight():
    group = duplicate("g", Role.INFERIOR)  # in a group, so the uniqueness factor does not apply
    protections = [faces(), favorite(), album(), favorite(0.5), faces(0.0)]

    factors = [rank(image("a", group, p))[0].protection for p in protections]

    assert factors == pytest.approx([0.5, 0.2, 0.4, 0.6, 1.0])


def test_protections_multiply():
    group = duplicate("g", Role.INFERIOR)

    both = rank(image("a", group, faces(), favorite()))[0]
    everything = rank(image("a", group, faces(), favorite(), album()))[0]

    assert both.protection == pytest.approx(0.1)
    assert everything.protection == pytest.approx(0.04)
    assert everything.likelihood == pytest.approx(0.04 * 2)  # a duplicate scores 1, likelihood 2
    assert everything.is_candidate


def test_a_favorite_or_an_album_lowers_an_image_below_an_unprotected_one():
    group = duplicate("g", Role.INFERIOR)

    order = rank(
        image("fav", group, favorite()), image("plain", group), image("album", group, album())
    )

    assert names(order) == ["plain", "album", "fav"]


def video(name, *signals):
    return IndexedMedia(MediaRef(Path(name), 0, 0, MediaType.VIDEO), signals=signals)


def test_a_video_has_the_threshold_of_its_own_where_the_category_has_one():
    ranker = Ranker(THRESHOLDS, {Category.BLURRY: 0.15})

    ranked = ranker.rank([video("clip", blur(0.2)), image("photo", blur(0.2))])

    assert [(c.image.ref.path.name, c.is_candidate) for c in ranked] == [
        ("clip", True),
        ("photo", False),
    ]


def test_the_photos_threshold_cannot_hide_a_video():
    ranker = Ranker({**THRESHOLDS, Category.BLURRY: 1.0}, {Category.BLURRY: 0.15})

    assert names(ranker.rank([video("clip", blur(0.2))])) == ["clip"]
    assert ranker.rank([video("clip", blur(0.2))])[0].is_candidate


def test_a_video_shares_the_threshold_of_a_category_with_no_video_threshold():
    ranker = Ranker(THRESHOLDS, {Category.BLURRY: 0.15})

    clip = ranker.rank([video("clip", screenshot(0.7))])[0]

    assert not clip.is_candidate  # 0.8 for photos and videos alike
    assert ranker.rank([video("clip", screenshot(0.9))])[0].is_candidate


def test_a_short_clip_is_a_candidate_from_its_own_threshold_and_faces_only_lower_it():
    def short_clip(score):
        return Signal("short_clips", 1, Category.SHORT_CLIPS, score)

    ranker = Ranker()
    assert Category.SHORT_CLIPS not in THRESHOLDS  # a video category: photos have no slider for it

    below, at = ranker.rank([video("below", short_clip(0.29)), video("at", short_clip(0.3))])[::-1]
    assert not below.is_candidate
    assert at.is_candidate

    people = video("people", short_clip(0.8), Signal("faces", 1, Protection.FACES, 1.0))
    plain = video("plain", short_clip(0.8))
    assert names(ranker.rank([people, plain])) == ["plain", "people"]
    assert all(c.is_candidate for c in ranker.rank([people, plain]))


def test_a_kept_short_clip_stays_kept_when_a_photo_or_a_mistake_raises_its_score():
    def short_clip(score, **evidence):
        return Signal("short_clips", 1, Category.SHORT_CLIPS, score, evidence)

    kept = short_clip(0.4, duration_chance="0.400")
    stronger = short_clip(0.9, duration_chance="0.400", photo_chance="0.500", mistake="dark")
    clip = IndexedMedia(
        MediaRef(Path("clip"), 0, 0, MediaType.VIDEO),
        ReviewState.KEPT,
        (stronger,),
        frozenset({kept.identity}),
    )

    assert stronger.identity == kept.identity
    assert Ranker().rank([clip]) == []  # a kept image comes back only for a new signal
