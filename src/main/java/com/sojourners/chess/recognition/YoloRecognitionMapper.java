package com.sojourners.chess.recognition;

import java.util.Arrays;
import java.util.List;

/**
 * Converts YOLO center-based detections into the canonical 9x10 grid while
 * retaining the model's actual confidence values.
 */
public final class YoloRecognitionMapper {

    private YoloRecognitionMapper() {
    }

    public static RecognitionCandidate map(int imageWidth, int imageHeight, long inputBytes,
                                           List<Detection> detections, String modelVersion) {
        char[][] board = emptyBoard();
        double[][] confidences = emptyConfidences();
        Detection boardDetection = largestBoard(detections);
        if (boardDetection == null) {
            return new RecognitionCandidate(
                    imageWidth, imageHeight, inputBytes, null,
                    board, confidences, Double.NaN, modelVersion
            );
        }

        int left = (int) Math.round(boardDetection.centerX - boardDetection.width / 2.0);
        int top = (int) Math.round(boardDetection.centerY - boardDetection.height / 2.0);
        int width = (int) Math.round(boardDetection.width);
        int height = (int) Math.round(boardDetection.height);
        RecognitionCandidate.BoardBounds bounds =
                new RecognitionCandidate.BoardBounds(left, top, width, height);

        double fileSpacing = boardDetection.width / 8.0;
        double rowSpacing = boardDetection.height / 9.0;
        if (detections != null && fileSpacing > 0 && rowSpacing > 0) {
            for (Detection detection : detections) {
                if (detection == null || detection.label == '0'
                        || !Double.isFinite(detection.centerX)
                        || !Double.isFinite(detection.centerY)) {
                    continue;
                }
                int file = (int) Math.floor(
                        (detection.centerX - (left - fileSpacing / 2.0)) / fileSpacing);
                int row = (int) Math.floor(
                        (detection.centerY - (top - rowSpacing / 2.0)) / rowSpacing);
                if (file < 0 || file > 8 || row < 0 || row > 9) {
                    continue;
                }
                if (Double.isNaN(confidences[row][file])
                        || detection.confidence > confidences[row][file]) {
                    board[row][file] = detection.label;
                    confidences[row][file] = detection.confidence;
                }
            }
        }

        return new RecognitionCandidate(
                imageWidth, imageHeight, inputBytes, bounds,
                board, confidences, boardDetection.confidence, modelVersion
        );
    }

    private static Detection largestBoard(List<Detection> detections) {
        if (detections == null) {
            return null;
        }
        Detection largest = null;
        double largestArea = -1;
        for (Detection detection : detections) {
            if (detection == null || detection.label != '0'
                    || !Double.isFinite(detection.width) || !Double.isFinite(detection.height)
                    || detection.width <= 0 || detection.height <= 0) {
                continue;
            }
            double area = detection.width * detection.height;
            if (area > largestArea) {
                largest = detection;
                largestArea = area;
            }
        }
        return largest;
    }

    private static char[][] emptyBoard() {
        char[][] board = new char[10][9];
        for (char[] row : board) {
            Arrays.fill(row, ' ');
        }
        return board;
    }

    private static double[][] emptyConfidences() {
        double[][] confidences = new double[10][9];
        for (double[] row : confidences) {
            Arrays.fill(row, Double.NaN);
        }
        return confidences;
    }

    public record Detection(char label, double centerX, double centerY,
                            double width, double height, double confidence) { }
}
