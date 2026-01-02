# DocLayoutSdk

文档版面分析 SDK，提供版面分析、OCR 识别、Markdown 生成等功能。

## 功能特性

- **版面分析**：智能识别文档中的标题、文本、图片、表格、公式等区域
- **OCR 识别**：对文本区域进行高精度 OCR 识别
- **Markdown 生成**：将分析结果转换为 Markdown 格式
- **图片裁剪**：自动裁剪并保存图片区域

## 集成方式

### 1. 添加依赖

将 AAR 文件复制到项目的 `libs` 目录：

```gradle
dependencies {
    implementation files('libs/DocLayoutSdk-debug.aar')
    implementation project(':OcrLibrary')  // 需要同时集成 OcrLibrary
}
```

### 2. 清单文件配置

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<activity
    android:name="com.benjaminwan.doclayoutsdk.ui.DocLayoutActivity"
    android:theme="@style/Theme.AppCompat.Light.Dialog" />
```

### 3. API 使用

#### 方式一：启动 Activity（推荐）

```kotlin
// 启动版面分析界面
val imageUri = Uri.parse("content://...")
DocLayoutActivity.start(this, imageUri, saveImagesDir = "/path/to/save")

// 获取结果（可选，在 onActivityResult 中）
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == 1001 && resultCode == RESULT_OK) {
        val markdown = data?.getStringExtra(DocLayoutActivity.EXTRA_RESULT_MARKDOWN)
        val imageCount = data?.getIntExtra(DocLayoutActivity.EXTRA_RESULT_IMAGE_COUNT, 0)
        val textCount = data?.getIntExtra(DocLayoutActivity.EXTRA_RESULT_TEXT_COUNT, 0)
        val tableCount = data?.getIntExtra(DocLayoutActivity.EXTRA_RESULT_TABLE_COUNT, 0)
    }
}
```

#### 方式二：直接调用 API

```kotlin
// 初始化 SDK
val analyzer = DocLayoutAnalyzer.init(context)

// 版面分析
val layoutResult = analyzer.analyzeLayout(bitmap, layoutScoreThresh = 0.1f)

// 生成 Markdown
val markdown = analyzer.generateMarkdown(layoutResult)

// 裁剪图片
val images = analyzer.cropAndSaveImages(bitmap, layoutResult, "/output/dir")
```

## API 参考

### DocLayoutAnalyzer

| 方法 | 说明 |
|------|------|
| `init(context)` | 初始化 SDK |
| `analyzeLayout(image, layoutScoreThresh)` | 执行版面分析 |
| `analyzeAndGenerateMarkdown(image, layoutScoreThresh, options)` | 分析并生成 Markdown |
| `generateMarkdown(layoutResult, options)` | 生成 Markdown |
| `cropAndSaveImages(image, layoutResult, outputDir)` | 裁剪并保存图片 |

### AnalyzeResult

| 字段 | 类型 | 说明 |
|------|------|------|
| `layoutResult` | LayoutResult | 版面分析结果 |
| `markdown` | String | 生成的 Markdown |
| `imageCount` | Int | 图片数量 |
| `textCount` | Int | 文本块数量 |
| `tableCount` | Int | 表格数量 |

## 第三方应用集成示例

```kotlin
class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 选择图片并分析
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                DocLayoutActivity.start(this, uri, "/sdcard/Pictures/Result/")
            }
        }

        // 或者获取结果
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val markdown = data?.getStringExtra(DocLayoutActivity.EXTRA_RESULT_MARKDOWN)
            // 使用 markdown 结果...
        }
    }
}
```

## 版本历史

### 1.0.0

- 初始版本
- 支持版面分析、OCR 识别、Markdown 生成
- 提供 Activity 界面和 API 两种使用方式
