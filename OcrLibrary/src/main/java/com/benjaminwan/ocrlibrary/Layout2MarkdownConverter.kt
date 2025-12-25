package com.benjaminwan.ocrlibrary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 版面分析结果转Markdown格式转换器
 *
 * 功能：
 * 1. 按阅读顺序排列版面元素
 * 2. 裁剪并保存图片区域（figure跳过OCR）
 * 3. 识别表格区域并转换为Markdown表格格式
 * 4. 生成完整的Markdown文档
 *
 * 处理策略：
 * - Figure区域：裁剪保存，跳过通用OCR，单独处理
 * - 文本区域：正常进行OCR识别
 * - 表格区域：裁剪保存，用于表格识别
 */
class Layout2MarkdownConverter(private val context: Context) {

    companion object {
        // 元素类型映射
        private val FIGURE_TYPES = setOf("figure", "image", "picture", "photo")
        private val TABLE_TYPES = setOf("table")
        private val TEXT_TYPES = setOf("text", "plain_text", "paragraph", "content")
        private val TITLE_TYPES = setOf("title", "heading", "header", "section_header")
        private val CAPTION_TYPES = setOf("figure_caption", "table_caption", "caption", "figcaption")
        private val EQUATION_TYPES = setOf("isolate_formula", "formula", "math", "formula_earse")
        private val REFERENCE_TYPES = setOf("reference", "footnote", "cite")

        // 颜色配置
        private val TYPE_COLORS = mapOf(
            "title" to "#FF5722",
            "figure" to "#2196F3",
            "table" to "#4CAF50",
            "text" to "#9E9E9E",
            "equation" to "#9C27B0",
            "caption" to "#FF9800"
        )

        // Figure区域特殊标记
        private const val FIGURE_SKIP_MARKER = "figure|skipped|figure_"
    }

    // 资源保存目录
    private var resourceDir: File? = null
    private var figureCount = 0
    private var tableCount = 0

