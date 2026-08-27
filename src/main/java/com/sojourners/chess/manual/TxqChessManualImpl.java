package com.sojourners.chess.manual;

import com.sojourners.chess.manual.adapter.ManualDocument;
import com.sojourners.chess.manual.adapter.TxqAdapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Bridges the legacy UI model to the safe, versioned TXQ adapter. */
public class TxqChessManualImpl implements ChessManualService {

    private final TxqAdapter adapter = new TxqAdapter();

    @Override
    public ChessManual openChessManual(File file) {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            ManualDocument document = adapter.read(input).document();
            ChessManual manual = adapter.toLegacyModel(document);
            translate(manual.getFenCode(), manual.getHead());
            return manual;
        } catch (IOException | RuntimeException failure) {
            System.err.println("TXQ open failed: " + message(failure));
            return null;
        }
    }

    @Override
    public void saveChessManual(ChessManual manual, File file) {
        Path target = file.toPath().toAbsolutePath().normalize();
        Path temporary = null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            adapter.write(adapter.fromLegacyModel(manual), bytes);
            Path parent = target.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new IOException("TXQ destination directory does not exist");
            }
            temporary = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
            Files.write(temporary, bytes.toByteArray());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException failure) {
            System.err.println("TXQ save failed: " + message(failure));
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A failed temporary-file cleanup must not hide the original save failure.
                }
            }
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message.trim();
    }
}
