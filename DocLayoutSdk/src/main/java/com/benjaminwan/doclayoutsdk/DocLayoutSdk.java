package com.benjaminwan.doclayoutsdk;

import android.content.Context;
import android.graphics.Bitmap;

import com.benjaminwan.ocrlibrary.LayoutResult;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DocLayoutSdk Java API
 *
 * 完整文档版面分析SDK，提供以下功能：
 * 1. 版面分析 - 检测文档中的标题、文本、图片、表格、公式等区域
 * 2. 内容识别 - 对非图片区域进行OCR识别
 * 3. 图片裁剪 - 自动裁剪并保存figure和表格区域
 * 4. Markdown生成 - 生成标准Markdown格式文档
 * 5. HTML预览 - 生成可用于WebView展示的HTML
 * 6. 进度回调 - 支持处理进度实时反馈
 *
 * 第三方集成示例:
 *
 * // 1. 在 Application.onCreate 中初始化
 * DocLayoutSdk.init(context);
 *
 * // 2. 简单分析
 * DocLayoutSdk.analyzeAsync(bitmap, new AnalysisCallback() {
 *     @Override
 *     public void onSuccess(LayoutResult result) {
 *         String markdown = result.getMarkdown();
 *         // 使用结果
 *     }
 *     @Override
 *     public void onError(String error) {
 *         // 处理错误
 *     }
 * });
 *
 * // 3. 完整分析并导出（带进度）
 * DocLayoutSdk.analyzeAndExportWithProgressAsync(bitmap, 0.1f, "document",
 *     Executors.newSingleThreadExecutor(),
 *     new ExportProgressCallback() {
 *         @Override
 *         public void onProgress(ProgressUpdate update) {
 *             // 更新UI: progressBar.setProgress(update.progress);
 *             // 更新UI: textView.setText(update.message);
 *         }
 *         @Override
 *         public void onSuccess(ExportResult result) {
 *             String htmlPath = result.htmlPath;      // HTML文件路径
 *             String mdPath = result.mdPath;          // Markdown文件路径
 *             String markdown = result.markdown;      // Markdown内容
 *             File resourceDir = result.resourceDir;  // 资源目录(含图片)
 *             int figureCount = result.figureCount;   // 图片数量
 *             int tableCount = result.tableCount;     // 表格数量
 *         }
 *         @Override
 *         public void onError(String error) {
 *             // 处理错误
 *         }
 *     });
 *
 * // 4. 显示HTML预览（使用WebView）
 * String htmlContent = DocLayoutAnalyzer.getInstance().generateHtmlPreview(markdown);
 * webView.loadDataWithBaseURL("file:///sdcard/", htmlContent, "text/html", "UTF-8", null);
 */
public class DocLayoutSdk {
    private static DocLayoutAnalyzer instance;
    private static Context applicationContext;
    private static final Executor WORK_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 初始化 SDK（需要在 Application.onCreate 中调用）
     */
    public static synchronized void init(Context context) {
        applicationContext = context.getApplicationContext();
        instance = DocLayoutAnalyzer.init(applicationContext);
    }

    /**
     * 获取单例实例（需先调用 init）
     */
    public static DocLayoutAnalyzer getInstance() {
        return instance;
    }

    // ==================== 异步分析API ====================

    /**
     * 异步执行版面分析
     * @param bitmap 输入图片
     * @param callback 回调接口
     */
    public static void analyzeAsync(Bitmap bitmap, AnalysisCallback callback) {
        analyzeAsync(bitmap, 0.1f, WORK_EXECUTOR, callback);
    }

    /**
     * 异步执行版面分析（带阈值参数）
     * @param bitmap 输入图片
     * @param layoutScoreThresh 版面分析置信度阈值
     * @param callback 回调接口
     */
    public static void analyzeAsync(Bitmap bitmap, float layoutScoreThresh, AnalysisCallback callback) {
        analyzeAsync(bitmap, layoutScoreThresh, WORK_EXECUTOR, callback);
    }

