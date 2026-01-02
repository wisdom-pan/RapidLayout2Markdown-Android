package com.benjaminwan.ocr.onnx.dialog

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

/**
 * 全屏Markdown显示对话框
 * 使用WebView渲染Markdown，支持base64嵌入图片显示
 */
class FullMarkdownDialogFragment : DialogFragment() {

    private var markdownContent: String = ""

    companion object {
        private const val ARG_MARKDOWN = "markdown"

        fun newInstance(markdown: String): FullMarkdownDialogFragment {
            return FullMarkdownDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MARKDOWN, markdown)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            markdownContent = it.getString(ARG_MARKDOWN, "")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        // 创建WebView渲染Markdown
        val webView = WebView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 配置WebView设置 - 支持本地文件访问
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = false
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.setSupportZoom(true)
        webSettings.defaultTextEncodingName = "UTF-8"
        // 允许加载本地文件
        webSettings.allowFileAccess = true
        webSettings.domStorageEnabled = true

        // 将Markdown转换为HTML并加载 - 使用baseUrl为file://以支持相对路径
        val htmlContent = markdownToHtml(markdownContent)
        // 使用 file:///sdcard/ 作为 baseUrl
        webView.loadDataWithBaseURL("file:///sdcard/", htmlContent, "text/html", "UTF-8", null)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_NoTitleBar_Fullscreen)
            .setTitle("完整Markdown结果")
            .setView(webView)
            .setPositiveButton("复制全部") { _, _ ->
                copyToClipboard(markdownContent)
            }
            .setNegativeButton("关闭") { _, _ ->
                dismiss()
            }
            .create()

        // 设置对话框为最大宽度，较大高度
        dialog.setOnShowListener {
            dialog.window?.apply {
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }

        return dialog
    }

    /**
     * 将Markdown转换为HTML，用于WebView渲染
     */
    private fun markdownToHtml(markdown: String): String {
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

            // 逐行处理Markdown
            markdown.lines().forEach { line ->
                when {
                    // 标题
                    line.startsWith("### ") -> {
                        val title = line.removePrefix("### ").trim()
                        if (title.isNotEmpty()) {
                            appendLine("<h3>$title</h3>")
                        }
                    }
                    // 图片嵌入 - data URI格式
                    line.startsWith("![") && line.contains("data:image/png;base64") -> {
                        appendLine("<div style='text-align:center;margin:12px 0;'>")
                        appendLine("<img src='${line.substringAfter("[").substringBefore("]")}' />")
                        appendLine("</div>")
                    }
                    // 图片嵌入 - 文件路径格式
                    line.startsWith("![") && line.contains("file://") -> {
                        val imgUrl = line.substringAfter("[").substringBefore("]")
                        appendLine("<div style='text-align:center;margin:12px 0;'>")
                        appendLine("<img src='$imgUrl' />")
                        appendLine("</div>")
                    }
                    // 图片标注
                    line.startsWith("**图 ") && line.contains("**") -> {
                        val figNum = line.removePrefix("**图 ").substringBefore("**")
                        appendLine("<p><strong>图 $figNum</strong></p>")
                    }
                    // 图片说明文字
                    line.startsWith("*图 ") && line.endsWith("*") -> {
                        val caption = line.removeSurrounding("*")
                        appendLine("<p><em>$caption</em></p>")
                    }
                    // 表格标注
                    line.startsWith("**表 ") && line.contains("**") -> {
                        val tableNum = line.removePrefix("**表 ").substringBefore("**")
                        appendLine("<p><strong>表 $tableNum</strong></p>")
                    }
                    // 表格说明
                    line.startsWith("*表 ") && line.endsWith("*") -> {
                        val caption = line.removeSurrounding("*")
                        appendLine("<p><em>$caption</em></p>")
                    }
                    // 公式
                    line.startsWith("$$") && line.endsWith("$$") -> {
                        val formula = line.removeSurrounding("$$").trim()
                        if (formula.isNotEmpty()) {
                            appendLine("<p style='text-align:center;font-family:serif;font-size:1.1em;padding:8px;background:#f5f5f5;border-radius:4px;'>$formula</p>")
                        }
                    }
                    // 公式区域
                    line.contains("公式区域") -> {
                        appendLine("<p style='color:#999;font-style:italic;'>公式区域</p>")
                    }
                    // 空行
                    line.isBlank() -> {
                        appendLine("<br>")
                    }
                    // 普通段落
                    else -> {
                        if (line.isNotBlank()) {
                            // 处理粗体
                            val processedLine = line
                                .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
                            appendLine("<p>$processedLine</p>")
                        }
                    }
                }
            }

            appendLine("""</body>""")
            appendLine("""</html>""")
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Full Markdown", text)
        clipboard.setPrimaryClip(clip)
    }
}
