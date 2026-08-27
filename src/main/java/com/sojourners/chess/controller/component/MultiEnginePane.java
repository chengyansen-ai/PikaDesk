package com.sojourners.chess.controller.component;

import com.sojourners.chess.analysis.MultiEngineAnalysisWorkspace;
import com.sojourners.chess.enginee.EngineRegistry;
import com.sojourners.chess.model.EngineConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class MultiEnginePane extends BorderPane implements AutoCloseable {

    private final Supplier<MultiEngineAnalysisWorkspace.AnalysisRequest> requestSupplier;
    private final Runnable beforeStart;
    private final Runnable afterStop;
    private final int initialThreads;
    private final int initialHashMiB;
    private final Spinner<Integer> threadBudget = new Spinner<>();
    private final Spinner<Integer> hashBudget = new Spinner<>();
    private final Button startButton = textButton("启动本地引擎", "启动已配置的本地多引擎");
    private final Button analyzeButton = textButton("分析当前局面", "让已启动引擎分析当前棋盘");
    private final Label status = new Label();
    private final Label consensus = new Label("共识：尚未开始分析");
    private final HBox cardRow = new HBox(8);
    private final Map<String, EngineAnalysisCard> cards = new LinkedHashMap<>();
    private final AtomicLong lifecycle = new AtomicLong();
    private final AtomicReference<UiUpdate> pendingUi =
            new AtomicReference<>();
    private final AtomicBoolean uiScheduled = new AtomicBoolean();
    private volatile MultiEngineSession session;
    private final AtomicReference<MultiEngineSession> pendingSession =
            new AtomicReference<>();
    private final AtomicReference<MultiEngineSession> closingFirst =
            new AtomicReference<>();
    private final AtomicReference<MultiEngineSession> closingSecond =
            new AtomicReference<>();
    private volatile boolean starting;
    private volatile boolean stopping;
    private volatile boolean disposed;
    private List<EngineConfig> configuredEngines = List.of();
    private MultiEngineAnalysisWorkspace.WorkspaceSnapshot lastSnapshot;

    public MultiEnginePane(List<EngineConfig> configuredEngines,
                           int initialThreads,
                           int initialHashMiB,
                           Supplier<MultiEngineAnalysisWorkspace.AnalysisRequest> requestSupplier,
                           Runnable beforeStart,
                           Runnable afterStop) {
        this.requestSupplier = requestSupplier;
        this.beforeStart = beforeStart;
        this.afterStop = afterStop;
        this.initialThreads = initialThreads;
        this.initialHashMiB = initialHashMiB;
        setAccessibleRole(AccessibleRole.PARENT);
        setAccessibleText("多引擎分析工作区");

        FlowPane controls = new FlowPane(8, 8,
                startButton, analyzeButton,
                new Label("总线程"), threadBudget,
                new Label("总 Hash(MB)"), hashBudget);
        controls.setPadding(new Insets(8));
        status.setWrapText(true);
        BorderPane header = new BorderPane();
        header.setCenter(controls);
        header.setBottom(status);
        BorderPane.setMargin(status, new Insets(0, 8, 8, 8));
        setTop(header);

        ScrollPane scroll = new ScrollPane(cardRow);
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        scroll.setAccessibleText("多引擎分析结果，可横向滚动");
        cardRow.setPadding(new Insets(8));
        setCenter(scroll);
        consensus.setWrapText(true);
        BorderPane.setMargin(consensus, new Insets(8));
        setBottom(consensus);

        analyzeButton.setDisable(true);
        startButton.setOnAction(event -> {
            if (stopping) return;
            if (starting || session != null || pendingSession.get() != null) stopSession();
            else beginStart();
        });
        analyzeButton.setOnAction(event -> analyzeCurrentPosition());
        reload(configuredEngines);
    }

    public void reload(List<EngineConfig> configuredEngines) {
        if (starting || session != null || pendingSession.get() != null) stopSession();
        this.configuredEngines = List.copyOf(configuredEngines == null
                ? List.of() : configuredEngines);
        configureBudgets();
        showEmptyState();
        if (stopping) {
            setStoppingControls();
            status.setText("正在停止多引擎并清理本地进程…");
        }
    }

    public void analyzeIfRunning() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::analyzeIfRunning);
            return;
        }
        if (session != null) analyzeCurrentPosition();
    }

    @Override
    public void close() {
        disposed = true;
        lifecycle.incrementAndGet();
        pendingUi.set(null);
        MultiEngineSession active = session;
        MultiEngineSession pending = pendingSession.getAndSet(null);
        session = null;
        if (active != null) active.close();
        if (pending != null && pending != active) pending.close();
        MultiEngineSession first = closingFirst.getAndSet(null);
        MultiEngineSession second = closingSecond.getAndSet(null);
        if (first != null && first != active && first != pending) first.close();
        if (second != null && second != active && second != pending && second != first) {
            second.close();
        }
    }

    private void beginStart() {
        if (configuredEngines.isEmpty()) {
            status.setText("未配置引擎。请先打开“引擎管理”添加本地 UCI/UCCI 引擎。");
            return;
        }
        beforeStart.run();
        long token = lifecycle.incrementAndGet();
        List<EngineConfig> selected = configuredEngines.stream()
                .limit(EngineRegistry.MAX_ENGINES).toList();
        int selectedThreads = threadBudget.getValue();
        int selectedHashMiB = hashBudget.getValue();
        starting = true;
        setStartingControls(true);
        status.setText("正在校验本地文件、哈希和资源预算…");

        Thread.startVirtualThread(() -> {
            MultiEngineSession created = null;
            try {
                created = new MultiEngineSession(
                        selected, selectedThreads, selectedHashMiB,
                        snapshot -> publish(token, snapshot));
                pendingSession.set(created);
                if (disposed || lifecycle.get() != token) {
                    pendingSession.compareAndSet(created, null);
                    created.close();
                    return;
                }
                List<EngineRegistry.EngineSnapshot> started = created.start();
                MultiEngineSession ready = created;
                Platform.runLater(() -> finishStart(token, ready, started));
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (created != null) created.close();
                pendingSession.compareAndSet(created, null);
                String detail = rootMessage(failure);
                Platform.runLater(() -> showStartFailure(token, detail));
            }
        });
    }

    private void finishStart(long token,
                             MultiEngineSession created,
                             List<EngineRegistry.EngineSnapshot> started) {
        if (disposed || lifecycle.get() != token) {
            closeAsync(created);
            return;
        }
        pendingSession.compareAndSet(created, null);
        starting = false;
        stopping = false;
        long running = started.stream().filter(snapshot ->
                snapshot.status() == EngineRegistry.Status.RUNNING).count();
        if (running == 0) {
            stopping = true;
            setStoppingControls();
            status.setText("所有本地引擎启动失败；正在清理本地进程…");
            closeAsyncAndRestore(
                    created,
                    null,
                    token,
                    "所有本地引擎启动失败。请检查路径、协议和引擎文件。");
            return;
        }
        session = created;
        startButton.setText("停止多引擎");
        startButton.setDisable(false);
        analyzeButton.setDisable(false);
        threadBudget.setDisable(true);
        hashBudget.setDisable(true);
        status.setText("已启动 " + running + "/" + started.size()
                + " 个本地引擎。切换局面时会自动重新分析。" );
        render(created.workspace().snapshot());
        analyzeCurrentPosition();
    }

    private void stopSession() {
        if (stopping) return;
        long token = lifecycle.incrementAndGet();
        MultiEngineSession active = session;
        MultiEngineSession pending = pendingSession.getAndSet(null);
        session = null;
        starting = false;
        stopping = true;
        showEmptyState();
        setStoppingControls();
        status.setText("正在停止多引擎并清理本地进程…");
        closeAsyncAndRestore(
                active,
                pending,
                token,
                "多引擎已停止；没有后台引擎进程继续运行。");
    }

    private void analyzeCurrentPosition() {
        MultiEngineSession active = session;
        if (active == null) return;
        long token = lifecycle.get();
        try {
            MultiEngineAnalysisWorkspace.AnalysisRequest request = requestSupplier.get();
            active.workspace().analyze(request).whenComplete((generation, failure) -> {
                if (failure != null) {
                    Platform.runLater(() -> {
                        if (!disposed && lifecycle.get() == token && session == active) {
                            status.setText("无法开始分析：" + rootMessage(failure));
                        }
                    });
                }
            });
        } catch (RuntimeException failure) {
            status.setText("当前局面不能分析：" + rootMessage(failure));
        }
    }

    private void render(MultiEngineAnalysisWorkspace.WorkspaceSnapshot snapshot) {
        lastSnapshot = snapshot;
        cards.keySet().removeIf(id -> snapshot.engines().stream()
                .noneMatch(engine -> engine.id().equals(id)));
        List<Node> ordered = new ArrayList<>();
        for (int index = 0; index < snapshot.engines().size(); index++) {
            MultiEngineAnalysisWorkspace.EngineView view = snapshot.engines().get(index);
            EngineAnalysisCard card = cards.computeIfAbsent(view.id(), id -> new EngineAnalysisCard(
                    view,
                    enabled -> changeEnabled(id, enabled),
                    visible -> changeVisible(id, visible),
                    () -> move(id, -1),
                    () -> move(id, 1)));
            card.update(view, index == 0, index == snapshot.engines().size() - 1);
            ordered.add(card);
        }
        cardRow.getChildren().setAll(ordered);
        MultiEngineAnalysisWorkspace.Consensus result = snapshot.consensus();
        consensus.setText(result.move()
                .map(move -> "共识：" + move + "（" + result.agreeing() + "/"
                        + result.compared() + "）"
                        + (result.divergentEngineIds().isEmpty() ? "" : "；分歧："
                        + String.join("、", result.divergentEngineIds())))
                .orElse("共识：等待至少一个可见引擎返回 PV1"));
    }

    private void changeEnabled(String id, boolean enabled) {
        MultiEngineSession active = session;
        if (active == null) return;
        long token = lifecycle.get();
        active.workspace().setEnabled(id, enabled).whenComplete((unused, failure) -> {
            if (failure != null) Platform.runLater(() -> {
                if (!disposed && lifecycle.get() == token && session == active) {
                    status.setText("切换引擎失败：" + rootMessage(failure));
                }
            });
        });
    }

    private void changeVisible(String id, boolean visible) {
        MultiEngineSession active = session;
        if (active != null) active.workspace().setVisible(id, visible);
    }

    private void move(String id, int offset) {
        MultiEngineSession active = session;
        if (active == null || lastSnapshot == null) return;
        List<String> ids = new ArrayList<>(lastSnapshot.engines().stream()
                .map(MultiEngineAnalysisWorkspace.EngineView::id).toList());
        int from = ids.indexOf(id);
        int to = from + offset;
        if (from >= 0 && to >= 0 && to < ids.size()) {
            java.util.Collections.swap(ids, from, to);
            active.workspace().reorder(ids);
        }
    }

    private void publish(long token,
                         MultiEngineAnalysisWorkspace.WorkspaceSnapshot snapshot) {
        if (disposed || lifecycle.get() != token) return;
        pendingUi.set(new UiUpdate(token, snapshot));
        if (uiScheduled.compareAndSet(false, true)) Platform.runLater(this::drainUi);
    }

    private void drainUi() {
        UiUpdate update = pendingUi.getAndSet(null);
        if (!disposed && update != null && lifecycle.get() == update.token()) {
            render(update.snapshot());
        }
        uiScheduled.set(false);
        if (pendingUi.get() != null && uiScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::drainUi);
        }
    }

    private void configureBudgets() {
        int count = Math.max(1, Math.min(EngineRegistry.MAX_ENGINES, configuredEngines.size()));
        int maxThreads = Math.max(count,
                Math.min(1_024, Runtime.getRuntime().availableProcessors() * 4));
        int threads = Math.max(count, Math.min(maxThreads, initialThreads));
        int hash = Math.max(count, Math.min(1_048_576, initialHashMiB));
        threadBudget.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                count, maxThreads, threads));
        hashBudget.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                count, 1_048_576, hash));
        threadBudget.setEditable(true);
        hashBudget.setEditable(true);
        threadBudget.setAccessibleText("多引擎总线程预算");
        hashBudget.setAccessibleText("多引擎总 Hash 内存预算，单位 MB");
    }

    private void showEmptyState() {
        cards.clear();
        Label empty = new Label(configuredEngines.isEmpty()
                ? "尚未配置本地引擎。请先使用“引擎管理”添加 UCI/UCCI 引擎。"
                : "已配置 " + configuredEngines.size() + " 个引擎；点击“启动本地引擎”后显示分析栏。"
                + (configuredEngines.size() > EngineRegistry.MAX_ENGINES ? " 首次使用前五个。" : ""));
        empty.setWrapText(true);
        empty.setAccessibleRole(AccessibleRole.TEXT);
        cardRow.getChildren().setAll(empty);
        consensus.setText("共识：尚未开始分析");
        startButton.setDisable(configuredEngines.isEmpty());
    }

    private void setStartingControls(boolean starting) {
        startButton.setText(starting ? "取消启动" : "启动本地引擎");
        startButton.setDisable(false);
        analyzeButton.setDisable(true);
        threadBudget.setDisable(starting);
        hashBudget.setDisable(starting);
    }

    private void resetControls() {
        setStartingControls(false);
        threadBudget.setDisable(false);
        hashBudget.setDisable(false);
    }

    private void setStoppingControls() {
        startButton.setText("正在停止…");
        startButton.setDisable(true);
        analyzeButton.setDisable(true);
        threadBudget.setDisable(true);
        hashBudget.setDisable(true);
    }

    private void showStartFailure(long token, String detail) {
        if (disposed || lifecycle.get() != token) return;
        starting = false;
        stopping = false;
        resetControls();
        showEmptyState();
        status.setText("多引擎启动失败：" + detail);
        afterStop.run();
    }

    private void closeAsync(MultiEngineSession target) {
        Thread.startVirtualThread(target::close);
    }

    private void closeAsyncAndRestore(MultiEngineSession first,
                                      MultiEngineSession second,
                                      long token,
                                      String finalStatus) {
        closingFirst.set(first);
        closingSecond.set(second);
        Thread.startVirtualThread(() -> {
            try {
                if (first != null) first.close();
                if (second != null && second != first) second.close();
                if (!disposed && lifecycle.get() == token) Platform.runLater(() -> {
                    if (!disposed && lifecycle.get() == token) {
                        stopping = false;
                        resetControls();
                        showEmptyState();
                        status.setText(finalStatus);
                        afterStop.run();
                    }
                });
            } finally {
                closingFirst.compareAndSet(first, null);
                closingSecond.compareAndSet(second, null);
            }
        });
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message.trim();
    }

    private static Button textButton(String text, String accessibleText) {
        Button button = new Button(text);
        button.setAccessibleText(accessibleText);
        button.setTooltip(new Tooltip(accessibleText));
        button.setMinWidth(Button.USE_PREF_SIZE);
        return button;
    }

    private record UiUpdate(
            long token,
            MultiEngineAnalysisWorkspace.WorkspaceSnapshot snapshot) {
    }
}
