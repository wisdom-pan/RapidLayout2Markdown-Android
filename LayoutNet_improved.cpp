// 改进的LayoutNet实现 - 重点修复预处理管道
#include "LayoutNet.h"

class LayoutNetImproved {
private:
    static const int INPUT_WIDTH = 1024;
    static const int INPUT_HEIGHT = 1024;

    // 预处理参数存储
    struct PreprocessParams {
        float gain;
        int padW, padH;
        int originalWidth, originalHeight;
    };

public:
    // ✅ 改进1: 实现标准Letterbox预处理
    cv::Mat preprocessImageLetterbox(const cv::Mat &src, PreprocessParams& params) {
        params.originalWidth = src.cols;
        params.originalHeight = src.rows;

        // 1. 计算gain（保持宽高比）
        params.gain = std::min(static_cast<float>(INPUT_WIDTH) / src.cols,
                              static_cast<float>(INPUT_HEIGHT) / src.rows);

        // 2. 计算新的尺寸
        int newWidth = static_cast<int>(src.cols * params.gain);
        int newHeight = static_cast<int>(src.rows * params.gain);

        // 3. 计算padding（与Python保持一致）
        params.padW = static_cast<int>(round((INPUT_WIDTH - newWidth) / 2.0f - 0.1f));
        params.padH = static_cast<int>(round((INPUT_HEIGHT - newHeight) / 2.0f - 0.1f));

        // 4. Resize保持宽高比
        cv::Mat resized;
        cv::resize(src, resized, cv::Size(newWidth, newHeight), 0, 0, cv::INTER_LINEAR);

        // 5. 添加padding（使用114,114,114灰色，与YOLO标准一致）
        cv::Mat padded;
        cv::copyMakeBorder(resized, padded, params.padH, params.padH, params.padW, params.padW,
                          cv::BORDER_CONSTANT, cv::Scalar(114, 114, 114));

        // 6. BGR2RGB + 归一化
        cv::Mat rgb;
        cv::cvtColor(padded, rgb, cv::COLOR_BGR2RGB);

        cv::Mat normalized;
        rgb.convertTo(normalized, CV_32F, 1.0/255.0);

        LOGI("Letterbox: orig(%dx%d) -> resized(%dx%d) -> padded(%dx%d), gain=%.3f, pad=(%d,%d)",
             src.cols, src.rows, newWidth, newHeight, INPUT_WIDTH, INPUT_HEIGHT,
             params.gain, params.padW, params.padH);

        return normalized;
    }

    // ✅ 改进2: 标准化坐标变换（与预处理保持一致）
    std::vector<LayoutBox> parseYOLOv8OutputImproved(float* outputData,
                                                     const std::vector<int64_t>& outputShape,
                                                     const PreprocessParams& params,
                                                     float confThreshold) {
        std::vector<LayoutBox> boxes;

        int numDetections = static_cast<int>(outputShape[1]);
        int numFeatures = static_cast<int>(outputShape[2]);

        LOGI("Parsing %d detections with %d features", numDetections, numFeatures);

        for (int i = 0; i < numDetections; ++i) {
            // 解析输出数据
            float x1 = outputData[i * numFeatures + 0];
            float y1 = outputData[i * numFeatures + 1];
            float x2 = outputData[i * numFeatures + 2];
            float y2 = outputData[i * numFeatures + 3];
            float confidence = outputData[i * numFeatures + 4];
            int classId = static_cast<int>(outputData[i * numFeatures + 5]);

            if (confidence < confThreshold || classId < 0 || classId >= 10) {
                continue;
            }

            // ✅ 使用标准scale_boxes逻辑
            // 1. 减去padding
            x1 -= params.padW;
            y1 -= params.padH;
            x2 -= params.padW;
            y2 -= params.padH;

            // 2. 除以gain
            x1 /= params.gain;
            y1 /= params.gain;
            x2 /= params.gain;
            y2 /= params.gain;

            // 3. clip_boxes
            x1 = std::max(0.0f, std::min(x1, static_cast<float>(params.originalWidth)));
            y1 = std::max(0.0f, std::min(y1, static_cast<float>(params.originalHeight)));
            x2 = std::max(0.0f, std::min(x2, static_cast<float>(params.originalWidth)));
            y2 = std::max(0.0f, std::min(y2, static_cast<float>(params.originalHeight)));

            // 确保框有效
            if (x2 <= x1 || y2 <= y1) continue;

            LayoutBox box;
            box.boxPoint = {
                cv::Point(static_cast<int>(x1), static_cast<int>(y1)),
                cv::Point(static_cast<int>(x2), static_cast<int>(y1)),
                cv::Point(static_cast<int>(x2), static_cast<int>(y2)),
                cv::Point(static_cast<int>(x1), static_cast<int>(y2))
            };
            box.score = confidence;
            box.type = static_cast<LayoutType>(classId);
            box.typeName = DOCLAYOUT_CLASSES[classId];
            box.hasOcrText = false;

            boxes.push_back(box);
        }

        LOGI("Generated %zu valid boxes", boxes.size());
        return boxes;
    }

