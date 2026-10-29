package io.github.drtron1c.cloudphotos.ai

/**
 * The exact model files the app downloads, pinned by revision and checksum. The CI tests
 * download these same files and fail if anything about them changes.
 */
object FacePins {
    /** YuNet face detector (MIT licence), OpenCV model zoo. */
    const val DET_REPO = "opencv/face_detection_yunet"
    const val DET_REV = "3cc26e7f1014a5ee5d74a42acee58bafc9d0a310"
    const val DET_FILE = "face_detection_yunet_2023mar.onnx"
    const val DET_SIZE = 232_589L
    const val DET_SHA = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"

    /** SFace face recogniser (Apache 2.0), 8-bit version: same matches as full size, a quarter of the download. */
    const val REC_REPO = "opencv/face_recognition_sface"
    const val REC_REV = "3d7082438a6e4551e840c9b2bb60b71e8da4b524"
    const val REC_FILE = "face_recognition_sface_2021dec_int8.onnx"
    const val REC_SIZE = 9_896_933L
    const val REC_SHA = "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a"
}

object TextPins {
    /** PaddleOCR PP-OCRv4 mobile models (Apache 2.0) as converted by RapidOCR. */
    const val REPO = "SWHL/RapidOCR"
    const val REV = "1cfba2e90fc938db55889873735088de210cc173"
    const val DET_FILE = "PP-OCRv4/ch_PP-OCRv4_det_infer.onnx"
    const val DET_SIZE = 4_745_517L
    const val DET_SHA = "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9"
    const val REC_FILE = "PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx"
    const val REC_SIZE = 10_857_958L
    const val REC_SHA = "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"
}
