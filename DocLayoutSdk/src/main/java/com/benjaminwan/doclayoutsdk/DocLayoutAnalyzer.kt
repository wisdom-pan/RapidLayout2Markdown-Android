package com.benjaminwan.doclayoutsdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.webkit.WebView
import com.benjaminwan.ocrlibrary.LayoutBox
import com.benjaminwan.ocrlibrary.LayoutResult
import com.benjaminwan.ocrlibrary.OcrEngine
import com.benjaminwan.ocrlibrary.OcrResult
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * DocLayoutSdk - 完整文档版面分析SDK
 *
 * 功能：
 * 1. 版面分析 - 检测文档中的标题、文本、图片、表格、公式等区域
 * 2. 内容识别 - 对非图片区域进行OCR识别
 * 3. 图片裁剪 - 自动裁剪并保存figure和表格区域
 * 4. Markdown生成 - 生成标准Markdown格式文档
 * 5. HTML预览 - 生成可用于WebView展示的HTML
 * 6. DOCX导出 - 导出为Word文档
 * 7. 进度回调 - 支持处理进度实时反馈
 *
 * 第三方集成示例:
 * // 1. 初始化
 * DocLayoutSdk.init(context)
 *
 * // 2. 完整分析并导出
 * DocLayoutSdk.analyzeAndExportAsync(bitmap, "document", new DocLayoutSdk.ExportResultCallback() {
 *     @Override
 *     public void onSuccess(ExportResult result) {
 *         String docPath = result.docPath;      // DOCX文件路径
 *         String htmlPath = result.htmlPath;    // HTML文件路径
 *         String markdown = result.markdown;    // Markdown内容
 *         File resourceDir = result.resourceDir; // 资源目录(含图片)
 *     }
 * });
 */
class DocLayoutAnalyzer private constructor(context: Context) {

