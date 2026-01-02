#include "LayoutNet.h"
#include "onnxruntime/core/session/onnxruntime_cxx_api.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <algorithm>
#include <chrono>

#define TAG "LayoutNet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// DOCLAYOUT_DOCSTRUCTBENCH 模型配置
const int INPUT_WIDTH = 1024;
const int INPUT_HEIGHT = 1024;

// DOCLAYOUT_DOCSTRUCTBENCH 支持的10个类别
static const std::vector<std::string> DOCLAYOUT_CLASSES = {
    "title", "plain text", "abandon", "figure", "figure_caption",
    "table", "table_caption", "table_footnote", "isolate_formula", "formula_caption"
};

LayoutNet::LayoutNet() : session(nullptr) {
    LOGI("LayoutNet constructor - CDLA support");
}

LayoutNet::~LayoutNet() {
    if (session) {
        delete session;
        session = nullptr;
    }
    LOGI("LayoutNet destructor");
}

void LayoutNet::setNumThread(int numOfThread) {
    numThread = numOfThread;
    sessionOptions.SetIntraOpNumThreads(numThread);
    sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);
    LOGI("Set threads: %d", numThread);
}

void LayoutNet::initModel(AAssetManager *mgr, const std::string &name) {
    LOGI("Loading CDLA model: %s", name.c_str());

    // 从 Android Assets 加载模型
    AAsset* asset = AAssetManager_open(mgr, name.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        LOGI("Failed to open model asset: %s", name.c_str());
        return;
    }

    size_t modelSize = AAsset_getLength(asset);
    const void* modelData = AAsset_getBuffer(asset);

    LOGI("Model size: %zu bytes", modelSize);

    try {
        // 创建 ONNX Runtime Session
        session = new Ort::Session(ortEnv, modelData, modelSize, sessionOptions);
        LOGI("CDLA model loaded successfully");

        AAsset_close(asset);
    } catch (const std::exception& e) {
        LOGI("Failed to create ONNX session: %s", e.what());
        AAsset_close(asset);
    }
}

// DOCLAYOUT_DOCSTRUCTBENCH 预处理 - Letterbox实现（与Python保持一致）
cv::Mat LayoutNet::preprocessImage(const cv::Mat &src) {
    // Letterbox预处理 - 保持宽高比，添加灰色填充
    cv::Size srcSize = src.size();

    // 计算缩放比例（保持宽高比）
    float r = std::min(static_cast<float>(INPUT_WIDTH) / srcSize.width,
                       static_cast<float>(INPUT_HEIGHT) / srcSize.height);

    // 计算resize后的尺寸
    cv::Size newUnpad(static_cast<int>(round(srcSize.width * r)),
                      static_cast<int>(round(srcSize.height * r)));

    // 计算padding
    float dw = static_cast<float>(INPUT_WIDTH - newUnpad.width);
    float dh = static_cast<float>(INPUT_HEIGHT - newUnpad.height);

    // 居中padding
    int padW = static_cast<int>(round(dw / 2.0f - 0.1f));
    int padH = static_cast<int>(round(dh / 2.0f - 0.1f));
    int padBottom = static_cast<int>(round(dh / 2.0f + 0.1f));
    int padRight = static_cast<int>(round(dw / 2.0f + 0.1f));

    LOGI("Letterbox: src_size=(%d,%d), scale=%.3f, new_size=(%d,%d), pad=(%d,%d,%d,%d)",
         srcSize.width, srcSize.height, r, newUnpad.width, newUnpad.height,
         padW, padH, padRight, padBottom);

    // Resize图像
    cv::Mat resized;
    cv::resize(src, resized, newUnpad);

    // 转换BGR到RGB
    cv::Mat rgb;
    cv::cvtColor(resized, rgb, cv::COLOR_BGR2RGB);

    // 添加灰色填充(114,114,114) - 与Python LetterBox保持一致
    cv::Mat padded;
    cv::copyMakeBorder(rgb, padded, padH, padBottom, padW, padRight,
                      cv::BORDER_CONSTANT, cv::Scalar(114, 114, 114));

    // 确保尺寸正确
    if (padded.size() != cv::Size(INPUT_WIDTH, INPUT_HEIGHT)) {
        cv::resize(padded, padded, cv::Size(INPUT_WIDTH, INPUT_HEIGHT));
    }

    // 归一化到[0,1]
    cv::Mat normalized;
    padded.convertTo(normalized, CV_32F, 1.0/255.0);

    return normalized;
}

