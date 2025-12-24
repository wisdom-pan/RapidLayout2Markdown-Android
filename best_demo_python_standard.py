#!/usr/bin/env python3
"""
DOCLAYOUT_DOCSTRUCTBENCH - Python标准实现
基于YOLOv8的文档布局检测标准实现

作者: 基于Ultralytics YOLOv8标准
日期: 2025-12-07
"""

import cv2
import numpy as np
import onnxruntime as ort
from typing import List, Tuple, Dict, Any
import argparse
import os
import json
from pathlib import Path

# DOCLAYOUT_DOCSTRUCTBENCH 配置
DOCLAYOUT_CLASSES = [
    "title", "plain text", "abandon", "figure", "figure_caption",
    "table", "table_caption", "table_footnote", "isolate_formula", "formula_caption"
]

class DocLayoutAnalyzer:
    """DOCLAYOUT_DOCSTRUCTBENCH 标准实现类"""

    def __init__(self, model_path: str, conf_threshold: float = 0.2, iou_threshold: float = 0.4):
        """
        初始化文档布局分析器

        Args:
            model_path: ONNX模型路径
            conf_threshold: 置信度阈值
            iou_threshold: NMS IoU阈值
        """
        self.model_path = model_path
        self.conf_threshold = conf_threshold
        self.iou_threshold = iou_threshold
        self.input_size = (1024, 1024)  # DOCLAYOUT_DOCSTRUCTBENCH使用1024x1024

        # 初始化ONNX Runtime
        self.session = None
        self.input_name = None
        self.output_names = None

        self._load_model()

    def _load_model(self):
        """加载ONNX模型"""
        try:
            # 创建ONNX Runtime会话
            providers = ['CUDAExecutionProvider', 'CPUExecutionProvider']
            self.session = ort.InferenceSession(self.model_path, providers=providers)

            # 获取输入输出信息
            self.input_name = self.session.get_inputs()[0].name
            self.output_names = [output.name for output in self.session.get_outputs()]

            print(f"✅ Model loaded: {self.model_path}")
            print(f"📋 Input name: {self.input_name}")
            print(f"📋 Output names: {self.output_names}")

        except Exception as e:
            print(f"❌ Failed to load model: {e}")
            raise

    def preprocess_image(self, image: np.ndarray) -> Tuple[np.ndarray, float, Tuple[int, int]]:
        """
        标准YOLOv8预处理 - Letterbox保持宽高比

        Args:
            image: 输入图像 (BGR格式)

        Returns:
            preprocessed_image: 预处理后的图像
            gain: 缩放比例
            padding: padding大小 (pad_w, pad_h)
        """
        original_shape = image.shape[:2]  # (h, w)

        # 1. 计算gain（保持宽高比）
        gain = min(self.input_size[1] / original_shape[1], self.input_size[0] / original_shape[0])

        # 2. 计算新的尺寸
        new_shape = (int(original_shape[1] * gain), int(original_shape[0] * gain))

        # 3. 计算padding（与YOLOv8标准一致）
        pad_w = round((self.input_size[1] - new_shape[0]) / 2 - 0.1)
        pad_h = round((self.input_size[0] - new_shape[1]) / 2 - 0.1)
        padding = (pad_w, pad_h)

        # 4. Resize保持宽高比
        resized = cv2.resize(image, new_shape, interpolation=cv2.INTER_LINEAR)

        # 5. 添加padding（使用114,114,114灰色，与YOLO标准一致）
        padded = cv2.copyMakeBorder(resized, pad_h, pad_h, pad_w, pad_w,
                                   cv2.BORDER_CONSTANT, value=(114, 114, 114))

        # 6. BGR2RGB + 归一化
        rgb = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB)
        normalized = rgb.astype(np.float32) / 255.0

        # 7. HWC -> CHW
        chw = normalized.transpose(2, 0, 1)

        # 8. 添加batch维度
        batched = np.expand_dims(chw, axis=0)

        print(f"🔧 Preprocess: {original_shape[::-1]} -> {new_shape} -> {self.input_size}")
        print(f"📏 Gain: {gain:.3f}, Padding: {padding}")

        return batched, gain, padding

    def scale_boxes(self, boxes: np.ndarray, original_shape: Tuple[int, int],
                   gain: float, padding: Tuple[int, int]) -> np.ndarray:
        """
        标准YOLOv8 scale_boxes实现

        Args:
            boxes: 检测框坐标 [N, 4] (x1, y1, x2, y2)
            original_shape: 原始图像尺寸 (h, w)
            gain: 缩放比例
            padding: padding大小 (pad_w, pad_h)

        Returns:
            scaled_boxes: 缩放后的坐标
        """
        pad_w, pad_h = padding

        # 1. 减去padding
        boxes[:, [0, 2]] -= pad_w  # x coordinates
        boxes[:, [1, 3]] -= pad_h  # y coordinates

        # 2. 除以gain
        boxes /= gain

        # 3. clip_boxes - 限制在原始图像范围内
        boxes[:, [0, 2]] = np.clip(boxes[:, [0, 2]], 0, original_shape[1])  # width
        boxes[:, [1, 3]] = np.clip(boxes[:, [1, 3]], 0, original_shape[0])  # height

        return boxes

    def non_max_suppression(self, boxes: np.ndarray, scores: np.ndarray,
                          class_ids: np.ndarray) -> List[int]:
        """
        标准NMS实现

        Args:
            boxes: 检测框坐标 [N, 4]
            scores: 置信度分数 [N]
            class_ids: 类别ID [N]

        Returns:
            keep_indices: 保留的检测框索引
        """
        if len(boxes) == 0:
            return []

        # 按分数降序排序
        indices = np.argsort(scores)[::-1]

        keep = []
        while len(indices) > 0:
            # 保留分数最高的框
            current = indices[0]
            keep.append(current)

            if len(indices) == 1:
                break

            # 计算当前框与其他框的IoU
            current_box = boxes[current]
            other_boxes = boxes[indices[1:]]

            # 计算IoU
            x1 = np.maximum(current_box[0], other_boxes[:, 0])
            y1 = np.maximum(current_box[1], other_boxes[:, 1])
            x2 = np.minimum(current_box[2], other_boxes[:, 2])
            y2 = np.minimum(current_box[3], other_boxes[:, 3])

            intersection = np.maximum(0, x2 - x1) * np.maximum(0, y2 - y1)
            current_area = (current_box[2] - current_box[0]) * (current_box[3] - current_box[1])
            other_areas = (other_boxes[:, 2] - other_boxes[:, 0]) * (other_boxes[:, 3] - other_boxes[:, 1])

            union = current_area + other_areas - intersection
            iou = intersection / np.maximum(union, 1e-6)

            # 保留IoU小于阈值的框（只对不同类别进行NMS）
            same_class = class_ids[indices[1:]] == class_ids[current]
            indices = indices[1:][np.where(~same_class | (iou < self.iou_threshold))[0]]

        return keep

    def parse_yolo_output(self, output: np.ndarray, original_shape: Tuple[int, int],
                         gain: float, padding: Tuple[int, int]) -> List[Dict[str, Any]]:
        """
        解析YOLOv8输出

        Args:
            output: 模型输出 [1, N, 6] (x1, y1, x2, y2, conf, class_id)
            original_shape: 原始图像尺寸 (h, w)
            gain: 缩放比例
            padding: padding大小

        Returns:
            detections: 检测结果列表
        """
        # 移除batch维度
        output = output.squeeze(0)  # [N, 6]

        detections = []

        for i in range(len(output)):
            x1, y1, x2, y2, conf, class_id = output[i]

            # 过滤低置信度和无效类别
            if conf < self.conf_threshold or class_id < 0 or class_id >= len(DOCLAYOUT_CLASSES):
                continue

            class_id = int(class_id)

            # 确保坐标有效
            if x2 <= x1 or y2 <= y1:
                continue

            # 坐标变换
            box = np.array([x1, y1, x2, y2])
            scaled_box = self.scale_boxes(box.reshape(1, -1), original_shape, gain, padding)[0]

            detection = {
                'bbox': scaled_box.astype(int).tolist(),  # [x1, y1, x2, y2]
                'confidence': float(conf),
                'class_id': class_id,
                'class_name': DOCLAYOUT_CLASSES[class_id]
            }

            detections.append(detection)

        return detections

    def detect(self, image: np.ndarray) -> Dict[str, Any]:
        """
        执行文档布局检测

        Args:
            image: 输入图像 (BGR格式)

        Returns:
            results: 检测结果
        """
        original_shape = image.shape[:2]  # (h, w)

        # 1. 预处理
        input_tensor, gain, padding = self.preprocess_image(image)

        # 2. 推理
        outputs = self.session.run(self.output_names, {self.input_name: input_tensor})
        output = outputs[0]  # [1, N, 6]

        print(f"🔮 Model output shape: {output.shape}")

        # 3. 解析输出
        detections = self.parse_yolo_output(output, original_shape, gain, padding)

        print(f"📦 Raw detections: {len(detections)}")

        # 4. NMS
        if detections:
            boxes = np.array([det['bbox'] for det in detections])
            scores = np.array([det['confidence'] for det in detections])
            class_ids = np.array([det['class_id'] for det in detections])

            keep_indices = self.non_max_suppression(boxes, scores, class_ids)
            detections = [detections[i] for i in keep_indices]

        print(f"🎯 Final detections after NMS: {len(detections)}")

        # 5. 统计结果
        class_counts = {}
        for det in detections:
            class_name = det['class_name']
            class_counts[class_name] = class_counts.get(class_name, 0) + 1

        results = {
            'detections': detections,
            'class_counts': class_counts,
            'total_detections': len(detections),
            'original_shape': original_shape,
            'confidence_threshold': self.conf_threshold,
            'iou_threshold': self.iou_threshold
        }

        return results

    def visualize_results(self, image: np.ndarray, results: Dict[str, Any],
                         output_path: str = None) -> np.ndarray:
        """
        可视化检测结果

        Args:
            image: 原始图像
            results: 检测结果
            output_path: 输出图像路径

        Returns:
            annotated_image: 标注后的图像
        """
        annotated = image.copy()

        # 为每个类别分配颜色
        colors = self._generate_colors(len(DOCLAYOUT_CLASSES))

        for detection in results['detections']:
            bbox = detection['bbox']
            class_name = detection['class_name']
            confidence = detection['confidence']
            class_id = detection['class_id']
            color = colors[class_id]

            # 绘制检测框
            cv2.rectangle(annotated, (bbox[0], bbox[1]), (bbox[2], bbox[3]), color, 2)

            # 绘制标签
            label = f"{class_name} {confidence:.2f}"
            label_size = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)[0]

            # 标签背景
            cv2.rectangle(annotated,
                         (bbox[0], bbox[1] - label_size[1] - 10),
                         (bbox[0] + label_size[0], bbox[1]),
                         color, -1)

            # 标签文字
            cv2.putText(annotated, label,
                       (bbox[0], bbox[1] - 5),
                       cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)

        if output_path:
            cv2.imwrite(output_path, annotated)
            print(f"💾 Visualization saved to: {output_path}")

        return annotated

    def _generate_colors(self, num_classes: int) -> List[Tuple[int, int, int]]:
        """为每个类别生成不同的颜色"""
        np.random.seed(42)  # 固定随机种子确保颜色一致
        colors = []
        for i in range(num_classes):
            color = tuple(np.random.randint(0, 255, 3).tolist())
            colors.append(color)
        return colors

    def generate_markdown_report(self, results: Dict[str, Any]) -> str:
        """生成Markdown格式报告"""
        markdown = "# DOCLAYOUT_DOCSTRUCTBENCH 检测报告\n\n"

        # 摘要信息
        markdown += "## 📊 检测摘要\n\n"
        markdown += f"- **总检测数量**: {results['total_detections']}\n"
        markdown += f"- **置信度阈值**: {results['confidence_threshold']}\n"
        markdown += f"- **IoU阈值**: {results['iou_threshold']}\n"
        markdown += f"- **原始尺寸**: {results['original_shape'][0]} × {results['original_shape'][1]}\n\n"

        # 类别统计
        markdown += "## 📈 类别统计\n\n"
        for class_name, count in results['class_counts'].items():
            markdown += f"- **{class_name}**: {count}\n"
        markdown += "\n"

        # 详细检测结果
        markdown += "## 🔍 详细检测结果\n\n"
        for i, detection in enumerate(results['detections'], 1):
            bbox = detection['bbox']
            markdown += f"### {i}. {detection['class_name']}\n"
            markdown += f"- **位置**: ({bbox[0]}, {bbox[1]}) → ({bbox[2]}, {bbox[3]})\n"
            markdown += f"- **置信度**: {detection['confidence']:.3f}\n"
            markdown += f"- **面积**: {(bbox[2]-bbox[0]) * (bbox[3]-bbox[1])} 像素²\n\n"

        return markdown


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description='DOCLAYOUT_DOCSTRUCTBENCH 文档布局检测')
    parser.add_argument('--model', type=str, required=True, help='ONNX模型路径')
    parser.add_argument('--image', type=str, required=True, help='输入图像路径')
    parser.add_argument('--output', type=str, default='detection_result.jpg', help='输出图像路径')
    parser.add_argument('--report', type=str, default='detection_report.md', help='输出报告路径')
    parser.add_argument('--conf', type=float, default=0.2, help='置信度阈值')
    parser.add_argument('--iou', type=float, default=0.4, help='NMS IoU阈值')

    args = parser.parse_args()

    # 检查文件是否存在
    if not os.path.exists(args.model):
        print(f"❌ Model file not found: {args.model}")
        return

    if not os.path.exists(args.image):
        print(f"❌ Image file not found: {args.image}")
        return

    # 创建分析器
    print("🚀 Initializing DOCLAYOUT_DOCSTRUCTBENCH analyzer...")
    analyzer = DocLayoutAnalyzer(args.model, args.conf, args.iou)

    # 读取图像
    print("📖 Loading image...")
    image = cv2.imread(args.image)
    if image is None:
        print(f"❌ Failed to load image: {args.image}")
        return

    # 执行检测
    print("🔍 Running detection...")
    results = analyzer.detect(image)

    # 可视化结果
    print("🎨 Visualizing results...")
    analyzer.visualize_results(image, results, args.output)

    # 生成报告
    print("📝 Generating report...")
    report = analyzer.generate_markdown_report(results)
    with open(args.report, 'w', encoding='utf-8') as f:
        f.write(report)

    print(f"✅ Detection completed!")
    print(f"📊 Total detections: {results['total_detections']}")
    print(f"💾 Result image: {args.output}")
    print(f"📄 Report: {args.report}")


if __name__ == "__main__":
    main()