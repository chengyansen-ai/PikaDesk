package com.sojourners.chess.linker;

/** Aligns the local side-to-move with the color of an already observed move. */
public record ObservedTurnAlignment(boolean moverRed, boolean corrected) {

    private static final String PIECES = "rnbakcpRNBAKCP";

    public static ObservedTurnAlignment fromMove(
            boolean expectedRedToMove,
            char movedPiece) {
        if (PIECES.indexOf(movedPiece) < 0) {
            throw new IllegalArgumentException("observed move source must contain a chess piece");
        }
        boolean moverRed = Character.isUpperCase(movedPiece);
        return new ObservedTurnAlignment(
                moverRed,
                moverRed != expectedRedToMove);
    }

    public boolean nextRedToMove() {
        return !moverRed;
    }
}
