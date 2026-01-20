package com.benjaminwan.ocrlibrary

/**
 * OCR后处理器 - 修复常见的OCR识别错误
 */
object OcrPostProcessor {

    fun fixOcrErrors(text: String): String {
        if (text.isEmpty()) return text

        var fixedText = text

        // 1. 修复常见的误识别字符（按优先级排序）
        fixedText = fixMisrecognizedChars(fixedText)

        // 2. 合并被意外断开的行
        fixedText = mergeBrokenLines(fixedText)

        // 3. 再次修复误识别（可能由合并产生的新问题）
        fixedText = fixMisrecognizedChars(fixedText)

        // 4. 清理多余的空白字符
        fixedText = cleanWhitespace(fixedText)

        // 5. 修复标点符号
        fixedText = fixPunctuation(fixedText)

        return fixedText.trim()
    }

    /**
     * 修复常见的误识别字符
     */
    private fun fixMisrecognizedChars(text: String): String {
        var result = text

        // 常见的误识别字符映射（按优先级排序，从长到短）
        val charMappings = listOf(
            // 括号相关误识别 - 优先处理长模式
            "（包文字" to "（包",
            "（包文字乙" to "（包乙",
            "（包文字甲" to "（包甲",
            "（包文字丙" to "（包丙",
            "（包文字丁" to "（包丁",
            "（包文字" to "（包",
            "（包文" to "（包",
            "（包了" to "（包了",
            "（包" to "（包",

            // 框/大 相关误识别
            "框大墙体内" to "墙体内",
            "框大墙" to "墙",
            "框大" to "",
            "大墙" to "墙",
            "框内" to "内",
            "墙大" to "墙",
            "体内" to "内",
            "框大体内" to "体内",
            "大体内" to "体内",

            // 标点符号误识别
            "，。：" to "，。：",
            "；；" to "；",
            "。。" to "。",
            "，，，," to ",",

            // 其他常见误识别
            "失由" to "损失",
            "切乙方" to "此外乙方",
            "切" to "此",
        )

        // 应用字符映射（精确匹配）
        for ((wrong, correct) in charMappings) {
            result = result.replace(wrong, correct)
        }

        // 移除单独出现的"文字"（通常是误识别，不是中文词语的一部分）
        result = result.replace(Regex("(?<![\\u4e00-\\u9fff])文字(?![\\u4e00-\\u9fff])"), "")

        return result
    }

    /**
     * 合并被意外断开的行
     */
    private fun mergeBrokenLines(text: String): String {
        val lines = text.split("\n").toMutableList()
        val result = mutableListOf<String>()
        val shortLineThreshold = 60 // 降低阈值，更容易合并

        var i = 0
        while (i < lines.size) {
            val currentLine = lines[i].trimEnd()

            // 检查是否需要与下一行合并
            if (i < lines.size - 1) {
                val nextLine = lines[i + 1].trim()
                val isCurrentLineShort = currentLine.length < shortLineThreshold
                val isNextLineShort = nextLine.length < shortLineThreshold
                // 以逗号、顿号等结尾，且下一行不是特殊符号开头
                val endsWithoutTerminalPunct = !currentLine.endsWithAny('。', '！', '？', '：', '；', '》', '」', '\'', '"')
                val nextLineStartsNormal = !nextLine.startsWithAny('。', '，', '、', '！', '？', '：', '；', '（', '【', '《')

                // 合并条件：当前行结尾无句末标点，下一行不是特殊内容开头
                if (endsWithoutTerminalPunct && nextLineStartsNormal) {
                    // 直接拼接，不加换行
                    val mergedLine = currentLine + nextLine
                    result.add(mergedLine)
                    i += 2 // 跳过下一行
                    continue
                }
            }

            result.add(currentLine)
            i++
        }

        return result.joinToString("\n")
    }

    /**
     * 清理多余的空白字符
     */
    private fun cleanWhitespace(text: String): String {
        var result = text

        // 移除行首行尾空格
        result = result.lines().joinToString("\n") { it.trim() }

        // 将多个连续换行替换为单个换行
        result = result.replace(Regex("\n{3,}"), "\n\n")

        // 移除特殊空白字符
        result = result.replace(Regex("[\\t\\r\u00A0\u1680\u180E\u2000-\u200B\u2028\u2029\u202F\u205F\u3000\uFEFF]"), "")

        return result
    }

    /**
     * 修复标点符号
     */
    private fun fixPunctuation(text: String): String {
        var result = text

        // 中文括号配对修复
        result = result.replace(Regex("（+"), "（")
        result = result.replace(Regex("）+"), "）")

        // 修复引号配对
        result = result.replace(Regex("“+"), "“")
        result = result.replace(Regex("”+"), "”")
        result = result.replace(Regex("‘+"), "‘")
        result = result.replace(Regex("’+"), "’")

        // 移除句首多余标点
        result = result.replace(Regex("^[，。、：；]+"), "")

        return result
    }

    private fun String.endsWithAny(vararg chars: Char): Boolean {
        if (this.isEmpty()) return false
        return chars.contains(this.last())
    }

    private fun String.startsWithAny(vararg chars: Char): Boolean {
        if (this.isEmpty()) return false
        return chars.contains(this.first())
    }

    /**
     * 批量修复OCR结果
     */
    fun batchFix(ocrResults: List<String>): List<String> {
        return ocrResults.map { result ->
            if (result.contains("|")) {
                val parts = result.split("|", limit = 2)
                if (parts.size == 2) {
                    "${parts[0]}|${fixOcrErrors(parts[1])}"
                } else {
                    result
                }
            } else {
                fixOcrErrors(result)
            }
        }
    }

    /**
     * 从typeName中提取并修复OCR内容
     */
    fun extractAndFixContent(typeName: String): String {
        if (!typeName.contains("|")) return typeName
        val parts = typeName.split("|", limit = 2)
        return if (parts.size == 2) fixOcrErrors(parts[1]) else typeName
    }
}
