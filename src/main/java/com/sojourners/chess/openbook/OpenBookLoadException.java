package com.sojourners.chess.openbook;

import java.sql.SQLException;
import java.util.Objects;

/** A stable, user-presentable failure raised while opening a local book. */
public final class OpenBookLoadException extends SQLException {

    private final String code;

    public OpenBookLoadException(String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    OpenBookLoadException(String code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