    /**
     * 将版面分析结果转换为Markdown格式
     *
     * @param layoutResult 版面分析结果
     * @param originalImage 原图（用于裁剪区域）
     * @param saveResources 是否保存裁剪的图片资源
     * @param skipFigureOcr 是否跳过figure区域的OCR识别（true=裁剪保存figure，单独处理）
     * @return 转换后的Markdown字符串
     */
    fun convert(
        layoutResult: LayoutResult,
        originalImage: Bitmap,
        saveResources: Boolean = true,
        skipFigureOcr: Boolean = true
    ): String {
        // 初始化资源目录
        if (saveResources) {
            initResourceDirectory()
            figureCount = 0
            tableCount = 0
        }

        // 按阅读顺序排序
        val sortedBoxes = sortByReadingOrder(layoutResult.layoutBoxes)

        // 构建Markdown内容
        val markdown = StringBuilder()
        val elements = mutableListOf<MarkdownElement>()

        // 用于跟踪当前上下文
        var lastFigureNum = 0
        var lastTableNum = 0
        // 存储figure区域的OCR结果（如果单独处理）
        val figureOcrResults = mutableMapOf<Int, String>()

        for ((index, layoutBox) in sortedBoxes.withIndex()) {
            val elementType = getElementType(layoutBox.typeName)
            val content = StringBuilder()

            when (elementType) {
                "title" -> {
                    content.append("## ")
                }
                "text" -> {
                    // 文本内容由OCR识别填充
                    content.append("")
                }
                "figure" -> {
                    figureCount++
                    val figureNum = figureCount

                    // 检查是否为跳过的figure区域
                    val isSkippedFigure = layoutBox.typeName.startsWith(FIGURE_SKIP_MARKER)

                    if (saveResources) {
                        // 裁剪并保存figure图片
                        val figureImage = cropBitmap(originalImage, layoutBox.boxPoint)
                        if (figureImage != null) {
                            val figureFileName = "figure_${figureNum}_${System.currentTimeMillis()}.png"
                            saveBitmap(figureImage, figureFileName)

                            if (skipFigureOcr) {
                                // 跳过通用OCR，figure区域只保存图片
                                content.append("![图 $figureNum](figures/$figureFileName)")
                            } else {
                                // 正常OCR处理
                                val ocrContent = extractOcrContent(layoutBox.typeName)
                                content.append("![图 $figureNum](figures/$figureFileName)")
                                if (ocrContent.isNotEmpty()) {
                                    content.append("\n\n*Figure内容: $ocrContent*")
                                }
                            }
                            figureImage.recycle()
                        }
                    } else {
                        content.append("![图 $figureNum]()")
                    }

                    // 如果是跳过的figure，提取figure编号并存储标记
                    if (isSkippedFigure) {
                        lastFigureNum = figureNum
                    }
                }
                "figure_caption" -> {
                    // 图注，使用typeName中的文本或空
                    content.append("*图 ${lastFigureNum}*")
                }
                "table" -> {
                    tableCount++
                    val tableNum = tableCount

                    if (saveResources) {
                        // 裁剪并保存表格图片（用于后续表格识别）
                        val tableImage = cropBitmap(originalImage, layoutBox.boxPoint)
                        if (tableImage != null) {
                            val tableFileName = "table_${tableNum}_${System.currentTimeMillis()}.png"
                            // 保存到tables子目录
                            val tablesDir = File(resourceDir, "tables")
                            val file = File(tablesDir, tableFileName)
                            try {
                                FileOutputStream(file).use { out ->
                                    tableImage.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            tableImage.recycle()
                        }
                    }

                    // 表格占位符 - 实际表格内容需要表格识别模型
                    content.append("**表 $tableNum**\n\n")
                    content.append("|  |  |  |\n")
                    content.append("|---|---|---|\n")
                    content.append("|  |  |  |\n")
                    content.append("|  |  |  |")
                }
                "table_caption" -> {
                    content.append("*表 ${lastTableNum}*")
                }
                "equation" -> {
                    // 公式区域
                    content.append("\$\$formula\$\$")
                }
                "reference" -> {
                    content.append("> ")
                }
                else -> {
                    content.append("")
                }
            }

            // 记录元素
            val element = MarkdownElement(
                type = elementType,
                content = content.toString(),
                bbox = layoutBox.boxPoint,
                score = layoutBox.score,
                order = index
            )
            elements.add(element)

            // 更新上下文
            if (elementType == "figure") lastFigureNum = figureCount
            if (elementType == "table") lastTableNum = tableCount

            // 添加到Markdown
            markdown.append(element.content)
            if (element.content.isNotEmpty() && !element.content.endsWith("\n")) {
                markdown.append("\n")
            }
            markdown.append("\n")
        }

        return markdown.toString()
    }

    /**
     * 排序为阅读顺序（从上到下，从左到右）
     */
    private fun sortByReadingOrder(boxes: ArrayList<LayoutBox>): ArrayList<LayoutBox> {
        // 按y坐标排序（主要），然后按x坐标排序（次要）
        val sorted = boxes.sortedWith(compareBy(
            { it.boxPoint.minOf { p -> p.y } },
            { it.boxPoint.minOf { p -> p.x } }
        )).toMutableList()

        // 处理同一行的元素
        val result = ArrayList<LayoutBox>()
        val rowThreshold = 30 // 像素阈值
        var currentRow = mutableListOf<LayoutBox>()

        for (box in sorted) {
            val boxY = box.boxPoint.minOf { it.y }

            if (currentRow.isEmpty()) {
                currentRow.add(box)
            } else {
                val lastBox = currentRow.last()
                val lastY = lastBox.boxPoint.minOf { it.y }

                if (kotlin.math.abs(boxY - lastY) < rowThreshold) {
                    currentRow.add(box)
                } else {
                    // 按x坐标排序当前行
                    currentRow.sortBy { it.boxPoint.minOf { p -> p.x } }
                    result.addAll(currentRow)
                    currentRow = mutableListOf(box)
                }
            }
        }

        // 处理最后一行
        if (currentRow.isNotEmpty()) {
            currentRow.sortBy { it.boxPoint.minOf { p -> p.x } }
            result.addAll(currentRow)
        }

        return ArrayList(result)
    }

    /**
     * 获取元素类型
     */
    private fun getElementType(typeName: String): String {
        val lowerName = typeName.lowercase()
        return when {
            FIGURE_TYPES.any { lowerName.contains(it) } -> "figure"
            TABLE_TYPES.any { lowerName.contains(it) } -> "table"
            TEXT_TYPES.any { lowerName.contains(it) } -> "text"
            TITLE_TYPES.any { lowerName.contains(it) } -> "title"
            CAPTION_TYPES.any { lowerName.contains(it) } -> {
                if (lowerName.contains("figure")) "figure_caption"
                else if (lowerName.contains("table")) "table_caption"
                else "caption"
            }
            EQUATION_TYPES.any { lowerName.contains(it) } -> "equation"
            REFERENCE_TYPES.any { lowerName.contains(it) } -> "reference"
            else -> "text"
        }
    }

    /**
     * 从typeName中提取OCR内容
     * 格式：typeName|ocrContent 或 figure|skipped|figure_N
     */
    private fun extractOcrContent(typeName: String): String {
        if (typeName.contains("|")) {
            val parts = typeName.split("|", limit = 2)
            if (parts.size >= 2) {
                // 跳过figure标记
                if (parts[0] == "figure" && parts[1] == "skipped") {
                    return ""
                }
                return parts[1].trim()
            }
        }
        return ""
    }

    /**
     * 根据多边形坐标裁剪Bitmap
     */
    private fun cropBitmap(source: Bitmap, points: ArrayList<Point>): Bitmap? {
        if (points.size < 4) return null

        // 计算边界矩形
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY

        if (width <= 0 || height <= 0) return null

        // 创建目标区域
        val destRect = Rect(0, 0, width, height)

        // 裁剪
        return Bitmap.createBitmap(
            source,
            minX.coerceAtLeast(0),
            minY.coerceAtLeast(0),
            width.coerceAtMost(source.width - minX),
            height.coerceAtMost(source.height - minY)
        )
    }

    /**
     * 初始化资源保存目录
     */
    private fun initResourceDirectory() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        resourceDir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "layout2md_$timestamp")
        resourceDir?.mkdirs()

        // 创建images子目录（用于一般图片）
        File(resourceDir, "images").mkdirs()
        // 创建figures子目录（用于figure区域裁剪）
        File(resourceDir, "figures").mkdirs()
        // 创建tables子目录（用于表格区域裁剪）
        File(resourceDir, "tables").mkdirs()
    }

