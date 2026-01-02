#ifndef __OCR_LAYOUTNET_H__
#define __OCR_LAYOUTNET_H__

#include "OcrStruct.h"
#include "onnxruntime/core/session/onnxruntime_cxx_api.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <vector>
#include <string>
#include <algorithm>
#include <map>

class LayoutNet {
public:
    LayoutNet();

    ~LayoutNet();

    void setNumThread(int numOfThread);

    void initModel(AAssetManager *mgr, const std::string &name);

    LayoutResult getLayoutBoxes(cv::Mat &src, float boxScoreThresh = 0.2f);

    std::string generateMarkdown(const LayoutResult &layoutResult);

private:
    Ort::Session *session;
    Ort::Env ortEnv = Ort::Env(ORT_LOGGING_LEVEL_ERROR, "LayoutNet");
    Ort::SessionOptions sessionOptions = Ort::SessionOptions();
    int numThread = 0;

    std::vector<Ort::AllocatedStringPtr> inputNamesPtr;
    std::vector<Ort::AllocatedStringPtr> outputNamesPtr;

    // DOCLAYOUT_DOCSTRUCTBENCH 模型配置
    static const int INPUT_WIDTH = 1024;
    static const int INPUT_HEIGHT = 1024;

    // DOCLAYOUT_DOCSTRUCTBENCH 支持的10个类别
    std::vector<std::string> getLayoutClassNames();

    // 将LayoutType转换为字符串
    std::string layoutTypeToString(LayoutType type);

    // 将整数转换为LayoutType
    LayoutType intToLayoutType(int classId);

    // 预处理图像 - YOLOv8风格
    cv::Mat preprocessImage(const cv::Mat &src);

    // 解析YOLOv8格式输出
    std::vector<LayoutBox> parseYOLOv8Output(float* outputData,
                                            const std::vector<int64_t>& outputShape,
                                            const cv::Mat& src,
                                            float confThreshold);

    // 计算IoU
    float calculateIoU(const LayoutBox &box1, const LayoutBox &box2);

    // NMS过滤重叠框
    std::vector<LayoutBox> nmsBoxes(std::vector<LayoutBox> &boxes, float iouThreshold = 0.4f);

    // 根据版面分析结果生成Markdown
    std::string generateLayoutMarkdown(const LayoutResult &layoutResult);

    // 像demo.py一样绘制检测结果：彩色框、区域类型标签、透明度遮罩
    void drawLayoutDetections(cv::Mat& img, const std::vector<LayoutBox>& boxes);

    // 获取区域类型对应的颜色 - DOCLAYOUT_DOCSTRUCTBENCH 10个类别
    cv::Scalar getLayoutColor(const LayoutType& type);

    // 绘制半透明遮罩
    void drawMask(cv::Mat& img, const std::vector<LayoutBox>& boxes, float alpha = 0.3f);

    // 绘制检测框和标签
    void drawBoxWithLabel(cv::Mat& img, const LayoutBox& box, const cv::Scalar& color);
};

#endif //__OCR_LAYOUTNET_H__