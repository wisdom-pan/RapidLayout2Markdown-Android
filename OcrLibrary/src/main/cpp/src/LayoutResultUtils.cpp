#include <OcrUtils.h>
#include "LayoutResultUtils.h"

LayoutResultUtils::LayoutResultUtils(JNIEnv *env, LayoutResult &layoutResult, jobject layoutImg) {
    jniEnv = env;

    jclass jLayoutResultClass = env->FindClass("com/benjaminwan/ocrlibrary/LayoutResult");

    if (jLayoutResultClass == NULL) {
        LOGE("LayoutResult class is null");
    }

    jmethodID jLayoutResultConstructor = env->GetMethodID(jLayoutResultClass, "<init>",
                                                           "(DLjava/util/ArrayList;Landroid/graphics/Bitmap;Ljava/lang/String;)V");

    jobject layoutBoxes = getLayoutBoxes(layoutResult.layoutBoxes);
    jdouble layoutNetTime = (jdouble) layoutResult.layoutNetTime;
    jstring jMarkdown = jniEnv->NewStringUTF(layoutResult.markdown.c_str());

    jLayoutResult = env->NewObject(jLayoutResultClass, jLayoutResultConstructor, layoutNetTime,
                                    layoutBoxes, layoutImg, jMarkdown);
}

LayoutResultUtils::~LayoutResultUtils() {
    jniEnv = NULL;
}

jobject LayoutResultUtils::getJObject() {
    return jLayoutResult;
}

jclass LayoutResultUtils::newJListClass() {
    jclass clazz = jniEnv->FindClass("java/util/ArrayList");
    if (clazz == NULL) {
        LOGE("ArrayList class is null");
        return NULL;
    }
    return clazz;
}

jmethodID LayoutResultUtils::getListConstructor(jclass clazz) {
    jmethodID constructor = jniEnv->GetMethodID(clazz, "<init>", "()V");
    return constructor;
}