// 解析YOLOv8格式输出
std::vector<LayoutBox> LayoutNet::parseYOLOv8Output(float* outputData,
                                                   const std::vector<int64_t>& outputShape,
                                                   const cv::Mat& src,
                                                   float confThreshold) {
    std::vector<LayoutBox> boxes;

    if (outputShape.size() != 3) {
        LOGI("Invalid YOLOv8 output shape, expected 3D, got %zu", outputShape.size());
        return boxes;
    }

    int numDetections = static_cast<int>(outputShape[1]);
    int numFeatures = static_cast<int>(outputShape[2]); // DOCLAYOUT_DOCSTRUCTBENCH: 6 (4 bbox + 1 conf + 1 class_id)

    LOGI("Parsing YOLOv8 output: detections=%d, features=%d", numDetections, numFeatures);

    // DOCLAYOUT_DOCSTRUCTBENCH 使用scale_boxes逻辑
    // 计算gain和padding，与Python的scale_boxes保持一致
    float gain = std::min(static_cast<float>(INPUT_WIDTH) / src.cols,
                          static_cast<float>(INPUT_HEIGHT) / src.rows);

    // 计算padding
    int padW = static_cast<int>(round((INPUT_WIDTH - src.cols * gain) / 2.0f - 0.1f));
    int padH = static_cast<int>(round((INPUT_HEIGHT - src.rows * gain) / 2.0f - 0.1f));

    LOGI("scale_boxes: gain=%.3f, pad=(%d,%d), src_size=(%d,%d), input_size=(%d,%d)",
         gain, padW, padH, src.cols, src.rows, INPUT_WIDTH, INPUT_HEIGHT);

    for (int i = 0; i < numDetections; ++i) {
        // DOCLAYOUT_DOCSTRUCTBENCH 格式: [x1, y1, x2, y2, conf, class_id]
        // 获取 bbox 坐标 (x1, y1, x2, y2) - 已经是在input_size上的坐标
        float x1 = outputData[i * numFeatures + 0];
        float y1 = outputData[i * numFeatures + 1];
        float x2 = outputData[i * numFeatures + 2];
        float y2 = outputData[i * numFeatures + 3];

        // 获取置信度 (第5个元素)
        float confidence = outputData[i * numFeatures + 4];

        // 获取类别ID (第6个元素)
        int classId = static_cast<int>(outputData[i * numFeatures + 5]);

        if (confidence < confThreshold || classId < 0 || classId >= 10) {
            LOGI("Skipping detection: score=%.3f, classId=%d (threshold=%.3f)", confidence, classId, confThreshold);
            continue;
        }

        float maxScore = confidence;

        // 确保classId在有效范围内，防止数组越界
        if (classId < 0 || classId >= DOCLAYOUT_CLASSES.size()) {
            LOGI("Invalid classId %d, skipping", classId);
            continue;
        }

        // 应用scale_boxes逻辑 (Python scale_boxes的反向操作)
        // 1. 减去padding
        x1 -= padW;
        y1 -= padH;
        x2 -= padW;
        y2 -= padH;

        // 2. 除以gain
        x1 /= gain;
        y1 /= gain;
        x2 /= gain;
        y2 /= gain;

        // 确保坐标在有效范围内 (clip_boxes)
        x1 = std::max(0.0f, std::min(x1, static_cast<float>(src.cols)));
        y1 = std::max(0.0f, std::min(y1, static_cast<float>(src.rows)));
        x2 = std::max(0.0f, std::min(x2, static_cast<float>(src.cols)));
        y2 = std::max(0.0f, std::min(y2, static_cast<float>(src.rows)));

        // 确保框有效
        if (x2 <= x1 || y2 <= y1) continue;

        LayoutBox box;
        box.boxPoint = {
            cv::Point(static_cast<int>(x1), static_cast<int>(y1)),
            cv::Point(static_cast<int>(x2), static_cast<int>(y1)),
            cv::Point(static_cast<int>(x2), static_cast<int>(y2)),
            cv::Point(static_cast<int>(x1), static_cast<int>(y2))
        };
        box.score = maxScore;
        box.type = static_cast<LayoutType>(classId);
        box.typeName = DOCLAYOUT_CLASSES[classId];
        box.hasOcrText = false;

        LOGI("Creating box: type=%d, typeName=%s, score=%.3f", static_cast<int>(box.type), box.typeName.c_str(), box.score);
        boxes.push_back(box);
    }

    LOGI("Parsed %zu valid boxes from YOLOv8 output", boxes.size());
    return boxes;
}

