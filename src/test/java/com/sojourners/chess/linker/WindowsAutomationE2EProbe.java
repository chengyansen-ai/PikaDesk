package com.sojourners.chess.linker;

import com.sojourners.chess.automation.BoardCoordinateMapper;
import com.sojourners.chess.recognition.RecognitionCandidate;
import com.sojourners.chess.recognition.RecognitionGate;
import com.sojourners.chess.recognition.RecognitionResult;
import com.sojourners.chess.yolo.Yolo11Model;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Manually invoked probe restricted to PikaDesk's offline test board. With no
 * arguments it finds the exact test-window title and uses the model-detected
 * board bounds. The optional single argument {@code black} selects a rotated
 * board. The legacy HWND plus four screen bounds remain available for diagnosis.
 */
public final class WindowsAutomationE2EProbe {

    private static final String ALLOWED_TITLE = "PikaDesk 本地自动化测试棋盘";

    private WindowsAutomationE2EProbe() { }

    public static void main(String[] args) throws AWTException, InterruptedException {
        ProbeArguments arguments = parseArguments(args);
        boolean blackAtBottom = arguments.blackAtBottom();
        WinDef.HWND handle = arguments.explicitCalibration()
                ? new WinDef.HWND(Pointer.createConstant(arguments.handleValue()))
                : findTestWindow();
        String title = windowTitle(handle);
        if (!ALLOWED_TITLE.equals(title)) {
            throw new IllegalArgumentException("refusing non-test target: " + title);
        }

        WindowsAutomationTarget target = WindowsAutomationTarget.attach(handle);
        BoardCoordinateMapper.TargetSnapshot snapshot = target.currentTarget().orElseThrow();
        BoardCoordinateMapper.ClientArea client = snapshot.clientArea();

        Yolo11Model model = new Yolo11Model();
        RecognitionGate.StabilityTracker recognition =
                new RecognitionGate(RecognitionGate.Policy.safeDefaults()).newStabilityTracker();
        Robot robot = new Robot();
        recognition.evaluate(model.recognize(capture(robot, client)));
        RecognitionResult stableBefore = recognition.evaluate(model.recognize(capture(robot, client)));
        if (!stableBefore.accepted()) {
            RecognitionResult.Rejection rejection = stableBefore.rejection().orElseThrow();
            throw new IllegalStateException("could not establish stable visual baseline: "
                    + rejection.reason() + " - " + rejection.detail());
        }
        RecognitionResult.AcceptedPosition accepted = stableBefore.position().orElseThrow();
        BoardCoordinateMapper.BoardBounds detected = detectedBoardBounds(accepted);
        BoardCoordinateMapper.BoardBounds board = arguments.explicitCalibration()
                ? toClientBounds(arguments.screenBoardBounds(), client)
                : detected;
        BoardCoordinateMapper.Calibration calibration = new BoardCoordinateMapper.Calibration(
                target.authorization().targetId(),
                target.authorization().targetRevision(),
                client,
                board,
                snapshot.dpi(),
                blackAtBottom ? BoardCoordinateMapper.Orientation.BLACK_AT_BOTTOM
                        : BoardCoordinateMapper.Orientation.RED_AT_BOTTOM
        );

        WindowsMoveCoordinator coordinator = new WindowsMoveCoordinator(
                target,
                () -> recognition.evaluate(model.recognize(capture(robot, client)))
        );
        if (!coordinator.arm()) {
            throw new IllegalStateException("could not arm coordinator");
        }
        char[][] visualBoard = accepted.boardCopy();
        char[][] canonicalBoard = blackAtBottom
                ? rotate180(visualBoard) : copy(visualBoard);
        WindowsMoveCoordinator.ExecutionOutcome outcome = null;
        for (int index = 0; index < arguments.moveCount(); index++) {
            ProbeMove move = arguments.moveCount() == 1
                    ? singleMove(blackAtBottom) : enduranceMove(index);
            outcome = coordinator.executeAndConfirm(
                    calibration,
                    canonicalBoard,
                    visualBoard,
                    move.canonical(),
                    move.visual(),
                    0,
                    30,
                    2_000
            );
            if (!outcome.confirmed()) {
                throw new IllegalStateException("visual confirmation failed at move "
                        + (index + 1) + " (" + move.ucci() + "): " + outcome.detail());
            }
            applyMove(canonicalBoard, canonicalGridMove(move.canonical()));
            applyMove(visualBoard, move.visual());
            if (arguments.moveCount() > 1
                    && ((index + 1) % 50 == 0 || index + 1 == arguments.moveCount())) {
                System.out.println("E2E_PROGRESS=" + (index + 1)
                        + "/" + arguments.moveCount());
            }
        }
        System.out.println("E2E_TARGET=" + target.authorization().targetId());
        System.out.println("E2E_DPI=" + snapshot.dpi());
        System.out.println("E2E_CLIENT=" + client);
        System.out.println("E2E_DETECTED_BOARD=" + detected);
        System.out.println("E2E_BOARD=" + board);
        System.out.println("E2E_CALIBRATION="
                + (arguments.explicitCalibration() ? "EXPLICIT" : "MODEL"));
        System.out.println("E2E_ORIENTATION="
                + (blackAtBottom ? "BLACK_AT_BOTTOM" : "RED_AT_BOTTOM"));
        System.out.println(arguments.moveCount() == 1
                ? "E2E_MOVE=a0a1" : "E2E_MOVES=" + arguments.moveCount());
        System.out.println("E2E_CONFIRMATION=" + outcome.confirmationStatus().orElseThrow());
        System.out.println("E2E_STATE=" + coordinator.state());
    }

