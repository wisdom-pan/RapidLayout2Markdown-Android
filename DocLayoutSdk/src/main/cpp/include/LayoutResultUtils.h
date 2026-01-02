#ifndef __LAYOUT_RESULT_UTILS_H__
#define __LAYOUT_RESULT_UTILS_H__

#include <jni.h>
#include "OcrStruct.h"

class LayoutResultUtils {
public:
    LayoutResultUtils(JNIEnv *env, LayoutResult &layoutResult, jobject layoutImg);

    ~LayoutResultUtils();

    jobject getJObject();

private:
    JNIEnv *jniEnv;
    jobject jLayoutResult;

    jclass newJListClass();

    jmethodID getListConstructor(jclass clazz);

    jobject getLayoutBox(LayoutBox &layoutBox);

    jobject getLayoutBoxes(std::vector<LayoutBox> &layoutBoxes);

    jobject newJPoint(cv::Point &point);

    jobject newJBoxPoint(std::vector<cv::Point> &boxPoint);

    jobject newJLayoutType(LayoutType layoutType);

};

#endif //__LAYOUT_RESULT_UTILS_H__