    // ✅ 改进3: 增强的NMS实现
    std::vector<LayoutBox> nmsBoxesImproved(std::vector<LayoutBox> &boxes, float iouThreshold) {
        if (boxes.empty()) return boxes;

        // 按分数降序排序
        std::sort(boxes.begin(), boxes.end(), [](const LayoutBox& a, const LayoutBox& b) {
            return a.score > b.score;
        });

        std::vector<LayoutBox> result;
        std::vector<bool> suppressed(boxes.size(), false);

        // ✅ 分类别NMS（更精细的过滤）
        std::map<int, std::vector<size_t>> classBoxes;
        for (size_t i = 0; i < boxes.size(); ++i) {
            classBoxes[static_cast<int>(boxes[i].type)].push_back(i);
        }

        for (const auto& classPair : classBoxes) {
            const auto& indices = classPair.second;
            for (size_t i = 0; i < indices.size(); ++i) {
                size_t idx = indices[i];
                if (suppressed[idx]) continue;

                result.push_back(boxes[idx]);

                // 对同类别框进行IoU抑制
                for (size_t j = i + 1; j < indices.size(); ++j) {
                    size_t jdx = indices[j];
                    if (!suppressed[jdx] && calculateIoU(boxes[idx], boxes[jdx]) > iouThreshold) {
                        suppressed[jdx] = true;
                    }
                }
            }
        }

        LOGI("NMS reduced boxes from %zu to %zu", boxes.size(), result.size());
        return result;
    }

    // ✅ 改进4: 完整的推理管道
    LayoutResult getLayoutBoxesImproved(cv::Mat &src, float boxScoreThresh = 0.2f) {
        LayoutResult result;

        if (!session) {
            LOGI("Session not initialized");
            return result;
        }

        auto startTime = std::chrono::high_resolution_clock::now();

        try {
            // 1. 改进的预处理
            PreprocessParams params;
            cv::Mat inputImage = preprocessImageLetterbox(src, params);

            // 2. 准备输入张量
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

            // 3. 创建输入张量并推理
            Ort::MemoryInfo memoryInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
            Ort::Value inputTensor = Ort::Value::CreateTensor<float>(
                memoryInfo, inputTensorData.data(), inputTensorData.size(),
                inputShape.data(), inputShape.size());

            Ort::AllocatorWithDefaultOptions allocator;
            char* inputName = session->GetInputNameAllocated(0, allocator).release();
            size_t numOutputNodes = session->GetOutputCount();
            std::vector<const char*> outputNames;
            for (size_t i = 0; i < numOutputNodes; i++) {
                char* outputName = session->GetOutputNameAllocated(i, allocator).release();
                outputNames.push_back(outputName);
            }

            auto outputTensors = session->Run(Ort::RunOptions{nullptr},
                                             &inputName, &inputTensor, 1,
                                             outputNames.data(), numOutputNodes);

            // 释放名称内存
            allocator.Free(const_cast<void*>(static_cast<const void*>(inputName)));
            for (size_t i = 0; i < numOutputNodes; i++) {
                allocator.Free(const_cast<void*>(static_cast<const void*>(outputNames[i])));
            }

            // 4. 改进的输出解析
            std::vector<LayoutBox> boxes;
            if (outputTensors.size() >= 1) {
                auto& outputTensor = outputTensors[0];
                auto outputShapeInfo = outputTensor.GetTensorTypeAndShapeInfo();
                std::vector<int64_t> outputShape = outputShapeInfo.GetShape();
                float* outputData = outputTensor.GetTensorMutableData<float>();

                boxes = parseYOLOv8OutputImproved(outputData, outputShape, params, boxScoreThresh);
            }

            // 5. 改进的NMS
            boxes = nmsBoxesImproved(boxes, 0.4f);

            // 6. 生成结果
            auto endTime = std::chrono::high_resolution_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

            result.layoutBoxes = boxes;
            result.layoutImg = src.clone();
            result.layoutNetTime = static_cast<double>(duration.count());
            result.markdown = generateLayoutMarkdown(result);

            // 7. 绘制检测结果
            drawLayoutDetections(result.layoutImg, boxes);

            LOGI("Improved DOCLAYOUT analysis: %zu boxes detected in %.2fms",
                 boxes.size(), result.layoutNetTime);

        } catch (const std::exception& e) {
            LOGI("Exception during improved layout analysis: %s", e.what());
        }

        return result;
    }
};