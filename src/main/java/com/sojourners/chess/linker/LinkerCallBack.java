package com.sojourners.chess.linker;

import com.sojourners.chess.linker.profile.ConnectionProfile;
import com.sojourners.chess.linker.profile.ConnectionWizardState;

import java.util.List;

public interface LinkerCallBack {

    void linkerInitChessBoard(String fenCode, boolean isReverse);

    char[][] getEngineBoard();

    boolean isThinking();

    boolean isWatchMode();

    default LinkMode connectionMode() {
        return isWatchMode()
                ? LinkMode.READ_ONLY_ADVISOR
                : LinkMode.AUTHORIZED_AUTOMATION;
    }

    void linkerMove(int x1, int y1, int x2, int y2);

    /** Lets the user choose one generic visible target for this session. */
    default TargetWindowChoice chooseTargetWindow(List<TargetWindowChoice> choices) {
        return null;
    }

    /**
     * Requests explicit approval for one selected local window. Returning
     * {@code null} means cancel; callers must remain unarmed.
     */
    default ConnectionProfile configureConnection(ConnectionWizardState wizard) {
        return null;
    }

    default void connectionConfigurationFailed(String message) { }

    default void connectionStatus(ConnectionStatus status) { }
}
