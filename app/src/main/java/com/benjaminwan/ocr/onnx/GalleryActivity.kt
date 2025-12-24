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
// Dialog classes removed due to epoxy dependencies
// import com.benjaminwan.ocr.onnx.dialog.DebugDialog
// import com.benjaminwan.ocr.onnx.dialog.TextResultDialog
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

    // 对版面区域进行OCR内容识别
    private fun recognizeLayoutContent(
        originalImg: Bitmap,
        layoutResult: com.benjaminwan.ocrlibrary.LayoutResult
    ): com.benjaminwan.ocrlibrary.LayoutResult {
        val updatedLayoutBoxes = mutableListOf<com.benjaminwan.ocrlibrary.LayoutBox>()

        // 对每个版面区域进行OCR识别
        layoutResult.layoutBoxes.forEachIndexed { index, layoutBox ->
            try {
                // 裁剪版面区域
                val cropRect = android.graphics.Rect(
                    layoutBox.boxPoint[0].x,
                    layoutBox.boxPoint[0].y,
                    layoutBox.boxPoint[2].x,
                    layoutBox.boxPoint[2].y
                )

                // 确保裁剪区域在图像范围内
                val safeCropRect = android.graphics.Rect(
                    maxOf(0, cropRect.left),
                    maxOf(0, cropRect.top),
                    minOf(originalImg.width, cropRect.right),
                    minOf(originalImg.height, cropRect.bottom)
                )

                if (safeCropRect.width() > 10 && safeCropRect.height() > 10) {
                    // 裁剪区域图像
                    val croppedBitmap = Bitmap.createBitmap(
                        originalImg,
                        safeCropRect.left,
                        safeCropRect.top,
                        safeCropRect.width(),
                        safeCropRect.height()
                    )

                    // 对裁剪区域进行OCR识别
                    val ocrOutput = Bitmap.createBitmap(
                        croppedBitmap.width,
                        croppedBitmap.height,
                        Bitmap.Config.ARGB_8888
                    )

                    // 使用现有OCR引擎识别内容
                    val ocrResult = App.ocrEngine.detect(
                        croppedBitmap,
                        ocrOutput,
                        maxSideLen = maxOf(croppedBitmap.width, croppedBitmap.height)
                    )

                    // 创建包含OCR内容的LayoutBox
                    val updatedLayoutBox = com.benjaminwan.ocrlibrary.LayoutBox(
                        boxPoint = layoutBox.boxPoint,
                        score = layoutBox.score,
                        type = layoutBox.type,
                        typeName = layoutBox.typeName
                    )

                    // 将OCR识别的真实内容存储在LayoutBox的typeName字段中
                    // 临时解决方案：将OCR结果附加到typeName中
                    val ocrContent = if (ocrResult.strRes.isNotEmpty()) {
                        ocrResult.strRes.trim()
                    } else {
                        ""
                    }

                    // 创建包含真实OCR内容的LayoutBox
                    val finalLayoutBox = com.benjaminwan.ocrlibrary.LayoutBox(
                        boxPoint = layoutBox.boxPoint,
                        score = layoutBox.score,
                        type = layoutBox.type,
                        typeName = "${layoutBox.typeName}|$ocrContent"  // 用|分隔原始类型和OCR内容
                    )

                    Logger.i("区域${index + 1}(${layoutBox.typeName}) OCR结果: $ocrContent")
                    updatedLayoutBoxes.add(finalLayoutBox)
                } else {
                    updatedLayoutBoxes.add(layoutBox)
                }
            } catch (e: Exception) {
                Logger.e("区域${index + 1} OCR识别失败: ${e.message}")
                updatedLayoutBoxes.add(layoutBox)
            }
        }

        // 返回更新后的LayoutResult（需要修改LayoutResult类存储OCR内容）
        return com.benjaminwan.ocrlibrary.LayoutResult(
            layoutNetTime = layoutResult.layoutNetTime,
            layoutBoxes = ArrayList(updatedLayoutBoxes),
            layoutImg = layoutResult.layoutImg,
            markdown = generateRealMarkdown(layoutResult)
        )
    }

    // 生成标准Markdown格式 - 符合渲染规范
    private fun generateRealMarkdown(layoutResult: com.benjaminwan.ocrlibrary.LayoutResult): String {
        val markdown = StringBuilder()
        markdown.appendLine("# Document Layout Analysis\n")

        // 按Y坐标排序
        val sortedBoxes = layoutResult.layoutBoxes.sortedBy { it.boxPoint[0].y }

        // 解析真实OCR内容并统计各类别
        val parsedBoxes = sortedBoxes.map { box ->
            val parts = box.typeName.split("|", limit = 2)
            val originalType = parts[0]
            val ocrContent = if (parts.size > 1) parts[1] else ""
            Triple(box, originalType, ocrContent)
        }

        val stats = parsedBoxes.groupBy { it.second }.mapValues { it.value.size }

        // 文档概览 - 标准Markdown表格
        markdown.appendLine("## Document Summary\n")
        markdown.appendLine("| Type | Count | Description |")
        markdown.appendLine("|------|-------|-------------|")
        markdown.appendLine("| Text | ${stats["text"] ?: 0} | Paragraph content |")
        markdown.appendLine("| Title | ${stats["title"] ?: 0} | Headings |")
        markdown.appendLine("| Figure | ${stats["figure"] ?: 0} | Images/Charts |")
        markdown.appendLine("| Table | ${stats["table"] ?: 0} | Data tables |")
        markdown.appendLine("| List | ${stats["list"] ?: 0} | Lists/Bullet points |")
        markdown.appendLine("| Formula | ${stats["formula"] ?: 0} | Math equations |")
        markdown.appendLine("\n")

        markdown.appendLine("## Document Structure\n")

        parsedBoxes.forEachIndexed { index, (box, type, ocrContent) ->
            when (type) {
                "title" -> {
                    val titleText = if (ocrContent.isNotEmpty()) ocrContent.trim() else "Title ${index + 1}"
                    markdown.appendLine("### $titleText")
                    markdown.appendLine("*Confidence: ${(box.score * 100).toInt()}%*  \n")
                }
                "text" -> {
                    val textContent = if (ocrContent.isNotEmpty()) ocrContent.trim() else "(No text recognized)"
                    markdown.appendLine("#### Text Region")
                    markdown.appendLine("$textContent")
                    markdown.appendLine("*Confidence: ${(box.score * 100).toInt()}%*  \n")
                }
                "figure" -> {
                    val figureText = if (ocrContent.isNotEmpty()) ocrContent.trim() else ""
                    val figureDescription = if (figureText.isNotEmpty()) " - $figureText" else ""
                    markdown.appendLine("#### Figure$figureDescription")
                    markdown.appendLine("```")
                    markdown.appendLine("┌─────────────────────────────────┐")
                    markdown.appendLine("│        IMAGE/CHART REGION        │")
                    markdown.appendLine("├─────────────────────────────────┤")
                    if (ocrContent.isNotEmpty()) {
                        markdown.appendLine("│ Caption: ${figureText.padEnd(27)} │")
                    } else {
                        markdown.appendLine("│ [Image Content]                 │")
                    }
                    markdown.appendLine("├─────────────────────────────────┤")
                    markdown.appendLine("│ Position: (${box.boxPoint[0].x},${box.boxPoint[0].y}) │")
                    markdown.appendLine("│ Size: ${box.boxPoint[2].x-box.boxPoint[0].x}x${box.boxPoint[2].y-box.boxPoint[0].y} │")
                    markdown.appendLine("└─────────────────────────────────┘")
                    markdown.appendLine("```")
                    markdown.appendLine("![Figure${index + 1}](image://region_${index})")
                    markdown.appendLine("\n")
                }
                "table" -> {
                    val tableText = if (ocrContent.isNotEmpty()) ocrContent.trim() else ""
                    markdown.appendLine("#### Table$tableText")
                    markdown.appendLine("``")
                    if (ocrContent.isNotEmpty()) {
                        // 显示原始OCR识别的表格内容，保留结构
                        val rows = ocrContent.split("\n").take(10)
                        rows.forEach { row ->
                            if (row.trim().isNotEmpty()) {
                                markdown.appendLine(row.trim())
                            }
                        }
                    } else {
                        markdown.appendLine("+----------------+----------------+----------------+")
                        markdown.appendLine("| Column 1      | Column 2      | Column 3      |")
                        markdown.appendLine("+----------------+----------------+----------------+")
                        markdown.appendLine("| Data 1        | Data 2        | Data 3        |")
                        markdown.appendLine("+----------------+----------------+----------------+")
                    }
                    markdown.appendLine("```")
                    markdown.appendLine("*Confidence: ${(box.score * 100).toInt()}%*  \n")
                }
                "list" -> {
                    val listText = if (ocrContent.isNotEmpty()) " - ${ocrContent.trim()}" else ""
                    markdown.appendLine("#### List$listText")
                    if (ocrContent.isNotEmpty()) {
                        val items = ocrContent.split("\n").take(8) // 最多显示8项
                        items.forEach { item ->
                            if (item.trim().isNotEmpty()) {
                                val cleanItem = item.trim()
                                    .replace(Regex("^[•·▪▫▬○●■□▪▫▬\\-]+\\s*"), "") // 移除列表符号
                                    .replace(Regex("^\\d+[\\.\\)\\s]+"), "") // 移除数字编号
                                markdown.appendLine("- $cleanItem")
                            }
                        }
                    } else {
                        markdown.appendLine("- Item 1")
                        markdown.appendLine("- Item 2")
                        markdown.appendLine("- Item 3")
                    }
                    markdown.appendLine("")
                }
                "formula" -> {
                    val formulaText = if (ocrContent.isNotEmpty()) ocrContent.trim() else "E = mc^2"
                    markdown.appendLine("#### Formula")
                    markdown.appendLine("```")
                    markdown.appendLine(formulaText)
                    markdown.appendLine("```")
                    markdown.appendLine("*Confidence: ${(box.score * 100).toInt()}%*  \n")
                }
                else -> {
                    val unknownText = if (ocrContent.isNotEmpty()) ocrContent.trim() else ""
                    markdown.appendLine("#### Other Region - $type$unknownText")
                    markdown.appendLine("*Confidence: ${(box.score * 100).toInt()}%*  \n")
                }
            }
        }

        markdown.appendLine("---\n")
        markdown.appendLine("## Technical Details\n")
        markdown.appendLine("- **Analysis Model**: RapidLayout + PP-YOLOE")
        markdown.appendLine("- **OCR Engine**: RapidOCR v3")
        markdown.appendLine("- **Processing Time**: ${layoutResult.layoutNetTime.toInt()}ms")
        markdown.appendLine("- **Detected Regions**: ${layoutResult.layoutBoxes.size}")
        markdown.appendLine("- **Platform**: Android + ONNX Runtime")
        markdown.appendLine("\n")
        markdown.appendLine("*Generated by Layout2Markdown - Layout Analysis + OCR Recognition*")

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


}