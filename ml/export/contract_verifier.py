"""Contract Verifier for Android Preprocessing and Coordinate Restoration.

Verifies that the Python letterbox preprocessing and coordinate unletterboxing
equations match Android's VisionTransforms.kt within numerical tolerance (1e-4).
"""


def compute_letterbox_transform(
    src_width: int,
    src_height: int,
    target_width: int,
    target_height: int,
) -> dict[str, float]:
    """Compute scale and offsets for letterbox transformation."""
    scale = min(target_width / src_width, target_height / src_height)
    scaled_w = round(src_width * scale)
    scaled_h = round(src_height * scale)
    pad_left = (target_width - scaled_w) / 2.0
    pad_top = (target_height - scaled_h) / 2.0

    return {
        "scale": scale,
        "scaled_w": float(scaled_w),
        "scaled_h": float(scaled_h),
        "pad_left": pad_left,
        "pad_top": pad_top,
        "target_w": float(target_width),
        "target_h": float(target_height),
        "src_w": float(src_width),
        "src_h": float(src_height),
    }


def unletterbox_box(
    box_tensor: tuple[float, float, float, float],
    transform: dict[str, float],
) -> tuple[float, float, float, float]:
    """Convert tensor normalized box [0, 1] back to original frame normalized box [0, 1]."""
    left_t, top_t, right_t, bottom_t = box_tensor

    px_left = left_t * transform["target_w"]
    px_top = top_t * transform["target_h"]
    px_right = right_t * transform["target_w"]
    px_bottom = bottom_t * transform["target_h"]

    orig_x1 = max(0.0, (px_left - transform["pad_left"]) / transform["scale"])
    orig_y1 = max(0.0, (px_top - transform["pad_top"]) / transform["scale"])
    orig_x2 = max(0.0, (px_right - transform["pad_left"]) / transform["scale"])
    orig_y2 = max(0.0, (px_bottom - transform["pad_top"]) / transform["scale"])

    norm_x1 = min(1.0, orig_x1 / transform["src_w"])
    norm_y1 = min(1.0, orig_y1 / transform["src_h"])
    norm_x2 = min(1.0, orig_x2 / transform["src_w"])
    norm_y2 = min(1.0, orig_y2 / transform["src_h"])

    return (round(norm_x1, 5), round(norm_y1, 5), round(norm_x2, 5), round(norm_y2, 5))


def verify_android_golden_contract() -> bool:
    """Golden test verifying agreement with VisionTransformsGoldenTest in Android.

    Source frame: 640x480
    Target tensor: 320x320
    Tensor box: [0.1, 0.25, 0.9, 0.75]
    Expected restored box in [0, 1] of 640x480:
    - left: 0.1, right: 0.9
    - top: 0.16667, bottom: 0.83333
    """
    transform = compute_letterbox_transform(640, 480, 320, 320)
    restored = unletterbox_box((0.1, 0.25, 0.9, 0.75), transform)

    # In 640x480 with 40px top pad:
    # pad_top = 40. px_top = 0.25 * 320 = 80. (80 - 40) / 0.5 = 80px in 480 -> 80/480 = 0.16667
    # px_bottom = 0.75 * 320 = 240. (240 - 40) / 0.5 = 400px in 480 -> 400/480 = 0.83333
    is_left_ok = abs(restored[0] - 0.1) < 1e-4
    is_top_ok = abs(restored[1] - 0.16667) < 1e-4
    is_right_ok = abs(restored[2] - 0.9) < 1e-4
    is_bottom_ok = abs(restored[3] - 0.83333) < 1e-4

    return is_left_ok and is_top_ok and is_right_ok and is_bottom_ok
