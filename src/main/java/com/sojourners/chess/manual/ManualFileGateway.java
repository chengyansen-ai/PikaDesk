package com.sojourners.chess.manual;

import com.sojourners.chess.manual.adapter.Cbr2Adapter;
import com.sojourners.chess.manual.adapter.ManualDocument;
import com.sojourners.chess.manual.adapter.PgnAdapter;
import com.sojourners.chess.manual.adapter.TxqAdapter;
import com.sojourners.chess.manual.adapter.Xqf10Adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Routes the desktop UI through the bounded, format-specific adapters. */
public final class ManualFileGateway {

    private final PgnAdapter pgn = new PgnAdapter();
    private final Xqf10Adapter xqf = new Xqf10Adapter();
    private final Cbr2Adapter cbr = new Cbr2Adapter();
    private final TxqAdapter txq = new TxqAdapter();

    public OpenResult open(Path file) throws IOException {
        Path source = normalized(file);
        Format format = formatOf(source);
        AdapterRead read;
        try (InputStream input = Files.newInputStream(source)) {
            read = read(format, input);
        }

        ChessManual manual = txq.toLegacyModel(read.document());
        if ((manual.getName() == null || manual.getName().isBlank())
                && read.document().metadata().containsKey("Event")) {
            manual.setName(read.document().metadata().get("Event"));
        }
        ChessManualService.translateMoves(manual.getFenCode(), manual.getHead());
        return new OpenResult(manual, read.document().result(), format,
                read.document().metadata(), read.notices());
    }

    public SaveResult save(ChessManual manual,
                           ManualDocument.Result result,
                           Path file) throws IOException {
        return save(manual, result, Map.of(), file);
    }

    public SaveResult save(ChessManual manual,
                           ManualDocument.Result result,
                           Map<String, String> retainedMetadata,
                           Path file) throws IOException {
        Objects.requireNonNull(manual, "manual");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(retainedMetadata, "retainedMetadata");
        Path target = normalized(file);
        Format format = formatOf(target);
        ManualDocument base = txq.fromLegacyModel(manual);
        Map<String, String> metadata = new LinkedHashMap<>(retainedMetadata);
        metadata.keySet().removeAll(List.of("Title", "Date", "Site", "Red", "Black"));
        metadata.putAll(base.metadata());
        if (format == Format.PGN) {
            metadata.remove("Event");
            String title = metadata.remove("Title");
            if (title != null && !title.isEmpty()) metadata.put("Event", title);
        }
        ManualDocument document = new ManualDocument(base.tree(), result, metadata);

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        List<Notice> notices = write(format, document, encoded);
        replaceAtomically(target, encoded.toByteArray());
        return new SaveResult(format, notices);
    }

    private AdapterRead read(Format format, InputStream input) throws IOException {
        return switch (format) {
            case PGN -> {
                PgnAdapter.ReadResult result = pgn.read(input);
                yield new AdapterRead(result.document(), result.notices().stream()
                        .map(notice -> new Notice(notice.code(), notice.message(), notice.offset()))
                        .toList());
            }
            case XQF -> {
                Xqf10Adapter.ReadResult result = xqf.read(input);
                yield new AdapterRead(result.document(), result.notices().stream()
                        .map(notice -> new Notice(notice.code(), notice.message(), notice.offset()))
                        .toList());
            }
            case CBR -> {
                Cbr2Adapter.ReadResult result = cbr.read(input);
                yield new AdapterRead(result.document(), result.notices().stream()
                        .map(notice -> new Notice(notice.code(), notice.message(), notice.offset()))
                        .toList());
            }
            case TXQ -> {
                TxqAdapter.ReadResult result = txq.read(input);
                yield new AdapterRead(result.document(), result.notices().stream()
                        .map(notice -> new Notice(notice.code(), notice.message(), notice.offset()))
                        .toList());
            }
        };
    }

    private List<Notice> write(Format format,
                               ManualDocument document,
                               ByteArrayOutputStream output) throws IOException {
        List<Notice> notices = new ArrayList<>();
        switch (format) {
            case PGN -> pgn.write(document, output).notices().forEach(notice -> notices.add(
                    new Notice(notice.code(), notice.message(), notice.offset())));
            case XQF -> xqf.write(document, output).notices().forEach(notice -> notices.add(
                    new Notice(notice.code(), notice.message(), notice.offset())));
            case CBR -> cbr.write(document, output).notices().forEach(notice -> notices.add(
                    new Notice(notice.code(), notice.message(), notice.offset())));
            case TXQ -> txq.write(document, output).notices().forEach(notice -> notices.add(
                    new Notice(notice.code(), notice.message(), notice.offset())));
        }
        return List.copyOf(notices);
    }

    private static void replaceAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ManualFileException("INVALID_DESTINATION",
                    "chess manual destination directory does not exist");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".pikadesk-manual-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private static Path normalized(Path file) {
        return Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    private static Format formatOf(Path file) throws ManualFileException {
        Path namePath = file.getFileName();
        String name = namePath == null ? "" : namePath.toString();
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        for (Format format : Format.values()) {
            if (format.extension.equals(extension)) return format;
        }
        throw new ManualFileException("UNSUPPORTED_EXTENSION",
                "supported chess manual extensions are txq, pgn, xqf and cbr");
    }

    public enum Format {
        TXQ("txq", "PikaDesk 安全 TXQ"),
        PGN("pgn", "ICCS PGN"),
        XQF("xqf", "XQF 1.0"),
        CBR("cbr", "CBR v2");

        private final String extension;
        private final String displayName;

        Format(String extension, String displayName) {
            this.extension = extension;
            this.displayName = displayName;
        }

        public String extension() {
            return extension;
        }

        public String displayName() {
            return displayName;
        }
    }

    public record Notice(String code, String message, int offset) {
        public Notice {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            if (code.isBlank() || message.isBlank() || offset < 0) {
                throw new IllegalArgumentException("invalid manual file notice");
            }
        }
    }

    public record OpenResult(ChessManual manual,
                             ManualDocument.Result result,
                             Format format,
                             Map<String, String> metadata,
                             List<Notice> notices) {
        public OpenResult {
            Objects.requireNonNull(manual, "manual");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(format, "format");
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        }
    }

    public record SaveResult(Format format, List<Notice> notices) {
        public SaveResult {
            Objects.requireNonNull(format, "format");
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        }
    }

    public static final class ManualFileException extends IOException {
        private final String code;

        private ManualFileException(String code, String message) {
            super(code + ": " + message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    private record AdapterRead(ManualDocument document, List<Notice> notices) { }
}
