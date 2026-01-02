#ifndef __OCR_LITE_H__
#define __OCR_LITE_H__

#include "opencv2/core.hpp"
#include "onnxruntime/core/session/onnxruntime_cxx_api.h"
#include "OcrStruct.h"
#include "DbNet.h"
#include "AngleNet.h"
#include "CrnnNet.h"
#include "LayoutNet.h"

class OcrLite {
public:
    OcrLite();

    ~OcrLite();

    void init(JNIEnv *jniEnv, jobject assetManager, int numOfThread, std::string detName,
              std::string clsName, std::string recName, std::string keysName, std::string layoutName);

    //void initLogger(bool isDebug);

    //void Logger(const char *format, ...);

    OcrResult detect(cv::Mat &src, cv::Rect &originRect, ScaleParam &scale,
                     float boxScoreThresh, float boxThresh,
                     float unClipRatio, bool doAngle, bool mostAngle);

    LayoutResult detectLayout(cv::Mat &src, float boxScoreThresh = 0.5f);

private:
    bool isLOG = true;
    DbNet dbNet;
    AngleNet angleNet;
    CrnnNet crnnNet;
    LayoutNet layoutNet;
};


#endif