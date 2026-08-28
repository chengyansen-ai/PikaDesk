package com.sojourners.chess.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StrictChineseMoveDecoderTest {

    private static final String INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    private final StrictChineseMoveDecoder decoder = new StrictChineseMoveDecoder();

    @Test
    void decodesTheMixedTraditionalCcpdOpeningDialect() {
        StrictChineseMoveDecoder.DecodedMove red = decoder.decode(INITIAL_FEN, "炮二平五");
        assertEquals("h2e2", red.ucci());
        assertEquals("炮二平五", red.canonicalNotation());

        StrictChineseMoveDecoder.DecodedMove black = decoder.decode(red.nextFen(), "馬８進７");
        assertEquals("h9g7", black.ucci());
        assertEquals("马８进７", black.canonicalNotation());

        StrictChineseMoveDecoder.DecodedMove secondRed = decoder.decode(black.nextFen(), "馬二進三");
        assertEquals("h0g2", secondRed.ucci());
    }

    @Test
    void acceptsOnlyTheDocumentedTraditionalCharacterVariants() {
        StrictChineseMoveDecoder.DecodedMove move = decoder.decode(INITIAL_FEN, "砲二平五");

        assertEquals("h2e2", move.ucci());
        assertEquals("炮二平五", move.canonicalNotation());
    }

    @Test
    void rejectsMalformedWrongSideAndNonCanonicalMoves() {
        assertEquals("MALFORMED_NOTATION", failure(INITIAL_FEN, "炮二平五.exe").code());
        assertEquals("WRONG_SIDE_OR_ILLEGAL", failure(INITIAL_FEN, "馬８進７").code());
        assertEquals("WRONG_SIDE_OR_ILLEGAL", failure(INITIAL_FEN, "馬一進三").code());
        assertEquals("WRONG_SIDE_OR_ILLEGAL", failure(INITIAL_FEN, "炮二平八").code());
    }

    private StrictChineseMoveDecoder.DecodeException failure(String fen, String move) {
        return assertThrows(StrictChineseMoveDecoder.DecodeException.class,
                () -> decoder.decode(fen, move));
    }
}
