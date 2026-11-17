"""FORWARDED: memes, stickers and greeting images from messaging apps (docs/design.md, 5).

Three pieces of evidence, combined as independent chances (noisy OR) like SCREENSHOT:
the zero-shot class is the strongest, because messaging folders and missing camera EXIF
also describe every photo a friend sends. The weights are starting points, to be tuned
for recall in the lab.

A video has no zero-shot term: its signal reads no frame and no model, only its folder. On the
49 video labels of #390 the zero-shot class did not tell what the maintainer deleted from what
they kept (docs/design.md, 5).
"""

from cribrum_lab import prompts
from cribrum_lab.detectors.base import AnalysisContext
from cribrum_lab.folders import is_messaging_folder
from cribrum_lab.model import Category, MediaRef, MediaType, Signal

ZERO_SHOT_WEIGHT = 0.9
MESSAGING_FOLDER_WEIGHT = 0.4
NO_CAMERA_EXIF_WEIGHT = 0.1


class ForwardedDetector:
    id = "forwarded"
    version = 2
    category = Category.FORWARDED
    """Bump with prompts.VERSION."""
    uses_embeddings = True
    """Of an image. A video's signal reads no embedding."""
    media_types = frozenset({MediaType.IMAGE, MediaType.VIDEO})

    def analyze(self, image: MediaRef, context: AnalysisContext) -> list[Signal]:
        if image.media_type is MediaType.VIDEO:
            return self._video(image)
        probability, prompt = prompts.classify(context.embedding(image))["forwarded"]
        messaging = is_messaging_folder(image.path)
        camera_exif = context.metadata(image).has_camera_exif
        miss = (
            (1.0 - ZERO_SHOT_WEIGHT * probability)
            * (1.0 - (MESSAGING_FOLDER_WEIGHT if messaging else 0.0))
            * (1.0 - (0.0 if camera_exif else NO_CAMERA_EXIF_WEIGHT))
        )
        evidence = {
            "prompt": prompt,
            "probability": f"{probability:.3f}",
            "messaging_folder": "yes" if messaging else "no",
            "camera_exif": "yes" if camera_exif else "no",
        }
        return [Signal(self.id, self.version, Category.FORWARDED, 1.0 - miss, evidence)]

    def _video(self, video: MediaRef) -> list[Signal]:
        """Every video in a messaging app's folder scores 1, and no other video gets a signal.
        At 1 no position of the category's slider, which the photos share, hides it."""
        if not is_messaging_folder(video.path):
            return []
        evidence = {"messaging_folder": "yes"}
        return [Signal(self.id, self.version, Category.FORWARDED, 1.0, evidence)]