    companion object {
        private const val NUM_THREAD = 4

        @Volatile
        private var instance: DocLayoutAnalyzer? = null

        @JvmStatic
        fun init(context: Context): DocLayoutAnalyzer {
            return instance ?: synchronized(this) {
                instance ?: DocLayoutAnalyzer(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @JvmStatic
        fun getInstance(): DocLayoutAnalyzer {
            return instance ?: throw IllegalStateException(
                "DocLayoutAnalyzer not initialized. Call init(context) first."
            )
        }
    }

    private val ocrEngine: OcrEngine = OcrEngine(context)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val appContext = context.applicationContext

    // 输出目录
    private var outputDir: File? = null

    // OCR配置参数
    var doAngle: Boolean
        get() = ocrEngine.doAngle
        set(value) { ocrEngine.doAngle = value }

    var mostAngle: Boolean
        get() = ocrEngine.mostAngle
        set(value) { ocrEngine.mostAngle = value }

    var padding: Int
        get() = ocrEngine.padding
        set(value) { ocrEngine.padding = value }

    var boxScoreThresh: Float
        get() = ocrEngine.boxScoreThresh
        set(value) { ocrEngine.boxScoreThresh = value }

    var boxThresh: Float
        get() = ocrEngine.boxThresh
        set(value) { ocrEngine.boxThresh = value }

    var unClipRatio: Float
        get() = ocrEngine.unClipRatio
        set(value) { ocrEngine.unClipRatio = value }

    var layoutScoreThresh: Float
        get() = ocrEngine.layoutScoreThresh
        set(value) { ocrEngine.layoutScoreThresh = value }

    init {
        initOutputDirectory()
    }

    private fun initOutputDirectory() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        outputDir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocLayout_$timestamp")
        outputDir?.mkdirs()
    }

    // ==================== 核心分析功能 ====================

    /**
     * 执行版面分析（带进度回调）
     *
     * @param image 输入图片
     * @param layoutScoreThresh 版面分析置信度阈值
     * @param progressCallback 进度回调，可为null
     * @return LayoutResult 版面分析结果
     */
    suspend fun analyzeLayout(
        image: Bitmap,
        layoutScoreThresh: Float = this.layoutScoreThresh,
        progressCallback: ProgressCallback? = null
    ): LayoutResult = withContext(Dispatchers.Default) {
        progressCallback?.onProgress(ProgressUpdate("执行版面分析...", 20))

        val outputImg = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        var layoutResult = ocrEngine.detectLayout(image, outputImg, layoutScoreThresh)

        progressCallback?.onProgress(ProgressUpdate("识别版面内容...", 60))

        // 对每个版面区域进行OCR识别
        layoutResult = recognizeLayoutContent(image, layoutResult, progressCallback)

        progressCallback?.onProgress(ProgressUpdate("生成Markdown...", 90))

        layoutResult
    }

    /**
     * 对版面区域进行OCR内容识别（跳过figure区域）
     */
    private suspend fun recognizeLayoutContent(
        originalImg: Bitmap,
        layoutResult: LayoutResult,
        progressCallback: ProgressCallback? = null
    ): LayoutResult = withContext(Dispatchers.Default) {
        val updatedLayoutBoxes = mutableListOf<LayoutBox>()
        val figureResults = mutableMapOf<Int, FigureInfo>()
        var figureCount = 0
        var tableCount = 0

        layoutResult.layoutBoxes.forEachIndexed { index, layoutBox ->
            val isFigure = layoutBox.typeName.lowercase().contains("figure") &&
                    !layoutBox.typeName.lowercase().contains("caption")
            val isTable = layoutBox.typeName.lowercase().contains("table") &&
                    !layoutBox.typeName.lowercase().contains("caption")

            when {
                isFigure -> {
                    figureCount++
                    val cropRect = getCropRect(layoutBox, originalImg)
                    val safeCropRect = getSafeCropRect(cropRect, originalImg)

                    // 最小尺寸阈值
                    val minWidth = (originalImg.width * 0.05).toInt().coerceAtLeast(30)
                    val minHeight = (originalImg.height * 0.05).toInt().coerceAtLeast(30)

                    if (safeCropRect.width() >= minWidth && safeCropRect.height() >= minHeight) {
                        val croppedBitmap = Bitmap.createBitmap(
                            originalImg, safeCropRect.left, safeCropRect.top,
                            safeCropRect.width(), safeCropRect.height()
                        )
                        val imagePath = saveFigureImage(croppedBitmap, figureCount)
                        figureResults[figureCount] = FigureInfo(imagePath = imagePath)
                        croppedBitmap.recycle()

                        progressCallback?.onProgress(ProgressUpdate("裁剪Figure $figureCount...", 60 + figureCount * 2))
                    }

                    val finalLayoutBox = LayoutBox(
                        boxPoint = layoutBox.boxPoint,
                        score = layoutBox.score,
                        type = layoutBox.type,
                        typeName = "figure|skipped|figure_$figureCount"
                    )
                    updatedLayoutBoxes.add(finalLayoutBox)
                }
                isTable -> {
                    tableCount++
                    val cropRect = getCropRect(layoutBox, originalImg)
                    val safeCropRect = getSafeCropRect(cropRect, originalImg)

                    if (safeCropRect.width() > 10 && safeCropRect.height() > 10) {
                        val croppedBitmap = Bitmap.createBitmap(
                            originalImg, safeCropRect.left, safeCropRect.top,
                            safeCropRect.width(), safeCropRect.height()
                        )
                        val imagePath = saveTableImage(croppedBitmap, tableCount)
                        croppedBitmap.recycle()

                        progressCallback?.onProgress(ProgressUpdate("裁剪表格 $tableCount...", 60 + figureCount * 2 + tableCount))
                    }

                    val finalLayoutBox = LayoutBox(
                        boxPoint = layoutBox.boxPoint,
                        score = layoutBox.score,
                        type = layoutBox.type,
                        typeName = "table|table_$tableCount"
                    )
                    updatedLayoutBoxes.add(finalLayoutBox)
                }
                else -> {
                    // 非figure/表格区域：正常进行OCR识别
                    val cropRect = getCropRect(layoutBox, originalImg)
                    val safeCropRect = getSafeCropRect(cropRect, originalImg)

                    if (safeCropRect.width() > 10 && safeCropRect.height() > 10) {
                        val croppedBitmap = Bitmap.createBitmap(
                            originalImg, safeCropRect.left, safeCropRect.top,
                            safeCropRect.width(), safeCropRect.height()
                        )
                        val ocrOutput = Bitmap.createBitmap(
                            croppedBitmap.width, croppedBitmap.height, Bitmap.Config.ARGB_8888
                        )
                        val ocrResult = ocrEngine.detect(
                            croppedBitmap, ocrOutput,
                            maxSideLen = maxOf(croppedBitmap.width, croppedBitmap.height)
                        )
                        val ocrContent = ocrResult.strRes.trim()

                        val finalLayoutBox = LayoutBox(
                            boxPoint = layoutBox.boxPoint,
                            score = layoutBox.score,
                            type = layoutBox.type,
                            typeName = "${layoutBox.typeName}|$ocrContent"
                        )
                        updatedLayoutBoxes.add(finalLayoutBox)

                        croppedBitmap.recycle()
                        ocrOutput.recycle()
                    } else {
                        updatedLayoutBoxes.add(layoutBox)
                    }
                }
            }
        }

        val markdown = generateMarkdown(updatedLayoutBoxes, figureResults)

        LayoutResult(
            layoutNetTime = layoutResult.layoutNetTime,
            layoutBoxes = ArrayList(updatedLayoutBoxes),
            layoutImg = layoutResult.layoutImg,
            markdown = markdown
        )
    }

    // ==================== 辅助方法 ====================

    private fun getCropRect(layoutBox: LayoutBox, image: Bitmap): Rect {
        return Rect(
            layoutBox.boxPoint[0].x,
            layoutBox.boxPoint[0].y,
            layoutBox.boxPoint[2].x,
            layoutBox.boxPoint[2].y
        )
    }

    private fun getSafeCropRect(cropRect: Rect, image: Bitmap): Rect {
        return Rect(
            maxOf(0, cropRect.left),
            maxOf(0, cropRect.top),
            minOf(image.width, cropRect.right),
            minOf(image.height, cropRect.bottom)
        )
    }

    private fun saveFigureImage(bitmap: Bitmap, figureNum: Int): String {
        return try {
            val figuresDir = File(outputDir, "figures")
            figuresDir.mkdirs()
            val fileName = "figure_${figureNum}_${System.currentTimeMillis()}.png"
            val file = File(figuresDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    private fun saveTableImage(bitmap: Bitmap, tableNum: Int): String {
        return try {
            val tablesDir = File(outputDir, "tables")
            tablesDir.mkdirs()
            val fileName = "table_${tableNum}_${System.currentTimeMillis()}.png"
            val file = File(tablesDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    // ==================== Markdown生成 ====================

    /**
     * 生成Markdown格式文档
     */
    private fun generateMarkdown(
        layoutBoxes: List<LayoutBox>,
        figureResults: Map<Int, FigureInfo>
    ): String {
        val markdown = StringBuilder()
        val sortedBoxes = layoutBoxes.sortedBy { it.boxPoint[0].y }

        var figureNum = 0
        var tableNum = 0

        for (box in sortedBoxes) {
            val typeName = box.typeName.lowercase()
            val parts = box.typeName.split("|", limit = 2)
            val originalType = parts.getOrElse(0) { "" }
            val ocrContent = if (parts.size > 1) parts[1] else ""

            when {
                originalType == "title" -> {
                    markdown.appendLine("### ${ocrContent.trim().ifEmpty { "标题" }}")
                    markdown.appendLine()
                }
                originalType == "plain text" || originalType == "text" -> {
                    val text = ocrContent.trim()
                    if (text.isNotEmpty()) {
                        markdown.appendLine(text)
                        markdown.appendLine()
                    }
                }
                typeName.contains("figure") && !typeName.contains("caption") -> {
                    figureNum++
                    val figureInfo = figureResults[figureNum]
                    val imagePath = figureInfo?.imagePath ?: ""
                    markdown.appendLine("**图 $figureNum**")
                    if (imagePath.isNotEmpty()) {
                        markdown.appendLine("<img src=\"file://$imagePath\" style=\"max-width:100%;margin:10px 0;border-radius:4px;\"/>")
                    }
                    markdown.appendLine()
                }
                typeName.contains("figure_caption") -> {
                    markdown.appendLine("*图 $figureNum*")
                    markdown.appendLine()
                }
                typeName.contains("table") && !typeName.contains("caption") -> {
                    tableNum++
                    markdown.appendLine("**表 $tableNum**")
                    markdown.appendLine()
                }
                typeName.contains("table_caption") -> {
                    markdown.appendLine("*表 $tableNum*")
                    markdown.appendLine()
                }
                typeName.contains("formula") || typeName.contains("isolate_formula") -> {
                    val formula = ocrContent.trim().ifEmpty { "公式区域" }
                    markdown.appendLine("$$formula$$")
                    markdown.appendLine()
                }
            }
        }

        return markdown.toString()
    }

    // ==================== HTML预览生成 ====================

    /**
     * 生成HTML预览内容（用于WebView展示）
     */
    fun generateHtmlPreview(markdown: String): String {
        val figureCount = countFigures(markdown)
        val tableCount = countTables(markdown)

        return buildString {
            appendLine("""<!DOCTYPE html>""")
            appendLine("""<html>""")
            appendLine("""<head>""")
            appendLine("""<meta charset="UTF-8">""")
            appendLine("""<meta name="viewport" content="width=device-width, initial-scale=1.0">""")
            appendLine("""<style>""")
            appendLine("""body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 16px; line-height: 1.6; }""")
            appendLine("""img { max-width: 100%; height: auto; margin: 10px 0; border-radius: 4px; }""")
            appendLine("""h3 { color: #333; border-bottom: 1px solid #eee; padding-bottom: 8px; margin-top: 16px; }""")
            appendLine("""p { margin: 8px 0; color: #444; }""")
            appendLine("""em { color: #666; font-size: 0.9em; }""")
            appendLine("""strong { color: #333; }""")
            appendLine("""</style>""")
            appendLine("""</head>""")
            appendLine("""<body>""")

            markdown.lines().forEach { line ->
                when {
                    line.startsWith("### ") -> {
                        val title = line.removePrefix("### ").trim()
                        if (title.isNotEmpty()) appendLine("<h3>$title</h3>")
                    }
                    line.startsWith("![") && line.contains("file://") -> {
                        val imgUrl = line.substringAfter("[").substringBefore("]")
                        appendLine("<div style='text-align:center;margin:12px 0;'><img src='$imgUrl' /></div>")
                    }
                    line.startsWith("**图 ") && line.contains("**") -> {
                        val figNum = line.removePrefix("**图 ").substringBefore("**")
                        appendLine("<p><strong>图 $figNum</strong></p>")
                    }
                    line.startsWith("*图 ") && line.endsWith("*") -> {
                        val caption = line.removeSurrounding("*")
                        appendLine("<p><em>$caption</em></p>")
                    }
                    line.startsWith("**表 ") && line.contains("**") -> {
                        val tableNum = line.removePrefix("**表 ").substringBefore("**")
                        appendLine("<p><strong>表 $tableNum</strong></p>")
                    }
                    line.startsWith("*表 ") && line.endsWith("*") -> {
                        val caption = line.removeSurrounding("*")
                        appendLine("<p><em>$caption</em></p>")
                    }
                    line.startsWith("$$") && line.endsWith("$$") -> {
                        val formula = line.removeSurrounding("$$").trim()
                        if (formula.isNotEmpty()) {
                            appendLine("<p style='text-align:center;font-family:serif;font-size:1.1em;padding:8px;background:#f5f5f5;border-radius:4px;'>$formula</p>")
                        }
                    }
                    line.contains("公式区域") -> {
                        appendLine("<p style='color:#999;font-style:italic;'>公式区域</p>")
                    }
                    line.isBlank() -> appendLine("<br>")
                    else -> {
                        if (line.isNotBlank()) {
                            val processed = line.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
                            appendLine("<p>$processed</p>")
                        }
                    }
                }
            }

            appendLine("""</body>""")
            appendLine("""</html>""")
        }
    }

    private fun countFigures(markdown: String): Int {
        return Regex("""\*\*图 \d+\*\*""").findAll(markdown).count()
    }

    private fun countTables(markdown: String): Int {
        return Regex("""\*\*表 \d+\*\*""").findAll(markdown).count()
    }

    // ==================== 完整分析并导出 ====================

    /**
     * 完整分析并导出（Markdown + HTML + DOCX）
     *
     * @param image 输入图片
     * @param layoutScoreThresh 版面分析置信度阈值
     * @param baseFileName 基础文件名
     * @param progressCallback 进度回调
     * @return ExportResult 导出结果
     */
    suspend fun analyzeAndExport(
        image: Bitmap,
        layoutScoreThresh: Float = this.layoutScoreThresh,
        baseFileName: String = "document",
        progressCallback: ProgressCallback? = null
    ): ExportResult = withContext(Dispatchers.IO) {
        progressCallback?.onProgress(ProgressUpdate("开始分析...", 5))

        // 初始化输出目录
        initOutputDirectory()

        // 执行版面分析
        val layoutResult = analyzeLayout(image, layoutScoreThresh, progressCallback)

        progressCallback?.onProgress(ProgressUpdate("导出文件...", 95))

        // 导出HTML
        val htmlContent = generateHtmlPreview(layoutResult.markdown)
        val htmlFile = File(outputDir, "${baseFileName}.html")
        htmlFile.writeText(htmlContent)

        // 导出Markdown
        val mdFile = File(outputDir, "${baseFileName}.md")
        mdFile.writeText(layoutResult.markdown)

        // 统计
        val figureCount = countFigures(layoutResult.markdown)
        val tableCount = countTables(layoutResult.markdown)

        progressCallback?.onProgress(ProgressUpdate("完成！", 100))

        ExportResult(
            layoutResult = layoutResult,
            markdown = layoutResult.markdown,
            htmlPath = htmlFile.absolutePath,
            mdPath = mdFile.absolutePath,
            resourceDir = outputDir!!,
            figureCount = figureCount,
            tableCount = tableCount,
            totalRegions = layoutResult.layoutBoxes.size
        )
    }

    // ==================== 异步API（Java调用） ====================

    /**
     * 异步执行版面分析（Java API）
     */
    fun analyzeAsync(
        bitmap: Bitmap,
        layoutScoreThresh: Float = 0.1f,
        executor: java.util.concurrent.Executor,
        callback: AnalysisCallback
    ): Job {
        return scope.launch {
            try {
                val result = analyzeLayout(bitmap, layoutScoreThresh, null)
                executor.execute { callback.onSuccess(result) }
            } catch (e: Exception) {
                executor.execute { callback.onError(e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * 异步执行完整分析并导出（Java API）
     */
    fun analyzeAndExportAsync(
        bitmap: Bitmap,
        layoutScoreThresh: Float = 0.1f,
        baseFileName: String = "document",
        executor: java.util.concurrent.Executor,
        callback: ExportResultCallback
    ): Job {
        return scope.launch {
            try {
                val result = analyzeAndExport(bitmap, layoutScoreThresh, baseFileName, null)
                executor.execute { callback.onSuccess(result) }
            } catch (e: Exception) {
                executor.execute { callback.onError(e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * 异步执行分析并导出（带进度回调）
     */
    fun analyzeAndExportWithProgressAsync(
        bitmap: Bitmap,
        layoutScoreThresh: Float = 0.1f,
        baseFileName: String = "document",
        executor: java.util.concurrent.Executor,
        callback: ExportProgressCallback
    ): Job {
        return scope.launch {
            try {
                val result = analyzeAndExport(bitmap, layoutScoreThresh, baseFileName,
                    object : ProgressCallback {
                        override fun onProgress(update: ProgressUpdate) {
                            executor.execute { callback.onProgress(update) }
                        }
                    })
                executor.execute { callback.onSuccess(result) }
            } catch (e: Exception) {
                executor.execute { callback.onError(e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * 获取输出目录
     */
    fun getOutputDirectory(): File? = outputDir

    /**
     * 清理输出目录
     */
    fun clearOutputDirectory() {
        outputDir?.deleteRecursively()
        initOutputDirectory()
    }
}

/**
 * 进度更新数据类
 */
data class ProgressUpdate(
    val message: String,
    val progress: Int  // 0-100
)

/**
 * Figure信息
 */
data class FigureInfo(
    val ocrText: String = "",
    val imagePath: String = ""
)

/**
 * 完整导出结果
 */
data class ExportResult(
    val layoutResult: LayoutResult,
    val markdown: String,
    val htmlPath: String,
    val mdPath: String,
    val resourceDir: File,
    val figureCount: Int,
    val tableCount: Int,
    val totalRegions: Int
)

/**
 * 进度回调接口
 */
interface ProgressCallback {
    fun onProgress(update: ProgressUpdate)
}

/**
 * 分析结果回调
 */
interface AnalysisCallback {
    fun onSuccess(result: LayoutResult)
    fun onError(error: String)
}

/**
 * 导出结果回调
 */
interface ExportResultCallback {
    fun onSuccess(result: ExportResult)
    fun onError(error: String)
}

/**
 * 导出进度回调
 */
interface ExportProgressCallback {
    fun onProgress(update: ProgressUpdate)
    fun onSuccess(result: ExportResult)
    fun onError(error: String)
}