    /**
     * 保存Bitmap到文件
     */
    private fun saveBitmap(bitmap: Bitmap, fileName: String): String? {
        if (resourceDir == null) return null

        try {
            val imagesDir = File(resourceDir, "images")
            val file = File(imagesDir, fileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            return file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 可视化版面分析结果（调试用）
     */
    fun visualizeLayout(
        originalImage: Bitmap,
        layoutBoxes: ArrayList<LayoutBox>,
        savePath: String? = null
    ): Bitmap {
        val result = originalImage.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        for (layoutBox in layoutBoxes) {
            val type = getElementType(layoutBox.typeName)
            val color = Color.parseColor(TYPE_COLORS[type] ?: "#9E9E9E")
            paint.color = color

            val points = layoutBox.boxPoint
            if (points.size >= 4) {
                // 绘制多边形
                val path = android.graphics.Path()
                path.moveTo(points[0].x.toFloat(), points[0].y.toFloat())
                for (i in 1 until points.size) {
                    path.lineTo(points[i].x.toFloat(), points[i].y.toFloat())
                }
                path.close()
                canvas.drawPath(path, paint)

                // 绘制标签
                val labelPaint = Paint().apply {
                    this.color = this@apply.color
                    style = Paint.Style.FILL
                    textSize = 32f
                }
                val labelX = points.minOf { it.x }.toFloat()
                val labelY = points.minOf { it.y }.toFloat() - 10
                canvas.drawText(layoutBox.typeName, labelX, labelY, labelPaint)
            }
        }

        // 保存到文件
        savePath?.let {
            try {
                FileOutputStream(it).use { out ->
                    result.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return result
    }

    /**
     * 获取资源目录路径
     */
    fun getResourceDirectory(): File? = resourceDir
}

/**
 * Markdown元素数据类
 */
data class MarkdownElement(
    val type: String,
    val content: String,
    val bbox: ArrayList<Point>,
    val score: Float,
    val order: Int
)
