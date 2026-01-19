package com.benjaminwan.ocrlibrary

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap

class OcrEngine(context: Context) {
    companion object {
        const val numThread: Int = 4
    }

    init {
        System.loadLibrary("RapidOcr")
        val ret = init(
            context.assets, numThread,
            "ch_PP-OCRv3_det_infer.onnx",
            "ch_ppocr_mobile_v2.0_cls_infer.onnx",
            "ch_PP-OCRv3_rec_infer.onnx",
            "ppocr_keys_v1.txt",
            "doclayout_yolo_docstructbench_imgsz1024.onnx"
        )
        if (!ret) throw IllegalArgumentException()
    }

    var padding: Int = 50
    var boxScoreThresh: Float = 0.3f  // 降低阈值以识别更多文本
    var boxThresh: Float = 0.15f      // 降低阈值以检测更多文字区域
    var unClipRatio: Float = 1.6f
    var doAngle: Boolean = true
    var mostAngle: Boolean = true

    // DOCLAYOUT_DOCSTRUCTBENCH 专用参数
    var layoutScoreThresh: Float = 0.1f  // 降低阈值以检测更多版面区域

    fun detect(input: Bitmap, output: Bitmap, maxSideLen: Int) =
        detect(
            input, output, padding, maxSideLen,
            boxScoreThresh, boxThresh,
            unClipRatio, doAngle, mostAngle
        )

    external fun init(
        assetManager: AssetManager,
        numThread: Int, detName: String,
        clsName: String, recName: String, keysName: String, layoutName: String
    ): Boolean

    external fun detect(
        input: Bitmap, output: Bitmap, padding: Int, maxSideLen: Int,
        boxScoreThresh: Float, boxThresh: Float,
        unClipRatio: Float, doAngle: Boolean, mostAngle: Boolean
    ): OcrResult

    external fun benchmark(input: Bitmap, loop: Int): Double

    // 版面分析相关方法
    external fun detectLayout(input: Bitmap, output: Bitmap, boxScoreThresh: Float): LayoutResult

    fun detectLayoutWithDefaultThreshold(input: Bitmap, output: Bitmap, boxScoreThresh: Float = layoutScoreThresh): LayoutResult =
        detectLayout(input, output, boxScoreThresh)

}