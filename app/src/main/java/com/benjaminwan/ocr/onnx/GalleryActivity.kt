package com.benjaminwan.ocr.onnx

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.afollestad.assent.Permission
import com.afollestad.assent.askForPermissions
import com.afollestad.assent.isAllGranted
import com.afollestad.assent.rationale.createDialogRationale
import com.benjaminwan.ocr.onnx.app.App
import com.benjaminwan.ocr.onnx.databinding.ActivityGalleryBinding
// Dialog classes
import com.benjaminwan.ocr.onnx.dialog.FullMarkdownDialogFragment
import com.benjaminwan.ocr.onnx.utils.decodeUri
import com.benjaminwan.ocr.onnx.utils.showToast
import com.benjaminwan.ocrlibrary.OcrResult
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.orhanobut.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlin.math.max

class GalleryActivity : AppCompatActivity(), View.OnClickListener, SeekBar.OnSeekBarChangeListener {

    private lateinit var binding: ActivityGalleryBinding

    private var selectedImg: Bitmap? = null
    private var ocrResult: OcrResult? = null
    private var detectJob: Job? = null
    private var layoutResult: com.benjaminwan.ocrlibrary.LayoutResult? = null
    private var layoutJob: Job? = null

    private val glideOptions =
        RequestOptions().skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE)

    private fun initViews() {
        binding.selectBtn.setOnClickListener(this)
        binding.detectBtn.setOnClickListener(this)
        binding.resultBtn.setOnClickListener(this)
        binding.debugBtn.setOnClickListener(this)
        binding.benchBtn.setOnClickListener(this)
        binding.stopBtn.setOnClickListener(this)
        // 版面分析按钮
        binding.layoutBtn.setOnClickListener(this)
        binding.layoutResultBtn.setOnClickListener(this)
        binding.markdownBtn.setOnClickListener(this)

        // 测试按钮
        binding.testFigureSkipBtn.setOnClickListener(this)
        binding.testAllBtn.setOnClickListener(this)

        binding.stopBtn.isEnabled = false
        binding.doAngleSw.isChecked = App.ocrEngine.doAngle
        binding.mostAngleSw.isChecked = App.ocrEngine.mostAngle
        updatePadding(App.ocrEngine.padding)
        updateBoxScoreThresh((App.ocrEngine.boxScoreThresh * 100).toInt())
        updateBoxThresh((App.ocrEngine.boxThresh * 100).toInt())
        updateUnClipRatio((App.ocrEngine.unClipRatio * 10).toInt())
        binding.paddingSeekBar.setOnSeekBarChangeListener(this)
        binding.boxScoreThreshSeekBar.setOnSeekBarChangeListener(this)
        binding.boxThreshSeekBar.setOnSeekBarChangeListener(this)
        binding.maxSideLenSeekBar.setOnSeekBarChangeListener(this)
        binding.scaleUnClipRatioSeekBar.setOnSeekBarChangeListener(this)
        binding.doAngleSw.setOnCheckedChangeListener { _, isChecked ->
            App.ocrEngine.doAngle = isChecked
            binding.mostAngleSw.isEnabled = isChecked
        }
        binding.mostAngleSw.setOnCheckedChangeListener { _, isChecked ->
            App.ocrEngine.mostAngle = isChecked
        }

        // 初始化版面分析按钮状态
        binding.layoutBtn.isEnabled = false
        binding.layoutResultBtn.isEnabled = false
        binding.markdownBtn.isEnabled = false

        // 初始化测试按钮
        binding.testFigureSkipBtn.isEnabled = false
        binding.testAllBtn.isEnabled = false

        // 初始化进度显示
        binding.progressLayout.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.ocrEngine.doAngle = true//相册识别时，默认启用文字方向检测
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()
    }

    override fun onResume() {
        super.onResume()
        val rationaleHandler = createDialogRationale(R.string.storage_permission) {
            onPermission(
                Permission.READ_EXTERNAL_STORAGE, "请点击允许"
            )
        }

        if (!isAllGranted(Permission.READ_EXTERNAL_STORAGE)) {
            askForPermissions(
                Permission.READ_EXTERNAL_STORAGE,
                rationaleHandler = rationaleHandler
            ) { result ->
                val permissionGranted: Boolean =
                    result.isAllGranted(
                        Permission.READ_EXTERNAL_STORAGE
                    )
                if (!permissionGranted) {
                    showToast("未获取权限，应用无法正常使用！")
                }
            }
        }
    }

    override fun onClick(view: View?) {
        view ?: return
        when (view.id) {
            R.id.selectBtn -> {
                val intent = Intent(Intent.ACTION_PICK).apply {
                    type = "image/*"
                }
                startActivityForResult(
                    intent, REQUEST_SELECT_IMAGE
                )
            }
            R.id.detectBtn -> {
                val img = selectedImg
                if (img == null) {
                    showToast("请先选择一张图片")
                    return
                }
                val ratio = binding.maxSideLenSeekBar.progress.toFloat() / 100.toFloat()
                val maxSize = max(img.width, img.height)
                val maxSideLen = (ratio * maxSize).toInt()
                detectJob = detect(img, maxSideLen)
            }
            R.id.stopBtn -> {
                detectJob?.cancel()
                clearLoading()
                ocrResult = null
            }
            R.id.resultBtn -> {
                val result = ocrResult ?: return
                // Simple text display dialog instead of removed TextResultDialog
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("识别结果")
                    .setMessage(result.strRes)
                    .setPositiveButton("确定", null)
                    .show()
            }
            R.id.debugBtn -> {
                val result = ocrResult ?: return
                // Simple debug info dialog instead of removed DebugDialog
                val debugInfo = buildString {
                    append("检测时间: ${result.detectTime}ms\n")
                    append("文字块数量: ${result.textBlocks.size}\n")
                    append("DB网络时间: ${result.dbNetTime}ms\n")
                    append("总时间: ${result.detectTime}ms")
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("调试信息")
                    .setMessage(debugInfo)
                    .setPositiveButton("确定", null)
                    .show()
            }
            R.id.benchBtn -> {
                val img = selectedImg
                if (img == null) {
                    showToast("请先选择一张图片")
                    return
                }
                val loop = 50
                showToast("开始循环${loop}次的测试")
                benchmark(img, loop)
            }
            R.id.layoutBtn -> {
                val img = selectedImg
                if (img == null) {
                    showToast("请先选择一张图片")
                    return
                }
                layoutJob = detectLayout(img)
            }
            R.id.layoutResultBtn -> {
                val result = layoutResult ?: return
                showLayoutResults(result)
            }
            R.id.markdownBtn -> {
                val result = layoutResult ?: return
                showMarkdownResults(result)
            }
            R.id.testFigureSkipBtn -> {
                val img = selectedImg
                if (img == null) {
                    showToast("请先选择一张图片")
                    return
                }
                // 测试figure跳过功能
                testFigureSkip(img)
            }
            R.id.testAllBtn -> {
                val img = selectedImg
                if (img == null) {
                    showToast("请先选择一张图片")
                    return
                }
                // 完整测试：先版面分析，再显示结果
                testFullPipeline(img)
            }
            else -> {
            }
        }
    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        seekBar ?: return
        when (seekBar.id) {
            R.id.maxSideLenSeekBar -> {
                updateMaxSideLen(progress)
            }
            R.id.paddingSeekBar -> {
                updatePadding(progress)
            }
            R.id.boxScoreThreshSeekBar -> {
                updateBoxScoreThresh(progress)
            }
            R.id.boxThreshSeekBar -> {
                updateBoxThresh(progress)
            }
            R.id.scaleUnClipRatioSeekBar -> {
                updateUnClipRatio(progress)
            }
            else -> {
            }
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
    }

    private fun updateMaxSideLen(progress: Int) {
        val ratio = progress.toFloat() / 100.toFloat()
        if (selectedImg != null) {
            val img = selectedImg ?: return
            val maxSize = max(img.width, img.height)
            val maxSizeLen = (ratio * maxSize).toInt()
            binding.maxSideLenTv.text = "MaxSideLen:$maxSizeLen(${ratio * 100}%)"
        } else {
            binding.maxSideLenTv.text = "MaxSideLen:0(${ratio * 100}%)"
        }
    }

    private fun updatePadding(progress: Int) {
        binding.paddingTv.text = "Padding:$progress"
        App.ocrEngine.padding = progress
    }

    private fun updateBoxScoreThresh(progress: Int) {
        val thresh = progress.toFloat() / 100.toFloat()
        binding.boxScoreThreshTv.text = "${getString(R.string.box_score_thresh)}:$thresh"
        App.ocrEngine.boxScoreThresh = thresh
    }

    private fun updateBoxThresh(progress: Int) {
        val thresh = progress.toFloat() / 100.toFloat()
        binding.boxThreshTv.text = "BoxThresh:$thresh"
        App.ocrEngine.boxThresh = thresh
    }

    private fun updateUnClipRatio(progress: Int) {
        val scale = progress.toFloat() / 10.toFloat()
        binding.unClipRatioTv.text = "${getString(R.string.box_un_clip_ratio)}:$scale"
        App.ocrEngine.unClipRatio = scale
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        data ?: return
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_SELECT_IMAGE) {
            val imgUri = data.data ?: return
            Glide.with(this).load(imgUri).apply(glideOptions).into(binding.imageView)
            selectedImg = decodeUri(imgUri)
            updateMaxSideLen(binding.maxSideLenSeekBar.progress)
            clearLastResult()

            // 启用版面分析按钮
            binding.layoutBtn.isEnabled = true
            // 启用测试按钮
            binding.testFigureSkipBtn.isEnabled = true
            binding.testAllBtn.isEnabled = true
        }
    }

    private fun showLoading() {
        Glide.with(this).load(R.drawable.loading_anim).into(binding.imageView)
    }

    private fun clearLoading() {
        Glide.with(this).clear(binding.imageView)
    }

    private fun clearLastResult() {
        binding.timeTV.text = ""
        ocrResult = null
        layoutResult = null

        // 重置版面分析按钮状态
        binding.layoutResultBtn.isEnabled = false
        binding.markdownBtn.isEnabled = false
        binding.testFigureSkipBtn.isEnabled = false
        binding.testAllBtn.isEnabled = false

        // 隐藏进度显示
        binding.progressLayout.visibility = View.GONE
    }

    private fun benchmark(img: Bitmap, loop: Int) = flow {
        val aveTime = App.ocrEngine.benchmark(img, loop)
        //showToast("循环${loop}次，平均时间${aveTime}ms")
        emit(aveTime)
    }.flowOn(Dispatchers.IO)
        .onStart {
            showLoading()
            binding.benchBtn.isEnabled = false
        }
        .onCompletion {
            binding.benchBtn.isEnabled = true
            clearLoading()
        }
        .onEach {
            binding.timeTV.text = "循环${loop}次，平均时间${it}ms"
        }
        .launchIn(lifecycleScope)

    private fun detect(img: Bitmap, reSize: Int) = flow {
        val boxImg: Bitmap = Bitmap.createBitmap(
            img.width, img.height, Bitmap.Config.ARGB_8888
        )
        Logger.i("selectedImg=${img.height},${img.width} ${img.config}")
        val start = System.currentTimeMillis()
        val ocrResult = App.ocrEngine.detect(img, boxImg, reSize)
        val end = System.currentTimeMillis()
        val time = "time=${end - start}ms"
        emit(ocrResult)
    }.flowOn(Dispatchers.IO)
        .onStart {
            showLoading()
            binding.detectBtn.isEnabled = false
            binding.stopBtn.isEnabled = true
        }
        .onCompletion {
            binding.detectBtn.isEnabled = true
            binding.stopBtn.isEnabled = false
            binding.resultBtn.callOnClick()
        }
        .onEach {
            ocrResult = it
            binding.timeTV.text = "识别时间:${it.detectTime.toInt()}ms"
            Glide.with(this).load(it.boxImg).apply(glideOptions).into(binding.imageView)
            Logger.i("$it")
        }.launchIn(lifecycleScope)

    private fun detectLayout(img: Bitmap) = flow {
        // 显示进度条
        withContext(Dispatchers.Main) {
            binding.progressLayout.visibility = View.VISIBLE
            binding.progressText.text = "开始版面分析..."
            binding.progressBar.progress = 0
        }

        emit(ProgressUpdate("执行版面分析...", 20))
        kotlinx.coroutines.delay(100)

        val start = System.currentTimeMillis()
        // 创建输出bitmap用于绘制检测框
        val outputImg: Bitmap = Bitmap.createBitmap(
            img.width, img.height, Bitmap.Config.ARGB_8888
        )
        var layoutResult = App.ocrEngine.detectLayout(img, outputImg, App.ocrEngine.layoutScoreThresh)

        emit(ProgressUpdate("识别版面内容...", 60))

        // 对每个版面区域进行OCR识别
        layoutResult = recognizeLayoutContent(img, layoutResult)

        val end = System.currentTimeMillis()

        emit(ProgressUpdate("生成Markdown...", 90))
        kotlinx.coroutines.delay(100)

        emit(ProgressUpdate("完成！", 100))
        kotlinx.coroutines.delay(100)

        emit(layoutResult)
    }.flowOn(Dispatchers.IO)
        .onStart {
            showLoading()
            binding.layoutBtn.isEnabled = false
            binding.layoutResultBtn.isEnabled = false
            binding.markdownBtn.isEnabled = false
        }
        .onCompletion {
            binding.layoutBtn.isEnabled = true
            clearLoading()
            // 隐藏进度条
            withContext(Dispatchers.Main) {
                binding.progressLayout.visibility = View.GONE
            }
        }
        .onEach { result ->
            if (result is ProgressUpdate) {
                // 更新进度显示
                withContext(Dispatchers.Main) {
                    binding.progressText.text = result.message
                    binding.progressBar.progress = result.progress
                }
            } else if (result is com.benjaminwan.ocrlibrary.LayoutResult) {
                layoutResult = result
                binding.timeTV.text = "版面分析时间:${result.layoutNetTime.toInt()}ms"
                // 显示版面分析结果图
                Glide.with(this).load(result.layoutImg).apply(glideOptions).into(binding.imageView)

                // 保存版面分析可视化结果（类似demo.py的layout_res.png）
                saveLayoutVisualization(result.layoutImg, result.layoutNetTime)

                // 启用结果按钮
                binding.layoutResultBtn.isEnabled = true
                binding.markdownBtn.isEnabled = true

                Logger.i("LayoutNet detected ${result.layoutBoxes.size} layout regions in ${result.layoutNetTime}ms")
            }
        }.launchIn(lifecycleScope)

    private fun showLayoutResults(result: com.benjaminwan.ocrlibrary.LayoutResult) {
        val resultInfo = buildString {
            append("版面分析结果:\n\n")
            append("检测到 ${result.layoutBoxes.size} 个区域:\n\n")

            // 按Y坐标排序（从上到下）
            val sortedBoxes = result.layoutBoxes.sortedBy { it.boxPoint[0].y }

            for ((index, box) in sortedBoxes.withIndex()) {
                append("${index + 1}. ${box.typeName}\n")
                append("   置信度: ${(box.score * 100).toInt()}%\n")
                append("   位置: (${box.boxPoint[0].x}, ${box.boxPoint[0].y}) -> (${box.boxPoint[2].x}, ${box.boxPoint[2].y})\n\n")
            }

            append("处理时间: ${result.layoutNetTime.toInt()}ms\n")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("版面分析结果")
            .setMessage(resultInfo)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showMarkdownResults(result: com.benjaminwan.ocrlibrary.LayoutResult) {
        val markdown = result.markdown

        // 创建可以滚动的文本显示
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = markdown
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        scrollView.addView(textView)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Markdown 输出")
            .setView(scrollView)
            .setPositiveButton("复制到剪贴板", null)
            .setNegativeButton("关闭", null)
            .show()
            .apply {
                // 获取按钮并设置点击事件来复制文本
                val positiveButton = getButton(AlertDialog.BUTTON_POSITIVE)
                positiveButton.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Markdown", markdown)
                    clipboard.setPrimaryClip(clip)
                    showToast("Markdown已复制到剪贴板")
                    dismiss()
                }
            }
    }

    /**
     * 显示完整的Markdown结果（使用DialogFragment支持更大尺寸）
     */
    private fun showFullMarkdownResults(result: com.benjaminwan.ocrlibrary.LayoutResult) {
        val markdown = result.markdown

        // 保存Markdown文件到输出目录
        saveMarkdownToFile(markdown)

        // 使用DialogFragment来显示完整内容
        val dialog = FullMarkdownDialogFragment.newInstance(markdown)
        dialog.show(supportFragmentManager, "fullMarkdown")
    }

    // 保存Markdown文件到输出目录
    private fun saveMarkdownToFile(markdown: String): String {
        return try {
            val timestamp = System.currentTimeMillis()
            val fileName = "result_${timestamp}.md"
            val outputDir = getOutputDir()
            val file = File(outputDir, fileName)

            FileOutputStream(file).use { out ->
                out.write(markdown.toByteArray())
            }

            Logger.i("Markdown已保存: ${file.absolutePath}")
            showToast("结果已保存到: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Logger.e("保存Markdown失败: ${e.message}")
            ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detectJob?.cancel()
        layoutJob?.cancel()
    }

    companion object {
        const val REQUEST_SELECT_IMAGE = 666
    }

    // 进度更新数据类
    data class ProgressUpdate(
        val message: String,
        val progress: Int
    )

    // Figure信息数据类
    data class FigureInfo(
        val ocrText: String = "",
        val imagePath: String = ""
    )

    // 对版面区域进行OCR内容识别（跳过figure区域）
    private fun recognizeLayoutContent(
        originalImg: Bitmap,
        layoutResult: com.benjaminwan.ocrlibrary.LayoutResult
    ): com.benjaminwan.ocrlibrary.LayoutResult {
        val updatedLayoutBoxes = mutableListOf<com.benjaminwan.ocrlibrary.LayoutBox>()
        val figureResults = mutableMapOf<Int, FigureInfo>() // 存储figure区域的OCR结果和图片路径
        var figureCount = 0

        // 对每个版面区域进行OCR识别
        layoutResult.layoutBoxes.forEachIndexed { index, layoutBox ->
            try {
                // 判断是否为figure区域
                val isFigure = layoutBox.typeName.lowercase().contains("figure") &&
                        !layoutBox.typeName.lowercase().contains("caption")

                if (isFigure) {
                    val cropRect = android.graphics.Rect(
                        layoutBox.boxPoint[0].x,
                        layoutBox.boxPoint[0].y,
                        layoutBox.boxPoint[2].x,
                        layoutBox.boxPoint[2].y
                    )

                    val safeCropRect = android.graphics.Rect(
                        maxOf(0, cropRect.left),
                        maxOf(0, cropRect.top),
                        minOf(originalImg.width, cropRect.right),
                        minOf(originalImg.height, cropRect.bottom)
                    )

                    // 计算最小尺寸阈值：图片宽度的5%或高度的5%
                    val minWidth = (originalImg.width * 0.05).toInt().coerceAtLeast(30)
                    val minHeight = (originalImg.height * 0.05).toInt().coerceAtLeast(30)

                    // 过滤掉太小的区域（可能是icon）
                    if (safeCropRect.width() < minWidth || safeCropRect.height() < minHeight) {
                        Logger.i("区域${index + 1} 尺寸过小(${safeCropRect.width()}x${safeCropRect.height()})，跳过视为icon")
                        // 作为普通文本区域处理
                        val finalLayoutBox = com.benjaminwan.ocrlibrary.LayoutBox(
                            boxPoint = layoutBox.boxPoint,
                            score = layoutBox.score,
                            type = layoutBox.type,
                            typeName = "plain text|icon区域"
                        )
                        updatedLayoutBoxes.add(finalLayoutBox)
                        return@forEachIndexed
                    }

                    // Figure区域：跳过通用OCR，只裁剪保存图片
                    figureCount++
                    Logger.i("Figure ${figureCount} 检测到，尺寸: ${safeCropRect.width()}x${safeCropRect.height()}")

                    // 裁剪并保存figure图片（不做OCR）
                    val croppedBitmap = Bitmap.createBitmap(
                        originalImg,
                        safeCropRect.left,
                        safeCropRect.top,
                        safeCropRect.width(),
                        safeCropRect.height()
                    )

                    // 保存figure图片
                    val imagePath = saveFigureImage(croppedBitmap, figureCount)

                    // 存储图片路径（不做OCR识别）
                    figureResults[figureCount] = FigureInfo(ocrText = "", imagePath = imagePath)
                    Logger.i("Figure ${figureCount} 图片已保存: $imagePath")

                    croppedBitmap.recycle()

                    // 存储特殊标记
                    val finalLayoutBox = com.benjaminwan.ocrlibrary.LayoutBox(
                        boxPoint = layoutBox.boxPoint,
                        score = layoutBox.score,
                        type = layoutBox.type,
                        typeName = "figure|skipped|figure_${figureCount}"
                    )
                    updatedLayoutBoxes.add(finalLayoutBox)

                } else {
                    // 非figure区域：正常进行OCR识别
                    val cropRect = android.graphics.Rect(
                        layoutBox.boxPoint[0].x,
                        layoutBox.boxPoint[0].y,
                        layoutBox.boxPoint[2].x,
                        layoutBox.boxPoint[2].y
                    )

                    val safeCropRect = android.graphics.Rect(
                        maxOf(0, cropRect.left),
                        maxOf(0, cropRect.top),
                        minOf(originalImg.width, cropRect.right),
                        minOf(originalImg.height, cropRect.bottom)
                    )

                    if (safeCropRect.width() > 10 && safeCropRect.height() > 10) {
                        val croppedBitmap = Bitmap.createBitmap(
                            originalImg,
                            safeCropRect.left,
                            safeCropRect.top,
                            safeCropRect.width(),
                            safeCropRect.height()
                        )

                        val ocrOutput = Bitmap.createBitmap(
                            croppedBitmap.width,
                            croppedBitmap.height,
                            Bitmap.Config.ARGB_8888
                        )

                        val ocrResult = App.ocrEngine.detect(
                            croppedBitmap,
                            ocrOutput,
                            maxSideLen = maxOf(croppedBitmap.width, croppedBitmap.height)
                        )

                        val ocrContent = if (ocrResult.strRes.isNotEmpty()) {
                            ocrResult.strRes.trim()
                        } else {
                            ""
                        }

                        val finalLayoutBox = com.benjaminwan.ocrlibrary.LayoutBox(
                            boxPoint = layoutBox.boxPoint,
                            score = layoutBox.score,
                            type = layoutBox.type,
                            typeName = "${layoutBox.typeName}|$ocrContent"
                        )

                        Logger.i("区域${index + 1}(${layoutBox.typeName}) OCR结果: $ocrContent")
                        updatedLayoutBoxes.add(finalLayoutBox)

                        croppedBitmap.recycle()
                        ocrOutput.recycle()
                    } else {
                        updatedLayoutBoxes.add(layoutBox)
                    }
                }
            } catch (e: Exception) {
                Logger.e("区域${index + 1} OCR识别失败: ${e.message}")
                updatedLayoutBoxes.add(layoutBox)
            }
        }

        // 先创建包含新boxes的中间result
        val newLayoutResult = com.benjaminwan.ocrlibrary.LayoutResult(
            layoutNetTime = layoutResult.layoutNetTime,
            layoutBoxes = ArrayList(updatedLayoutBoxes),
            layoutImg = layoutResult.layoutImg,
            markdown = ""
        )

        // 使用新boxes生成markdown
        val markdown = generateRealMarkdown(newLayoutResult, figureResults)

        // 返回更新后的LayoutResult
        return com.benjaminwan.ocrlibrary.LayoutResult(
            layoutNetTime = layoutResult.layoutNetTime,
            layoutBoxes = ArrayList(updatedLayoutBoxes),
            layoutImg = layoutResult.layoutImg,
            markdown = markdown
        )
    }

    // 保存figure图像到文件并返回文件路径
    private fun saveFigureImage(bitmap: Bitmap, figureNum: Int): String {
        return try {
            val timestamp = System.currentTimeMillis()
            val fileName = "figure_${figureNum}_${timestamp}.png"

            // 保存到公共Pictures目录
            val outputDir = getOutputDir()
            val file = File(outputDir, fileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
            }

            Logger.i("Figure图像已保存: ${file.absolutePath}")

            // 返回文件路径
            file.absolutePath
        } catch (e: Exception) {
            Logger.e("保存figure图像失败: ${e.message}")
            ""
        }
    }

    // 获取输出目录
    private fun getOutputDir(): File {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val outputDir = File(picturesDir, "RapidOcrResult")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        return outputDir
    }

    // 生成简化Markdown格式 - 使用base64嵌入图片
    private fun generateRealMarkdown(
        layoutResult: com.benjaminwan.ocrlibrary.LayoutResult,
        figureResults: Map<Int, FigureInfo> = emptyMap()
    ): String {
        val markdown = StringBuilder()

        // 按Y坐标排序
        val sortedBoxes = layoutResult.layoutBoxes.sortedBy { it.boxPoint[0].y }

        var figureNum = 0
        var tableNum = 0

        for (box in sortedBoxes) {
            val typeName = box.typeName.lowercase()
            val parts = box.typeName.split("|", limit = 2)
            val originalType = parts.getOrElse(0) { "" }
            val ocrContent = if (parts.size > 1) parts[1] else ""

            when {
                // Title - 使用###标题
                originalType == "title" -> {
                    val titleText = ocrContent.trim().ifEmpty { "标题" }
                    markdown.appendLine("### $titleText")
                    markdown.appendLine()
                }
                // Plain text - 直接输出文本
                originalType == "plain text" || originalType == "text" -> {
                    val textContent = ocrContent.trim()
                    if (textContent.isNotEmpty()) {
                        markdown.appendLine(textContent)
                        markdown.appendLine()
                    }
                }
                // Figure - 直接输出HTML img标签
                typeName.contains("figure") && !typeName.contains("caption") -> {
                    figureNum++
                    val figureInfo = figureResults[figureNum]
                    val imagePath = figureInfo?.imagePath ?: ""

                    markdown.appendLine("**图 $figureNum**")
                    if (imagePath.isNotEmpty()) {
                        // 直接输出HTML img标签，使用完整路径
                        markdown.appendLine("<img src=\"file://$imagePath\" style=\"max-width:100%;margin:10px 0;border-radius:4px;\"/>")
                    }
                    markdown.appendLine()
                }
                // Figure caption
                typeName.contains("figure_caption") -> {
                    markdown.appendLine("*图 $figureNum*")
                    markdown.appendLine()
                }
                // Table
                typeName.contains("table") && !typeName.contains("caption") -> {
                    tableNum++
                    markdown.appendLine("**表 $tableNum**")
                    markdown.appendLine()
                }
                // Table caption
                typeName.contains("table_caption") -> {
                    markdown.appendLine("*表 $tableNum*")
                    markdown.appendLine()
                }
                // Formula
                typeName.contains("formula") || typeName.contains("isolate_formula") -> {
                    val formulaText = ocrContent.trim().ifEmpty { "公式区域" }
                    markdown.appendLine("$$formulaText$$")
                    markdown.appendLine()
                }
            }
        }

        return markdown.toString()
    }

    private fun saveLayoutVisualization(layoutImg: Bitmap, processingTime: Double) {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "layout_res_${timestamp}.png"
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val layoutDir = File(picturesDir, "LayoutAnalysis")

            if (!layoutDir.exists()) {
                layoutDir.mkdirs()
            }

            val imageFile = File(layoutDir, filename)

            // 保存图片
            val outputStream = FileOutputStream(imageFile)
            layoutImg.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.close()

            // 通知媒体扫描器
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(imageFile)
            sendBroadcast(mediaScanIntent)

            Logger.i("Layout visualization saved to: ${imageFile.absolutePath}")

            // 显示保存提示
            Toast.makeText(this, "版面分析结果已保存: $filename", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Logger.e("Failed to save layout visualization: ${e.message}")
            Toast.makeText(this, "保存版面分析结果失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 测试Figure跳过功能
     * 只对figure区域进行裁剪和保存，不进行通用OCR
     */
    private fun testFigureSkip(img: Bitmap) {
        flow {
            emit(ProgressUpdate("开始Figure跳过测试...", 10))
            kotlinx.coroutines.delay(100)

            val outputImg: Bitmap = Bitmap.createBitmap(
                img.width, img.height, Bitmap.Config.ARGB_8888
            )

            emit(ProgressUpdate("执行版面分析...", 30))
            kotlinx.coroutines.delay(100)

            // 执行版面分析
            var result = App.ocrEngine.detectLayout(img, outputImg, App.ocrEngine.layoutScoreThresh)

            // 统计figure区域
            val figureCount = result.layoutBoxes.count {
                it.typeName.lowercase().contains("figure") && !it.typeName.lowercase().contains("caption")
            }

            emit(ProgressUpdate("检测到 $figureCount 个Figure区域...", 50))
            kotlinx.coroutines.delay(100)

            // 裁剪保存所有figure区域
            emit(ProgressUpdate("裁剪并保存Figure区域...", 70))

            var savedCount = 0
            result.layoutBoxes.forEachIndexed { index, layoutBox ->
                val isFigure = layoutBox.typeName.lowercase().contains("figure") &&
                        !layoutBox.typeName.lowercase().contains("caption")

                if (isFigure) {
                    savedCount++
                    val cropRect = android.graphics.Rect(
                        layoutBox.boxPoint[0].x,
                        layoutBox.boxPoint[0].y,
                        layoutBox.boxPoint[2].x,
                        layoutBox.boxPoint[2].y
                    )
                    val safeCropRect = android.graphics.Rect(
                        maxOf(0, cropRect.left),
                        maxOf(0, cropRect.top),
                        minOf(img.width, cropRect.right),
                        minOf(img.height, cropRect.bottom)
                    )
                    if (safeCropRect.width() > 10 && safeCropRect.height() > 10) {
                        val croppedBitmap = Bitmap.createBitmap(
                            img,
                            safeCropRect.left,
                            safeCropRect.top,
                            safeCropRect.width(),
                            safeCropRect.height()
                        )
                        saveFigureImage(croppedBitmap, savedCount)
                        croppedBitmap.recycle()
                    }
                }
            }

            emit(ProgressUpdate("保存了 $savedCount 个Figure区域", 90))
            kotlinx.coroutines.delay(100)

            emit(ProgressUpdate("测试完成！", 100))
            kotlinx.coroutines.delay(100)

            // 显示结果
            val testResult = buildString {
                appendLine("=== Figure跳过测试结果 ===")
                appendLine()
                appendLine("检测到的区域数量: ${result.layoutBoxes.size}")
                appendLine("Figure区域数量: $figureCount")
                appendLine("保存的Figure图像: $savedCount")
                appendLine()
                appendLine("区域详情:")
                result.layoutBoxes.forEachIndexed { idx, box ->
                    appendLine("  ${idx + 1}. ${box.typeName} (置信度: ${(box.score * 100).toInt()}%)")
                }
            }

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@GalleryActivity)
                    .setTitle("Figure跳过测试结果")
                    .setMessage(testResult)
                    .setPositiveButton("确定", null)
                    .show()
            }

        }.flowOn(Dispatchers.IO)
            .onStart {
                binding.progressLayout.visibility = View.VISIBLE
                binding.testFigureSkipBtn.isEnabled = false
                binding.testAllBtn.isEnabled = false
            }
            .onCompletion {
                binding.testFigureSkipBtn.isEnabled = true
                binding.testAllBtn.isEnabled = true
                binding.progressLayout.visibility = View.GONE
            }
            .onEach { result ->
                if (result is ProgressUpdate) {
                    withContext(Dispatchers.Main) {
                        binding.progressText.text = result.message
                        binding.progressBar.progress = result.progress
                    }
                }
            }.launchIn(lifecycleScope)
    }

    /**
     * 完整测试流程：版面分析 + Figure跳过 + Markdown生成
     */
    private fun testFullPipeline(img: Bitmap) {
        flow {
            emit(ProgressUpdate("开始完整测试...", 5))
            kotlinx.coroutines.delay(100)

            val outputImg: Bitmap = Bitmap.createBitmap(
                img.width, img.height, Bitmap.Config.ARGB_8888
            )

            emit(ProgressUpdate("1. 执行版面分析...", 20))
            kotlinx.coroutines.delay(100)

            // 执行版面分析
            var layoutRes = App.ocrEngine.detectLayout(img, outputImg, App.ocrEngine.layoutScoreThresh)

            emit(ProgressUpdate("2. 识别版面内容 (跳过Figure)...", 40))
            kotlinx.coroutines.delay(100)

            // 对非figure区域进行OCR
            layoutRes = recognizeLayoutContent(img, layoutRes)

            emit(ProgressUpdate("3. 完成 (Markdown已生成)", 90))
            kotlinx.coroutines.delay(100)

            // 统计
            val figureCount = layoutRes.layoutBoxes.count {
                it.typeName.lowercase().contains("figure") && !it.typeName.lowercase().contains("caption")
            }
            val textCount = layoutRes.layoutBoxes.count {
                it.typeName.lowercase().contains("text") || it.typeName.lowercase().contains("plain")
            }
            val tableCount = layoutRes.layoutBoxes.count {
                it.typeName.lowercase().contains("table") && !it.typeName.lowercase().contains("caption")
            }

            emit(ProgressUpdate("测试完成！", 100))
            kotlinx.coroutines.delay(100)

            withContext(Dispatchers.Main) {
                // 保存结果
                layoutResult = layoutRes
                binding.timeTV.text = "处理时间:${layoutRes.layoutNetTime.toInt()}ms"
                Glide.with(this@GalleryActivity).load(layoutRes.layoutImg).apply(glideOptions).into(binding.imageView)
                binding.layoutResultBtn.isEnabled = true
                binding.markdownBtn.isEnabled = true

                // 显示统计信息
                val statsInfo = "处理完成！\n\n区域统计:\n  - Figure: $figureCount 个\n  - 文本: $textCount 个\n  - 表格: $tableCount 个\n  - 总计: ${layoutRes.layoutBoxes.size} 个\n\n点击\"查看完整Markdown\"按钮查看全部结果"
                AlertDialog.Builder(this@GalleryActivity)
                    .setTitle("完整测试完成")
                    .setMessage(statsInfo)
                    .setPositiveButton("查看完整Markdown") { _, _ ->
                        showFullMarkdownResults(layoutRes)
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }

        }.flowOn(Dispatchers.IO)
            .onStart {
                binding.progressLayout.visibility = View.VISIBLE
                binding.testFigureSkipBtn.isEnabled = false
                binding.testAllBtn.isEnabled = false
            }
            .onCompletion {
                binding.testFigureSkipBtn.isEnabled = true
                binding.testAllBtn.isEnabled = true
            }
            .onEach { result ->
                if (result is ProgressUpdate) {
                    withContext(Dispatchers.Main) {
                        binding.progressText.text = result.message
                        binding.progressBar.progress = result.progress
                    }
                }
            }.launchIn(lifecycleScope)
    }

}