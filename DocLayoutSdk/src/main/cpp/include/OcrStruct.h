#ifndef __OCR_STRUCT_H__
#define __OCR_STRUCT_H__

#include "opencv2/core.hpp"
#include <vector>

struct ScaleParam {
    int srcWidth;
    int srcHeight;
    int dstWidth;
    int dstHeight;
    float ratioWidth;
    float ratioHeight;
};

struct TextBox {
    std::vector<cv::Point> boxPoint;
    float score;
};

struct Angle {
    int index;
    float score;
    double time;
};

struct TextLine {
    std::string text;
    std::vector<float> charScores;
    double time;
};

struct TextBlock {
    std::vector<cv::Point> boxPoint;
    float boxScore;
    int angleIndex;
    float angleScore;
    double angleTime;
    std::string text;
    std::vector<float> charScores;
    double crnnTime;
    double blockTime;
};

// DOCLAYOUT_DOCSTRUCTBENCH 支持的10个类别
enum class LayoutType {
    TITLE = 0,              // 0: title
    PLAIN_TEXT = 1,         // 1: plain text
    ABANDON = 2,            // 2: abandon
    FIGURE = 3,             // 3: figure
    FIGURE_CAPTION = 4,     // 4: figure_caption
    TABLE = 5,              // 5: table
    TABLE_CAPTION = 6,      // 6: table_caption
    TABLE_FOOTNOTE = 7,     // 7: table_footnote
    ISOLATE_FORMULA = 8,    // 8: isolate_formula
    FORMULA_CAPTION = 9,    // 9: formula_caption
    UNKNOWN = -1            // -1: unknown (error case)
};

struct LayoutBox {
    std::vector<cv::Point> boxPoint;
    float score;
    LayoutType type;
    std::string typeName;
    std::string ocrText;  // OCR识别的真实内容
    bool hasOcrText;      // 是否包含OCR文本
};

struct LayoutResult {
    double layoutNetTime;
    std::vector<LayoutBox> layoutBoxes;
    cv::Mat layoutImg;
    std::string markdown;
};

struct OcrResult {
    double dbNetTime;
    std::vector<TextBlock> textBlocks;
    cv::Mat boxImg;
    double detectTime;
    std::string strRes;
};

#endif //__OCR_STRUCT_H__
