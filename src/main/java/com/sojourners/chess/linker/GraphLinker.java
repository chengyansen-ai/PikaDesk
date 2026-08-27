package com.sojourners.chess.linker;

import java.awt.*;
import java.awt.image.BufferedImage;

public interface GraphLinker {

    void start();

    void stop();

    void getTargetWindowId();

    Rectangle getTargetWindowPosition();

    BufferedImage screenshotByBack(Rectangle windowPos);

    BufferedImage screenshotByFront(Rectangle windowPos);

}
