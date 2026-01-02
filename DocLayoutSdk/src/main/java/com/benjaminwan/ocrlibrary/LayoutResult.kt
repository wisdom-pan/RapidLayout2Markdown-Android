package com.benjaminwan.ocrlibrary

import android.graphics.Bitmap
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// DOCLAYOUT_DOCSTRUCTBENCH 支持的10个类别
enum class LayoutType {
    TITLE,              // 0: title
    PLAIN_TEXT,         // 1: plain text
    ABANDON,            // 2: abandon
    FIGURE,             // 3: figure
    FIGURE_CAPTION,     // 4: figure_caption
    TABLE,              // 5: table
    TABLE_CAPTION,      // 6: table_caption
    TABLE_FOOTNOTE,     // 7: table_footnote
    ISOLATE_FORMULA,    // 8: isolate_formula
    FORMULA_CAPTION,    // 9: formula_caption
    UNKNOWN             // 255: unknown (backward compatibility)
}

@Parcelize
data class LayoutBox(
    val boxPoint: ArrayList<Point>,
    val score: Float,
    val type: LayoutType,
    val typeName: String
) : Parcelable

@Parcelize
data class LayoutResult(
    val layoutNetTime: Double,
    val layoutBoxes: ArrayList<LayoutBox>,
    val layoutImg: Bitmap,
    val markdown: String
) : Parcelable