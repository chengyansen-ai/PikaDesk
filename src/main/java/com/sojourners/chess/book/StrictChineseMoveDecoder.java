package com.sojourners.chess.book;

import com.sojourners.chess.game.tree.GameTree;
import com.sojourners.chess.util.XiangqiUtils;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strictly decodes the four-character Chinese notation used by the licensed
 * CCPD source. This boundary deliberately accepts only the documented
 * traditional-character variants and revalidates every decoded move against
 * the current position.
 */
public final class StrictChineseMoveDecoder {

    private static final Map<Character, Character> VARIANTS = Map.of(
            '馬', '马',
            '車', '车',
            '砲', '炮',
            '進', '进',
            '後', '后',
            '帥', '帅',
            '將', '将');

    private static final Set<Character> ALLOWED = Set.of(
            '车', '马', '相', '象', '士', '仕', '将', '帅', '炮', '卒', '兵',
            '前', '中', '后', '一', '二', '三', '四', '五', '六', '七', '八', '九',
            '１', '２', '３', '４', '５', '６', '７', '８', '９', '进', '退', '平');

    public DecodedMove decode(String fen, String notation) {
        String normalizedNotation = normalizeNotation(notation);
        GameTree tree;
        try {
            tree = GameTree.create(fen);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException("MALFORMED_POSITION", exception);
        }

        char[][] board = XiangqiUtils.fenToBoard(tree.current().positionFen());
        StringBuilder ucciBuilder = new StringBuilder(4);
        try {
            XiangqiUtils.translateCnMove(board, ucciBuilder, normalizedNotation);
        } catch (RuntimeException exception) {
            throw new DecodeException("WRONG_SIDE_OR_ILLEGAL", exception);
        }
        String ucci = ucciBuilder.toString();
        if (!ucci.matches("[a-i][0-9][a-i][0-9]")) {
            throw new DecodeException("WRONG_SIDE_OR_ILLEGAL");
        }

        StringBuilder canonicalBuilder = new StringBuilder(4);
        try {
            XiangqiUtils.translate(board, canonicalBuilder, ucci, false);
        } catch (RuntimeException exception) {
            throw new DecodeException("WRONG_SIDE_OR_ILLEGAL", exception);
        }
        String canonical = canonicalBuilder.toString();
        if (!canonical.equals(normalizedNotation)) {
            throw new DecodeException("NON_CANONICAL_NOTATION");
        }

        try {
            tree.insert(tree.current().id(), ucci);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new DecodeException("WRONG_SIDE_OR_ILLEGAL", exception);
        }
        return new DecodedMove(ucci, canonical, tree.current().positionFen());
    }

    private String normalizeNotation(String notation) {
        if (notation == null || notation.length() != 4) {
            throw new DecodeException("MALFORMED_NOTATION");
        }
        StringBuilder normalized = new StringBuilder(4);
        for (int index = 0; index < notation.length(); index++) {
            char symbol = VARIANTS.getOrDefault(notation.charAt(index), notation.charAt(index));
            if (!ALLOWED.contains(symbol)) {
                throw new DecodeException("MALFORMED_NOTATION");
            }
            normalized.append(symbol);
        }
        return normalized.toString();
    }

    public record DecodedMove(String ucci, String canonicalNotation, String nextFen) {
        public DecodedMove {
            Objects.requireNonNull(ucci, "ucci");
            Objects.requireNonNull(canonicalNotation, "canonicalNotation");
            Objects.requireNonNull(nextFen, "nextFen");
        }
    }

    public static final class DecodeException extends IllegalArgumentException {

        private final String code;

        private DecodeException(String code) {
            super(code);
            this.code = code;
        }

        private DecodeException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
