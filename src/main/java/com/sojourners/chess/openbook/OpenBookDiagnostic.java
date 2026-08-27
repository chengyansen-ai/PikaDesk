package com.sojourners.chess.openbook;

import java.nio.file.Path;
import java.util.Objects;

/** A bounded diagnostic that deliberately omits private absolute paths. */
public record OpenBookDiagnostic(String source, String code, String message) {

    public OpenBookDiagnostic {
        source = Objects.requireNonNull(source, "source");
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
    }

    public static OpenBookDiagnostic loadFailure(Path path, Exception failure) {
        String fileName = safeFileName(path);
        String code = failure instanceof OpenBookLoadException loadFailure
                ? loadFailure.code() : "LOAD_FAILED";
        return new OpenBookDiagnostic(fileName, code,
                "无法加载开局库 " + fileName + "（" + code + "）");
    }

    static OpenBookDiagnostic queryFailure(String source, String code, String message) {
        return new OpenBookDiagnostic(source, code, message);
    }

    private static String safeFileName(Path path) {
        if (path == null || path.getFileName() == null) {
            return "未知文件";
        }
        return path.getFileName().toString();
    }
}
