import importlib.util
import shlex
from pathlib import Path

_SCRIPT = Path(__file__).parents[1] / "scripts" / "pull_device_videos.py"
_spec = importlib.util.spec_from_file_location("pull_device_videos", _SCRIPT)
pull = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pull)

CAMERA = "DCIM/Camera"
WHATSAPP = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"


def test_the_listing_gives_each_video_its_size_even_with_spaces_in_the_path():
    text = f"123 {WHATSAPP}/Sent/VID-1.mp4\n4567 {CAMERA}/20261101_101010.mp4\n\n"

    assert pull.parse_listing(text) == [
        (123, f"{WHATSAPP}/Sent/VID-1.mp4"),
        (4567, f"{CAMERA}/20261101_101010.mp4"),
    ]


def test_the_sample_takes_as_many_videos_from_each_folder():
    files = [(1, f"{CAMERA}/{i}.mp4") for i in range(10)]
    files += [(1, f"{WHATSAPP}/Sent/{i}.mp4") for i in range(10)]

    picked = pull.pick(files, [CAMERA, WHATSAPP], sample=3)

    assert sum(p.startswith(CAMERA) for _, p in picked) == 3
    assert sum(p.startswith(WHATSAPP) for _, p in picked) == 3


def test_a_larger_sample_keeps_the_videos_of_a_smaller_one():
    files = [(1, f"{CAMERA}/{i}.mp4") for i in range(50)]

    small = pull.pick(files, [CAMERA], sample=5)
    large = pull.pick(files, [CAMERA], sample=12)

    assert len(large) == 12
    assert set(small) <= set(large)


def test_without_a_sample_every_video_is_picked():
    files = [(1, f"{CAMERA}/{i}.mp4") for i in range(7)]

    assert sorted(pull.pick(files, [CAMERA], sample=None)) == sorted(files)


def test_a_folder_named_like_the_start_of_another_does_not_take_its_videos():
    files = [(1, "DCIM/Camera/a.mp4"), (1, "DCIM/CameraBackup/b.mp4")]

    assert pull.pick(files, ["DCIM/Camera"], sample=None) == [(1, "DCIM/Camera/a.mp4")]


def test_the_copy_command_quotes_every_path_for_the_device_shell():
    command = pull.copy_command(["DCIM/it's here.mp4", f"{WHATSAPP}/a b.mp4"])

    words = shlex.split(command)
    assert words[words.index("--") + 1 :][:2] == ["DCIM/it's here.mp4", f"{WHATSAPP}/a b.mp4"]


def test_the_listing_command_only_reads():
    command = pull.listing_command([CAMERA, WHATSAPP])

    assert shlex.quote(WHATSAPP) in command
    for word in ("rm ", "mv ", "-delete", ">"):
        assert word not in command.replace("2>/dev/null", "")


def test_a_video_already_copied_with_the_same_size_is_not_copied_again(tmp_path):
    (tmp_path / "DCIM").mkdir()
    (tmp_path / "DCIM/done.mp4").write_bytes(b"12345")
    (tmp_path / "DCIM/partial.mp4").write_bytes(b"12")
    picked = [(5, "DCIM/done.mp4"), (5, "DCIM/partial.mp4"), (5, "DCIM/new.mp4")]

    assert pull.to_copy(picked, tmp_path) == [(5, "DCIM/partial.mp4"), (5, "DCIM/new.mp4")]
