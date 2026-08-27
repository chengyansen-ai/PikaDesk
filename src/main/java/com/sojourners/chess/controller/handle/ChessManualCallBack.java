package com.sojourners.chess.controller.handle;

import java.util.List;

public interface ChessManualCallBack {
    void browseChessRecord(String fenCode, List<String> moveList, boolean redGo, List<String> nextList);
    void setNextList(List<String> nextList);
    void newChessBoardFromManual(String fenCode);
    void turnOnAnalysisMode();
    void turnOffAnalysisMode();
    void refreshLineChart();
}