    /**
     * 异步执行版面分析（带执行器）
     * @param bitmap 输入图片
     * @param layoutScoreThresh 版面分析置信度阈值
     * @param executor 执行器
     * @param callback 回调接口
     */
    public static void analyzeAsync(Bitmap bitmap, float layoutScoreThresh,
                                    Executor executor, AnalysisCallback callback) {
        instance.analyzeAsync(bitmap, layoutScoreThresh, executor, callback);
    }

    // ==================== 完整导出API ====================

    /**
     * 异步执行完整分析并导出
     * @param bitmap 输入图片
     * @param baseFileName 基础文件名
     * @param callback 回调接口
     */
    public static void analyzeAndExportAsync(Bitmap bitmap, String baseFileName, ExportResultCallback callback) {
        analyzeAndExportAsync(bitmap, 0.1f, baseFileName, WORK_EXECUTOR, callback);
    }

    /**
     * 异步执行完整分析并导出（带阈值参数）
     */
    public static void analyzeAndExportAsync(Bitmap bitmap, float layoutScoreThresh,
                                             String baseFileName, ExportResultCallback callback) {
        analyzeAndExportAsync(bitmap, layoutScoreThresh, baseFileName, WORK_EXECUTOR, callback);
    }

    /**
     * 异步执行完整分析并导出（带执行器）
     */
    public static void analyzeAndExportAsync(Bitmap bitmap, float layoutScoreThresh,
                                             String baseFileName, Executor executor,
                                             ExportResultCallback callback) {
        instance.analyzeAndExportAsync(bitmap, layoutScoreThresh, baseFileName, executor, callback);
    }

    // ==================== 进度导出API ====================

    /**
     * 异步执行分析并导出（带进度回调）
     * @param bitmap 输入图片
     * @param layoutScoreThresh 版面分析置信度阈值
     * @param baseFileName 基础文件名
     * @param executor 执行器
     * @param callback 回调接口
     */
    public static void analyzeAndExportWithProgressAsync(Bitmap bitmap, float layoutScoreThresh,
                                                         String baseFileName, Executor executor,
                                                         ExportProgressCallback callback) {
        instance.analyzeAndExportWithProgressAsync(bitmap, layoutScoreThresh, baseFileName, executor, callback);
    }

    /**
     * 异步执行分析并导出（带进度回调，默认执行器）
     */
    public static void analyzeAndExportWithProgressAsync(Bitmap bitmap, String baseFileName,
                                                         ExportProgressCallback callback) {
        analyzeAndExportWithProgressAsync(bitmap, 0.1f, baseFileName, WORK_EXECUTOR, callback);
    }

    // ==================== 配置API ====================

    /**
     * 设置是否检测文字方向
     */
    public static void setDoAngle(boolean doAngle) {
        instance.getDoAngle();
        instance.setDoAngle(doAngle);
    }

    /**
     * 设置文字方向检测模式
     */
    public static void setMostAngle(boolean mostAngle) {
        instance.setMostAngle(mostAngle);
    }

    /**
     * 设置Padding
     */
    public static void setPadding(int padding) {
        instance.setPadding(padding);
    }

    /**
     * 设置检测框置信度阈值
     */
    public static void setBoxScoreThresh(float boxScoreThresh) {
        instance.setBoxScoreThresh(boxScoreThresh);
    }

    /**
     * 设置检测框阈值
     */
    public static void setBoxThresh(float boxThresh) {
        instance.setBoxThresh(boxThresh);
    }

    /**
     * 设置UnClipRatio
     */
    public static void setUnClipRatio(float unClipRatio) {
        instance.setUnClipRatio(unClipRatio);
    }

    /**
     * 设置版面分析置信度阈值
     */
    public static void setLayoutScoreThresh(float layoutScoreThresh) {
        instance.setLayoutScoreThresh(layoutScoreThresh);
    }

    /**
     * 获取输出目录
     */
    public static File getOutputDirectory() {
        return instance.getOutputDirectory();
    }

    /**
     * 清理输出目录
     */
    public static void clearOutputDirectory() {
        instance.clearOutputDirectory();
    }
}