    static ProbeArguments parseArguments(String[] args) {
        if (args == null || args.length == 0) {
            return new ProbeArguments(false, 0, null, false, 1);
        }
        if (args.length == 1) {
            if ("black".equalsIgnoreCase(args[0])) {
                return new ProbeArguments(false, 0, null, true, 1);
            }
            String normalized = args[0].toLowerCase(Locale.ROOT);
            if (normalized.startsWith("endurance=")) {
                int count;
                try {
                    count = Integer.parseInt(normalized.substring("endurance=".length()));
                } catch (NumberFormatException failure) {
                    throw new IllegalArgumentException(
                            "endurance move count must be an integer", failure);
                }
                if (count < 1 || count > 1_000) {
                    throw new IllegalArgumentException(
                            "endurance move count must be between 1 and 1000");
                }
                return new ProbeArguments(false, 0, null, false, count);
            }
            throw new IllegalArgumentException(
                    "single optional argument must be black or endurance=1..1000");
        }
        if (args.length != 5 && args.length != 6) {
            throw new IllegalArgumentException(
                    "use no arguments, black, or HWND plus four screen bounds and optional black");
        }
        boolean black = args.length == 6 && "black".equalsIgnoreCase(args[5]);
        if (args.length == 6 && !black) {
            throw new IllegalArgumentException("optional orientation must be black");
        }
        BoardCoordinateMapper.BoardBounds bounds = new BoardCoordinateMapper.BoardBounds(
                Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        return new ProbeArguments(true, parseHandle(args[0]), bounds, black, 1);
    }

    static ProbeMove enduranceMove(int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> new ProbeMove("a0a1",
                    new BoardCoordinateMapper.Move(0, 0, 0, 1),
                    new MoveConfirmationTracker.GridMove(0, 9, 0, 8));
            case 1 -> new ProbeMove("a9a8",
                    new BoardCoordinateMapper.Move(0, 9, 0, 8),
                    new MoveConfirmationTracker.GridMove(0, 0, 0, 1));
            case 2 -> new ProbeMove("a1a0",
                    new BoardCoordinateMapper.Move(0, 1, 0, 0),
                    new MoveConfirmationTracker.GridMove(0, 8, 0, 9));
            default -> new ProbeMove("a8a9",
                    new BoardCoordinateMapper.Move(0, 8, 0, 9),
                    new MoveConfirmationTracker.GridMove(0, 1, 0, 0));
        };
    }