// 计算IoU
float LayoutNet::calculateIoU(const LayoutBox &box1, const LayoutBox &box2) {
    cv::Rect rect1(box1.boxPoint[0].x, box1.boxPoint[0].y,
                   box1.boxPoint[2].x - box1.boxPoint[0].x,
                   box1.boxPoint[2].y - box1.boxPoint[0].y);
    cv::Rect rect2(box2.boxPoint[0].x, box2.boxPoint[0].y,
                   box2.boxPoint[2].x - box2.boxPoint[0].x,
                   box2.boxPoint[2].y - box2.boxPoint[0].y);

    cv::Rect intersection = rect1 & rect2;
    cv::Rect union_ = rect1 | rect2;

    if (union_.area() == 0) return 0.0f;
    return static_cast<float>(intersection.area()) / static_cast<float>(union_.area());
}

// Per-class NMS过滤重叠框 - 与Python multiclass_nms保持一致
std::vector<LayoutBox> LayoutNet::nmsBoxes(std::vector<LayoutBox> &boxes, float iouThreshold) {
    if (boxes.empty()) return boxes;

    LOGI("Applying per-class NMS with IoU threshold: %.2f", iouThreshold);

    // 按类别分组
    std::map<int, std::vector<LayoutBox>> classGroups;
    for (const auto& box : boxes) {
        int classId = static_cast<int>(box.type);
        classGroups[classId].push_back(box);
    }

    std::vector<LayoutBox> result;
    int totalBeforeNMS = static_cast<int>(boxes.size());
    int totalAfterNMS = 0;

    // 对每个类别单独应用NMS
    for (auto& pair : classGroups) {
        int classId = pair.first;
        std::vector<LayoutBox>& classBoxes = pair.second;

        LOGI("Processing class %d (%s): %d boxes before NMS",
             classId, classId < DOCLAYOUT_CLASSES.size() ? DOCLAYOUT_CLASSES[classId].c_str() : "unknown",
             static_cast<int>(classBoxes.size()));

        // 按分数降序排序
        std::sort(classBoxes.begin(), classBoxes.end(), [](const LayoutBox& a, const LayoutBox& b) {
            return a.score > b.score;
        });

        std::vector<LayoutBox> classResult;
        std::vector<bool> suppressed(classBoxes.size(), false);

        // 应用NMS
        for (size_t i = 0; i < classBoxes.size(); ++i) {
            if (suppressed[i]) continue;

            classResult.push_back(classBoxes[i]);

            // 只抑制同类的重叠框
            for (size_t j = i + 1; j < classBoxes.size(); ++j) {
                if (!suppressed[j] && calculateIoU(classBoxes[i], classBoxes[j]) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }

        LOGI("Class %d: %d boxes after NMS", classId, static_cast<int>(classResult.size()));
        result.insert(result.end(), classResult.begin(), classResult.end());
        totalAfterNMS += static_cast<int>(classResult.size());
    }

    LOGI("Per-class NMS completed: %d -> %d boxes (%.1f%% reduction)",
         totalBeforeNMS, totalAfterNMS,
         totalBeforeNMS > 0 ? (1.0 - static_cast<float>(totalAfterNMS) / totalBeforeNMS) * 100.0f : 0.0f);

    return result;
}

LayoutResult LayoutNet::getLayoutBoxes(cv::Mat &src, float boxScoreThresh) {
    LayoutResult result;

    if (!session) {
        LOGI("Session not initialized");
        return result;
    }

    LOGI("Starting DOCLAYOUT_DOCSTRUCTBENCH layout analysis with score threshold: %.2f", boxScoreThresh);

    // 开始计时
    auto startTime = std::chrono::high_resolution_clock::now();

    try {
        // 预处理图像
        cv::Mat inputImage = preprocessImage(src);

        // 准备输入张量 - CHW格式
        std::vector<int64_t> inputShape = {1, 3, INPUT_HEIGHT, INPUT_WIDTH};
        std::vector<float> inputTensorData(3 * INPUT_HEIGHT * INPUT_WIDTH);

        // HWC -> CHW 转换
        for (int c = 0; c < 3; ++c) {
            for (int h = 0; h < INPUT_HEIGHT; ++h) {
                for (int w = 0; w < INPUT_WIDTH; ++w) {
                    int srcIdx = (h * INPUT_WIDTH + w) * 3 + c;
                    int dstIdx = c * INPUT_HEIGHT * INPUT_WIDTH + h * INPUT_WIDTH + w;
                    inputTensorData[dstIdx] = inputImage.ptr<float>()[srcIdx];
                }
            }
        }

        // 创建输入张量
        Ort::MemoryInfo memoryInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        Ort::Value inputTensor = Ort::Value::CreateTensor<float>(
            memoryInfo, inputTensorData.data(), inputTensorData.size(),
            inputShape.data(), inputShape.size());

        if (!inputTensor.IsTensor()) {
            LOGI("ERROR: Failed to create input tensor!");
            return result;
        }

        // 获取输入输出名称 - DOCLAYOUT_DOCSTRUCTBENCH 使用 "images" 作为输入名
        Ort::AllocatorWithDefaultOptions allocator;
        char* inputName = session->GetInputNameAllocated(0, allocator).release();
        LOGI("Model input name: %s", inputName);

        size_t numOutputNodes = session->GetOutputCount();
        std::vector<const char*> outputNames;
        for (size_t i = 0; i < numOutputNodes; i++) {
            char* outputName = session->GetOutputNameAllocated(i, allocator).release();
            outputNames.push_back(outputName);
        }

        // 运行推理
        auto outputTensors = session->Run(Ort::RunOptions{nullptr},
                                         &inputName, &inputTensor, 1,
                                         outputNames.data(), numOutputNodes);

        // 释放名称内存
        allocator.Free(const_cast<void*>(static_cast<const void*>(inputName)));
        for (size_t i = 0; i < numOutputNodes; i++) {
            allocator.Free(const_cast<void*>(static_cast<const void*>(outputNames[i])));
        }

        LOGI("DOCLAYOUT_DOCSTRUCTBENCH inference completed, got %zu output tensors", outputTensors.size());

        // 解析输出
        std::vector<LayoutBox> boxes;
        if (outputTensors.size() >= 1) {
            auto& outputTensor = outputTensors[0];
            auto outputShapeInfo = outputTensor.GetTensorTypeAndShapeInfo();
            std::vector<int64_t> outputShape = outputShapeInfo.GetShape();

            float* outputData = outputTensor.GetTensorMutableData<float>();

            // 使用传入的置信度阈值
            float effectiveThreshold = boxScoreThresh;
            boxes = parseYOLOv8Output(outputData, outputShape, src, effectiveThreshold);
        }

        // NMS过滤重叠框 - 与best_demo.py保持一致的IoU阈值
        boxes = nmsBoxes(boxes, 0.4f);

        LOGI("After NMS: %zu boxes remaining", boxes.size());

        // 结束计时
        auto endTime = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);
        double processingTime = static_cast<double>(duration.count());

        // 生成结果
        result.layoutBoxes = boxes;
        result.layoutImg = src.clone();
        result.layoutNetTime = processingTime;

        // 生成Markdown内容
        result.markdown = generateLayoutMarkdown(result);

        // 绘制检测结果
        drawLayoutDetections(result.layoutImg, boxes);

        LOGI("DOCLAYOUT_DOCSTRUCTBENCH layout analysis completed successfully in %.2fms", processingTime);

    } catch (const std::exception& e) {
        LOGI("Exception during layout analysis: %s", e.what());
    }

    return result;
}

std::vector<std::string> LayoutNet::getLayoutClassNames() {
    return DOCLAYOUT_CLASSES;
}

std::string LayoutNet::layoutTypeToString(LayoutType type) {
    int classId = static_cast<int>(type);
    if (classId >= 0 && classId < DOCLAYOUT_CLASSES.size()) {
        return DOCLAYOUT_CLASSES[classId];
    }
    return "unknown";
}

LayoutType LayoutNet::intToLayoutType(int classId) {
    if (classId >= 0 && classId < DOCLAYOUT_CLASSES.size()) {
        return static_cast<LayoutType>(classId);
    }
    return LayoutType::UNKNOWN;
}

std::string LayoutNet::generateMarkdown(const LayoutResult &layoutResult) {
    return generateLayoutMarkdown(layoutResult);
}

std::string LayoutNet::generateLayoutMarkdown(const LayoutResult &layoutResult) {
    if (layoutResult.layoutBoxes.empty()) {
        return "# Document Analysis Results\n\nNo layout regions detected.";
    }

    std::string markdown = "# Document Layout Analysis (DOCLAYOUT_DOCSTRUCTBENCH)\n\n";

    // 按Y坐标排序（从上到下）
    auto sortedBoxes = layoutResult.layoutBoxes;
    std::sort(sortedBoxes.begin(), sortedBoxes.end(),
              [](const LayoutBox& a, const LayoutBox& b) {
                  return a.boxPoint[0].y < b.boxPoint[0].y;
              });

    // 统计各类别数量 - DOCLAYOUT_DOCSTRUCTBENCH 支持的10个类别
    std::map<std::string, int> classCounts;
    for (const auto& box : sortedBoxes) {
        classCounts[box.typeName]++;
    }

    // 添加文档摘要
    markdown += "## Document Summary\n\n";
    for (const auto& pair : classCounts) {
        std::string displayName = pair.first;
        std::replace(displayName.begin(), displayName.end(), '_', ' ');
        displayName[0] = std::toupper(displayName[0]);
        markdown += "- **" + displayName + "**: " + std::to_string(pair.second) + "\n";
    }
    markdown += "\n";

    // 添加布局结构
    markdown += "## Document Structure\n\n";

    for (size_t i = 0; i < sortedBoxes.size(); ++i) {
        const auto& box = sortedBoxes[i];

        std::string displayName = box.typeName;
        std::replace(displayName.begin(), displayName.end(), '_', ' ');
        displayName[0] = std::toupper(displayName[0]);

        if (box.typeName == "title") {
            markdown += "### " + displayName + " " + std::to_string(i + 1) + "\n\n";
        } else if (box.typeName == "plain text") {
            markdown += "**Text Region** (Confidence: " + std::to_string(static_cast<int>(box.score * 100)) + "%)\n\n";
            markdown += "> Location: (" + std::to_string(box.boxPoint[0].x) + ", " +
                       std::to_string(box.boxPoint[0].y) + ") → (" +
                       std::to_string(box.boxPoint[2].x) + ", " +
                       std::to_string(box.boxPoint[2].y) + ")\n\n";
        } else if (box.typeName == "figure") {
            markdown += "**Figure/Image** (Confidence: " + std::to_string(static_cast<int>(box.score * 100)) + "%)\n\n";
            markdown += "![Figure](image://" + std::to_string(i) + ")\n\n";
            markdown += "*Figure location: (" + std::to_string(box.boxPoint[0].x) + ", " +
                       std::to_string(box.boxPoint[0].y) + ")*\n\n";
        } else if (box.typeName == "table") {
            markdown += "**Table** (Confidence: " + std::to_string(static_cast<int>(box.score * 100)) + "%)\n\n";
            markdown += "| Column 1 | Column 2 | Column 3 |\n";
            markdown += "|----------|----------|----------|\n";
            markdown += "| Data 1   | Data 2   | Data 3   |\n";
            markdown += "| Data 4   | Data 5   | Data 6   |\n\n";
        } else {
            markdown += "**" + displayName + "** (Confidence: " + std::to_string(static_cast<int>(box.score * 100)) + "%)\n\n";
        }
    }

    // 添加技术信息
    markdown += "---\n\n";
    markdown += "## Analysis Details\n\n";
    markdown += "- **Total Regions**: " + std::to_string(layoutResult.layoutBoxes.size()) + "\n";
    markdown += "- **Processing Time**: " + std::to_string(static_cast<int>(layoutResult.layoutNetTime)) + "ms\n";
    markdown += "- **Analysis Model**: DOCLAYOUT_DOCSTRUCTBENCH (YOLOv8-based)\n";
    markdown += "- **Supported Formats**: ";
    for (size_t i = 0; i < DOCLAYOUT_CLASSES.size(); ++i) {
        if (i > 0) markdown += ", ";
        std::string className = DOCLAYOUT_CLASSES[i];
        className[0] = std::toupper(className[0]);
        markdown += className;
    }
    markdown += "\n\n";

    // 添加应用信息
    markdown += "## Generated By\n\n";
    markdown += "**Layout2Markdown** - Intelligent Document Layout Analysis\n\n";
    markdown += "*Features:*\n";
    markdown += "- 📄 **High-Precision Detection**: DOCLAYOUT_DOCSTRUCTBENCH model\n";
    markdown += "- 🔍 **10 Layout Categories**: Comprehensive document element recognition\n";
    markdown += "- ⚡ **Real-time Processing**: Optimized ONNX runtime inference\n";
    markdown += "- 📱 **Mobile Optimized**: Efficient Android deployment\n\n";

    return markdown;
}

// 绘制检测结果
void LayoutNet::drawLayoutDetections(cv::Mat& img, const std::vector<LayoutBox>& boxes) {
    if (boxes.empty()) return;

    // 绘制半透明遮罩
    drawMask(img, boxes, 0.3f);

    // 绘制每个检测框和标签
    for (const auto& box : boxes) {
        cv::Scalar color = getLayoutColor(box.type);
        drawBoxWithLabel(img, box, color);
    }
}

// 获取随机颜色 - 匹配Python vis_res.py的get_color()实现
cv::Scalar LayoutNet::getLayoutColor(const LayoutType& type) {
    // 生成随机颜色，匹配Python实现
    return cv::Scalar(
        rand() % 256,  // B
        rand() % 256,  // G
        rand() % 256   // R
    );
}

// 绘制半透明遮罩
void LayoutNet::drawMask(cv::Mat& img, const std::vector<LayoutBox>& boxes, float alpha) {
    cv::Mat maskImg = img.clone();

    for (const auto& box : boxes) {
        cv::Scalar color = getLayoutColor(box.type);
        cv::rectangle(maskImg, box.boxPoint[0], box.boxPoint[2], color, -1);
    }

    // 叠加透明效果
    cv::addWeighted(maskImg, alpha, img, 1.0 - alpha, 0, img);
}

// 绘制检测框和标签 - 匹配Python vis_res.py实现
void LayoutNet::drawBoxWithLabel(cv::Mat& img, const LayoutBox& box, const cv::Scalar& color) {
    // 绘制检测框 - 匹配Python thickness=2
    cv::rectangle(img, box.boxPoint[0], box.boxPoint[2], color, 2);

    // 准备标签文本 - 匹配Python格式: "class_name XX%"
    std::string label = box.typeName + " " +
                       std::to_string(static_cast<int>(box.score * 100)) + "%";

    // 计算文本大小 - 匹配Python字体设置
    int fontFace = cv::FONT_HERSHEY_SIMPLEX;
    double fontScale = 0.6;  // Python使用动态字体大小，这里暂时使用固定值
    int thickness = 1;       // Python text_thickness
    int baseline = 0;

    cv::Size textSize = cv::getTextSize(label, fontFace, fontScale, thickness, &baseline);

    // 匹配Python的文本背景绘制逻辑
    int th = static_cast<int>(baseline * 1.2);
    cv::Point topLeft = box.boxPoint[0];
    cv::Point bottomRight = cv::Point(topLeft.x + textSize.width,
                                     topLeft.y - th);

    // 绘制标签背景 - 匹配Python的-1填充
    cv::rectangle(img, topLeft, bottomRight, color, -1);

    // 绘制文本 - 匹配Python的位置和颜色(255,255,255)
    cv::Point textPos(topLeft.x, topLeft.y);
    cv::putText(img, label, textPos, fontFace, fontScale,
                cv::Scalar(255, 255, 255), thickness, cv::LINE_AA);
}