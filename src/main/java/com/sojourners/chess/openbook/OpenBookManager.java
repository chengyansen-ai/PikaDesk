package com.sojourners.chess.openbook;

import com.sojourners.chess.board.ChessBoard;
import com.sojourners.chess.config.Properties;
import com.sojourners.chess.model.BookData;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class OpenBookManager {

    private volatile static OpenBookManager instance;

    private OpenBook cloudOpenBook;
    private List<OpenBook> localOpenBooks;
    private final List<OpenBookDiagnostic> loadDiagnostics = new ArrayList<>();
    private final List<OpenBookDiagnostic> queryDiagnostics = new ArrayList<>();
    Properties prop;

    private OpenBookManager() {
        this.cloudOpenBook = new CloudOpenBook();
        this.localOpenBooks = new ArrayList<>();
        prop = Properties.getInstance();

        setLocalOpenBooks();
    }

    public synchronized void close() {
        for (OpenBook ob : localOpenBooks) {
            ob.close();
        }
    }

    public synchronized void setLocalOpenBooks() {
        close();
        localOpenBooks.clear();
        loadDiagnostics.clear();
        queryDiagnostics.clear();
        for (String path : prop.getOpenBookList()) {
            Path selected = safePath(path);
            try {
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".xqb")) {
                    localOpenBooks.add(new XqbOpenBook(path));
                } else if (lower.endsWith(".obk")) {
                    localOpenBooks.add(new BhOpenBook(path));
                } else if (lower.endsWith(".pfbook")) {
                    localOpenBooks.add(new PfOpenBook(path));
                } else {
                    loadDiagnostics.add(OpenBookDiagnostic.loadFailure(selected,
                            new OpenBookLoadException("UNSUPPORTED_FORMAT",
                                    "unsupported local book extension")));
                }
            } catch (Exception e) {
                loadDiagnostics.add(OpenBookDiagnostic.loadFailure(selected, e));
            }
        }
    }

    public synchronized List<BookData> queryBook(char[][] b, boolean redGo, boolean offManual) {

        List<BookData> cloudResults = new ArrayList<>();
        if (prop.getUseCloudBook()) {
            String fenCode = ChessBoard.fenCode(b, redGo);
            cloudResults.addAll(cloudOpenBook.query(fenCode, offManual, prop.getMoveRule()));
        }

        List<BookData> localResults = new ArrayList<>();
        queryDiagnostics.clear();
        if (!offManual) {
            for (OpenBook ob : this.localOpenBooks) {
                localResults.addAll(ob.query(b, redGo, prop.getMoveRule()));
                ob.diagnostic().ifPresent(queryDiagnostics::add);
            }
        }

        if (prop.getLocalBookFirst()) {
            localResults.addAll(cloudResults);
            return localResults;
        } else {
            cloudResults.addAll(localResults);
            return cloudResults;
        }
    }

    public static OpenBookManager getInstance() {
        if (instance == null) {
            synchronized (OpenBookManager.class) {
                if (instance == null) {
                    instance = new OpenBookManager();
                }
            }
        }
        return instance;
    }

    public synchronized List<OpenBookDiagnostic> diagnostics() {
        List<OpenBookDiagnostic> diagnostics = new ArrayList<>(loadDiagnostics);
        diagnostics.addAll(queryDiagnostics);
        return List.copyOf(diagnostics);
    }

    public synchronized Optional<String> diagnosticSummary() {
        List<OpenBookDiagnostic> diagnostics = diagnostics();
        if (diagnostics.isEmpty()) return Optional.empty();
        StringBuilder message = new StringBuilder("以下开局库未能安全使用：\n");
        int shown = Math.min(diagnostics.size(), 5);
        for (int index = 0; index < shown; index++) {
            OpenBookDiagnostic diagnostic = diagnostics.get(index);
            message.append("• ").append(diagnostic.source()).append("：")
                    .append(diagnostic.code()).append('\n');
        }
        if (diagnostics.size() > shown) {
            message.append("另有 ").append(diagnostics.size() - shown).append(" 项");
        }
        return Optional.of(message.toString().stripTrailing());
    }

    private static Path safePath(String value) {
        try {
            return value == null ? null : Path.of(value);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }


}