jobject LayoutResultUtils::getLayoutBox(LayoutBox &layoutBox) {
    jclass clazz = jniEnv->FindClass("com/benjaminwan/ocrlibrary/LayoutBox");
    if (clazz == NULL) {
        LOGE("LayoutBox class is null");
        return NULL;
    }

    // 正确映射LayoutType枚举
    jclass typeClass = jniEnv->FindClass("com/benjaminwan/ocrlibrary/LayoutType");
    jobject layoutType = NULL;
    if (typeClass != NULL) {
        // 根据C++ LayoutType正确映射到Java LayoutType
        jfieldID enumField = NULL;
        int typeId = static_cast<int>(layoutBox.type);

        switch (typeId) {
            case 0: // TITLE
                enumField = jniEnv->GetStaticFieldID(typeClass, "TITLE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping TITLE enum (typeId=%d)", typeId);
                break;
            case 1: // PLAIN_TEXT
                enumField = jniEnv->GetStaticFieldID(typeClass, "PLAIN_TEXT", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping PLAIN_TEXT enum (typeId=%d)", typeId);
                break;
            case 2: // ABANDON
                enumField = jniEnv->GetStaticFieldID(typeClass, "ABANDON", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping ABANDON enum (typeId=%d)", typeId);
                break;
            case 3: // FIGURE
                enumField = jniEnv->GetStaticFieldID(typeClass, "FIGURE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping FIGURE enum (typeId=%d)", typeId);
                break;
            case 4: // FIGURE_CAPTION
                enumField = jniEnv->GetStaticFieldID(typeClass, "FIGURE_CAPTION", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping FIGURE_CAPTION enum (typeId=%d)", typeId);
                break;
            case 5: // TABLE
                enumField = jniEnv->GetStaticFieldID(typeClass, "TABLE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping TABLE enum (typeId=%d)", typeId);
                break;
            case 6: // TABLE_CAPTION
                enumField = jniEnv->GetStaticFieldID(typeClass, "TABLE_CAPTION", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping TABLE_CAPTION enum (typeId=%d)", typeId);
                break;
            case 7: // TABLE_FOOTNOTE
                enumField = jniEnv->GetStaticFieldID(typeClass, "TABLE_FOOTNOTE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping TABLE_FOOTNOTE enum (typeId=%d)", typeId);
                break;
            case 8: // ISOLATE_FORMULA
                enumField = jniEnv->GetStaticFieldID(typeClass, "ISOLATE_FORMULA", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping ISOLATE_FORMULA enum (typeId=%d)", typeId);
                break;
            case 9: // FORMULA_CAPTION
                enumField = jniEnv->GetStaticFieldID(typeClass, "FORMULA_CAPTION", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                LOGI("Mapping FORMULA_CAPTION enum (typeId=%d)", typeId);
                break;
            default:
                LOGI("Unknown typeId %d, using UNKNOWN", typeId);
                enumField = jniEnv->GetStaticFieldID(typeClass, "UNKNOWN", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
                break;
        }

        if (enumField != NULL) {
            layoutType = jniEnv->GetStaticObjectField(typeClass, enumField);
            if (layoutType != NULL) {
                LOGI("Successfully mapped enum for %s (typeId=%d)", layoutBox.typeName.c_str(), typeId);
            } else {
                LOGE("Failed to get enum object for typeId=%d", typeId);
            }
        } else {
            LOGE("Failed to get enum field ID for typeId=%d", typeId);
        }
    }

    if (layoutType == NULL) {
        LOGE("Cannot get LayoutType enum for %s, failing", layoutBox.typeName.c_str());
        return NULL;
    }

    jobject boxPoint = newJBoxPoint(layoutBox.boxPoint);
    jfloat score = (jfloat) layoutBox.score;
    jstring jTypeName = jniEnv->NewStringUTF(layoutBox.typeName.c_str());

    // 使用完整的构造函数
    jmethodID constructor = jniEnv->GetMethodID(clazz, "<init>",
                                                  "(Ljava/util/ArrayList;FLcom/benjaminwan/ocrlibrary/LayoutType;Ljava/lang/String;)V");

    jobject jLayoutBox = jniEnv->NewObject(clazz, constructor, boxPoint, score, layoutType, jTypeName);
    return jLayoutBox;
}

jobject LayoutResultUtils::getLayoutBoxes(std::vector<LayoutBox> &layoutBoxes) {
    jclass arrayListClass = newJListClass();
    jmethodID constructor = getListConstructor(arrayListClass);
    jmethodID addMethod = jniEnv->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jobject jLayoutBoxes = jniEnv->NewObject(arrayListClass, constructor);
    for (int i = 0; i < layoutBoxes.size(); ++i) {
        jobject jLayoutBox = getLayoutBox(layoutBoxes[i]);
        jniEnv->CallBooleanMethod(jLayoutBoxes, addMethod, jLayoutBox);
    }
    return jLayoutBoxes;
}

jobject LayoutResultUtils::newJPoint(cv::Point &point) {
    jclass clazz = jniEnv->FindClass("com/benjaminwan/ocrlibrary/Point");
    if (clazz == NULL) {
        LOGE("Point class is null");
        return NULL;
    }
    jmethodID constructor = jniEnv->GetMethodID(clazz, "<init>", "(II)V");
    jobject jPoint = jniEnv->NewObject(clazz, constructor, (jint) point.x, (jint) point.y);
    return jPoint;
}

jobject LayoutResultUtils::newJBoxPoint(std::vector<cv::Point> &boxPoint) {
    jclass arrayListClass = newJListClass();
    jmethodID constructor = getListConstructor(arrayListClass);
    jmethodID addMethod = jniEnv->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jobject jBoxPoint = jniEnv->NewObject(arrayListClass, constructor);
    for (int i = 0; i < boxPoint.size(); ++i) {
        jobject jPoint = newJPoint(boxPoint[i]);
        jniEnv->CallBooleanMethod(jBoxPoint, addMethod, jPoint);
    }
    return jBoxPoint;
}

jobject LayoutResultUtils::newJLayoutType(LayoutType layoutType) {
    int layoutTypeId = static_cast<int>(layoutType);
    LOGI("Converting LayoutType: %d", layoutTypeId);

    jclass clazz = jniEnv->FindClass("com/benjaminwan/ocrlibrary/LayoutType");
    if (clazz == NULL) {
        LOGE("LayoutType class is null");
        return NULL;
    }

    // Get the enum values based on LayoutType - DOCLAYOUT_DOCSTRUCTBENCH
    jfieldID fieldID = NULL;
    switch (layoutType) {
        case LayoutType::TITLE:
            LOGI("Mapping TITLE enum");
            fieldID = jniEnv->GetStaticFieldID(clazz, "TITLE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::PLAIN_TEXT:
            LOGI("Mapping PLAIN_TEXT enum");
            fieldID = jniEnv->GetStaticFieldID(clazz, "PLAIN_TEXT", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::ABANDON:
            fieldID = jniEnv->GetStaticFieldID(clazz, "ABANDON", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::FIGURE:
            fieldID = jniEnv->GetStaticFieldID(clazz, "FIGURE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::FIGURE_CAPTION:
            fieldID = jniEnv->GetStaticFieldID(clazz, "FIGURE_CAPTION", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::TABLE:
            fieldID = jniEnv->GetStaticFieldID(clazz, "TABLE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::TABLE_CAPTION:
            fieldID = jniEnv->GetStaticFieldID(clazz, "TABLE_CAPTION", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::TABLE_FOOTNOTE:
            fieldID = jniEnv->GetStaticFieldID(clazz, "TABLE_FOOTNOTE", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::ISOLATE_FORMULA:
            fieldID = jniEnv->GetStaticFieldID(clazz, "ISOLATE_FORMULA", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::FORMULA_CAPTION:
            fieldID = jniEnv->GetStaticFieldID(clazz, "FORMULA_CAPTION", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
        case LayoutType::UNKNOWN:
        default:
            LOGI("Mapping UNKNOWN enum (default case)");
            fieldID = jniEnv->GetStaticFieldID(clazz, "UNKNOWN", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
            break;
    }

    if (fieldID == NULL) {
        LOGE("LayoutType field ID is null, using UNKNOWN as fallback");
        // 尝试使用UNKNOWN作为fallback
        fieldID = jniEnv->GetStaticFieldID(clazz, "UNKNOWN", "Lcom/benjaminwan/ocrlibrary/LayoutType;");
        if (fieldID == NULL) {
            LOGE("Even UNKNOWN LayoutType field ID is null");
            return NULL;
        }
    }

    jobject jLayoutType = jniEnv->GetStaticObjectField(clazz, fieldID);
    return jLayoutType;
}