#!/bin/bash

# 脚本：将 OcrLibrary 合并到 DocLayoutSdk，打包成完整的 AAR
# 运行方式: bash merge-sdk.sh
# 注意：需要先编译 OcrLibrary 模块以生成 native 库

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
OCR_DIR="$PROJECT_DIR/OcrLibrary"
SDK_DIR="$PROJECT_DIR/DocLayoutSdk"

echo "=== 开始合并 OcrLibrary 到 DocLayoutSdk ==="

# 0. 确保 OcrLibrary 已编译
echo "0. 检查 OcrLibrary 编译状态..."
JNI_SRC="$OCR_DIR/build/intermediates/library_jni/debug/copyDebugJniLibsProjectOnly/jni"
if [ ! -d "$JNI_SRC" ]; then
    echo "   编译 OcrLibrary 中..."
    cd "$PROJECT_DIR"
    ./gradlew :OcrLibrary:assembleDebug --quiet
fi
echo "   OcrLibrary 已编译"

# 1. 合并 Java 源代码
echo "1. 合并 Java 源代码..."
rm -rf "$SDK_DIR/src/main/java/com/benjaminwan/ocrlibrary"
mkdir -p "$SDK_DIR/src/main/java/com/benjaminwan/ocrlibrary"
cp -r "$OCR_DIR/src/main/java/com/benjaminwan/ocrlibrary/." "$SDK_DIR/src/main/java/com/benjaminwan/ocrlibrary/"
echo "   Java 源代码已复制"

# 2. 合并 assets（模型文件）
echo "2. 合并模型文件..."
rm -rf "$SDK_DIR/src/main/assets"
mkdir -p "$SDK_DIR/src/main/assets"
cp -r "$OCR_DIR/src/main/assets/." "$SDK_DIR/src/main/assets/"
echo "   模型文件已复制: $(ls -1 "$SDK_DIR/src/main/assets" | wc -l) 个"

# 3. 合并 AndroidManifest 权限
echo "3. 合并 AndroidManifest..."
MANIFEST_SDK="$SDK_DIR/src/main/AndroidManifest.xml"

# 确保权限存在
for perm in "android.permission.READ_EXTERNAL_STORAGE" "android.permission.WRITE_EXTERNAL_STORAGE"; do
    if ! grep -q "$perm" "$MANIFEST_SDK"; then
        sed -i '' "s|<uses-permission android:name=\"android.permission.READ_EXTERNAL_STORAGE\" />|<uses-permission android:name=\"$perm\" />|g" "$MANIFEST_SDK"
    fi
done
echo "   AndroidManifest 已更新"

# 4. 复制 native 库（从 OcrLibrary 的 build 目录）
echo "4. 复制 native 库..."
rm -rf "$SDK_DIR/src/main/jniLibs"
mkdir -p "$SDK_DIR/src/main/jniLibs"

# 从 OcrLibrary 编译输出复制 libRapidOcr.so
for abi in "arm64-v8a" "armeabi-v7a" "x86" "x86_64"; do
    SRC_SO="$JNI_SRC/$abi/libRapidOcr.so"
    if [ -f "$SRC_SO" ]; then
        mkdir -p "$SDK_DIR/src/main/jniLibs/$abi"
        cp "$SRC_SO" "$SDK_DIR/src/main/jniLibs/$abi/"
        echo "   复制 $abi/libRapidOcr.so"
    fi
done

# 复制 onnxruntime .so 文件
for abi in "arm64-v8a" "armeabi-v7a" "x86" "x86_64"; do
    ONNX_SO="$OCR_DIR/src/main/onnxruntime-shared/$abi/lib/libonnxruntime.so"
    if [ -f "$ONNX_SO" ]; then
        mkdir -p "$SDK_DIR/src/main/jniLibs/$abi"
        cp "$ONNX_SO" "$SDK_DIR/src/main/jniLibs/$abi/"
        echo "   复制 $abi/libonnxruntime.so"
    fi
done

# 复制 libc++_shared.so
for abi in "arm64-v8a" "armeabi-v7a" "x86" "x86_64"; do
    CPP_SO="$OCR_DIR/build/intermediates/library_jni/debug/copyDebugJniLibsProjectOnly/jni/$abi/libc++_shared.so"
    if [ -f "$CPP_SO" ]; then
        mkdir -p "$SDK_DIR/src/main/jniLibs/$abi"
        cp "$CPP_SO" "$SDK_DIR/src/main/jniLibs/$abi/"
        echo "   复制 $abi/libc++_shared.so"
    fi
done

echo "   Native 库已复制"

# 5. 合并 C++ 源码（可选，用于调试）
echo "5. 合并 C++ 源码..."
rm -rf "$SDK_DIR/src/main/cpp"
mkdir -p "$SDK_DIR/src/main/cpp"
cp -r "$OCR_DIR/src/main/cpp/." "$SDK_DIR/src/main/cpp/"
echo "   C++ 源码已复制"

# 6. 复制 onnxruntime-shared（CMake 需要）
echo "6. 复制 onnxruntime-shared..."
rm -rf "$SDK_DIR/src/main/onnxruntime-shared"
mkdir -p "$SDK_DIR/src/main/onnxruntime-shared"
cp -r "$OCR_DIR/src/main/onnxruntime-shared/." "$SDK_DIR/src/main/onnxruntime-shared/"
echo "   onnxruntime-shared 已复制"

echo ""
echo "=== 合并完成 ==="
echo ""
echo "新增功能:"
echo "  - Markdown转DOCX支持 (MarkdownToDocConverter.kt)"
echo "  - DocLayoutSdk Java API 更新 (DocLayoutSdk.java)"
echo "  - 完整导出接口 (analyzeAndExport)"
echo ""
echo "构建 AAR:"
echo "  cd $PROJECT_DIR"
echo "  ./gradlew :DocLayoutSdk:assembleDebug"
echo ""
echo "AAR 输出位置:"
echo "  $SDK_DIR/build/outputs/aar/DocLayoutSdk-debug.aar"
echo ""
echo "第三方集成示例:"
echo "  // 初始化"
echo "  DocLayoutSdk.init(context)"
echo ""
echo "  // 分析并导出"
echo "  DocLayoutSdk.analyzeAndExportAsync(bitmap, resourceDir, \"document\","
echo "      new DocLayoutSdk.ExportResultCallback() {"
echo "          @Override"
echo "          public void onSuccess(ExportResult result) {"
echo "              String docPath = result.docPath;  // DOCX文件路径"
echo "              String htmlPath = result.htmlPath; // HTML文件路径"
echo "          }"
echo "          @Override"
echo "          public void onError(String error) { }"
echo "      });"