    static void applyMove(char[][] board, MoveConfirmationTracker.GridMove move) {
        if (board == null || board.length != 10
                || move.fromFile() < 0 || move.fromFile() > 8
                || move.toFile() < 0 || move.toFile() > 8
                || move.fromRow() < 0 || move.fromRow() > 9
                || move.toRow() < 0 || move.toRow() > 9) {
            throw new IllegalArgumentException("endurance move is outside a 9x10 board");
        }
        char piece = board[move.fromRow()][move.fromFile()];
        if (piece == ' ') {
            throw new IllegalStateException("endurance move source is empty");
        }
        board[move.fromRow()][move.fromFile()] = ' ';
        board[move.toRow()][move.toFile()] = piece;
    }

    private static ProbeMove singleMove(boolean blackAtBottom) {
        return new ProbeMove("a0a1",
                new BoardCoordinateMapper.Move(0, 0, 0, 1),
                blackAtBottom
                        ? new MoveConfirmationTracker.GridMove(8, 0, 8, 1)
                        : new MoveConfirmationTracker.GridMove(0, 9, 0, 8));
    }

    private static MoveConfirmationTracker.GridMove canonicalGridMove(
            BoardCoordinateMapper.Move move) {
        return new MoveConfirmationTracker.GridMove(
                move.fromFile(), 9 - move.fromRank(),
                move.toFile(), 9 - move.toRank());
    }

    static BoardCoordinateMapper.BoardBounds detectedBoardBounds(
            RecognitionResult.AcceptedPosition position) {
        RecognitionCandidate.BoardBounds bounds = position.boardBounds();
        if (bounds == null) {
            throw new IllegalArgumentException("accepted recognition has no board bounds");
        }
        return new BoardCoordinateMapper.BoardBounds(
                bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    private static BoardCoordinateMapper.BoardBounds toClientBounds(
            BoardCoordinateMapper.BoardBounds screen,
            BoardCoordinateMapper.ClientArea client) {
        return new BoardCoordinateMapper.BoardBounds(
                Math.subtractExact(screen.x(), client.screenX()),
                Math.subtractExact(screen.y(), client.screenY()),
                screen.width(), screen.height());
    }

    private static WinDef.HWND findTestWindow() {
        WinDef.HWND handle = User32.INSTANCE.FindWindow(null, ALLOWED_TITLE);
        if (handle == null || handle.getPointer() == null
                || Pointer.nativeValue(handle.getPointer()) == 0) {
            throw new IllegalStateException(
                    "PikaDesk offline test board is not running");
        }
        return handle;
    }

    private static BufferedImage capture(Robot robot,
                                         BoardCoordinateMapper.ClientArea client) {
        return robot.createScreenCapture(new Rectangle(
                client.screenX(), client.screenY(), client.width(), client.height()));
    }

    private static char[][] rotate180(char[][] source) {
        char[][] result = new char[10][9];
        for (int row = 0; row < 10; row++) {
            for (int file = 0; file < 9; file++) {
                result[9 - row][8 - file] = source[row][file];
            }
        }
        return result;
    }

    private static char[][] copy(char[][] source) {
        char[][] result = new char[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = source[row].clone();
        }
        return result;
    }

    private static long parseHandle(String value) {
        String normalized = value.startsWith("0x") || value.startsWith("0X")
                ? value.substring(2) : value;
        return Long.parseUnsignedLong(normalized, 16);
    }

    private static String windowTitle(WinDef.HWND handle) {
        int length = User32.INSTANCE.GetWindowTextLength(handle);
        char[] text = new char[Math.max(1, length + 1)];
        User32.INSTANCE.GetWindowText(handle, text, text.length);
        return new String(text, 0, length);
    }

    record ProbeArguments(boolean explicitCalibration,
                          long handleValue,
                          BoardCoordinateMapper.BoardBounds screenBoardBounds,
                          boolean blackAtBottom,
                          int moveCount) { }

    record ProbeMove(String ucci,
                     BoardCoordinateMapper.Move canonical,
                     MoveConfirmationTracker.GridMove visual) { }

}
