package com.sojourners.chess.controller.component;

import com.sojourners.chess.game.tree.GameTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** UI-independent projection and editing boundary for the branch game tree. */
public final class GameTreeViewModel {

    private GameTree tree;
    private final Map<UUID, Integer> plyById = new HashMap<>();

    public GameTreeViewModel(String initialFen) {
        reset(initialFen);
    }

    public void reset(String initialFen) {
        tree = GameTree.create(initialFen);
        plyById.clear();
        plyById.put(tree.root().id(), 0);
    }

    public Row root() {
        return row(tree.root().id());
    }

    public Row current() {
        return row(tree.current().id());
    }

    public Row row(UUID nodeId) {
        GameTree.Node node = tree.node(nodeId);
        boolean mainline = node.parentId().map(parentId -> tree.node(parentId)
                .mainlineChildId().filter(node.id()::equals).isPresent()).orElse(true);
        int ply = ply(node);
        return new Row(
                node.id(), node.parentId().orElse(null),
                node.parentId().isEmpty() ? "初始局面" : ply + ". " + node.move(),
                node.move(), node.positionFen(), node.comment(),
                evaluationText(node.evaluation().orElse(null)),
                mainline, node.id().equals(tree.current().id()),
                !node.children().isEmpty());
    }

    public List<Row> children(UUID nodeId) {
        return tree.children(nodeId).stream().map(node -> row(node.id())).toList();
    }

    public Row recordMove(String move) {
        UUID parentId = tree.current().id();
        Integer parentPly = plyById.get(parentId);
        if (parentPly == null) parentPly = ply(tree.current());
        int childPly = parentPly + 1;
        GameTree.MergeResult result = tree.insert(parentId, move);
        plyById.putIfAbsent(result.endNodeId(), childPly);
        return row(result.endNodeId());
    }

    public Navigation followLine(String initialFen, List<String> moves) {
        Objects.requireNonNull(moves, "moves");
        List<String> requestedMoves = List.copyOf(moves);
        GameTree validated = GameTree.create(initialFen);
        for (String move : requestedMoves) {
            validated.insert(validated.current().id(), move);
        }
        if (!validated.initialFen().equals(tree.initialFen())) {
            tree = validated;
            plyById.clear();
            cachePath(tree.current().id());
            return navigation(tree.current().id());
        }
        tree.jumpTo(tree.root().id());
        for (int from = 0; from < requestedMoves.size(); from += 512) {
            List<String> chunk = requestedMoves.subList(
                    from, Math.min(from + 512, requestedMoves.size()));
            GameTree.MergeResult merged = tree.mergeLine(tree.current().id(), chunk);
            tree.jumpTo(merged.endNodeId());
        }
        cachePath(tree.current().id());
        return navigation(tree.current().id());
    }

    public Navigation jumpTo(UUID nodeId) {
        tree.jumpTo(nodeId);
        return navigation(nodeId);
    }

    public List<UUID> pathTo(UUID nodeId) {
        ArrayDeque<UUID> path = new ArrayDeque<>();
        GameTree.Node cursor = tree.node(nodeId);
        path.addFirst(cursor.id());
        while (cursor.parentId().isPresent()) {
            cursor = tree.node(cursor.parentId().orElseThrow());
            path.addFirst(cursor.id());
        }
        return List.copyOf(path);
    }

    public void updateComment(UUID nodeId, String comment) {
        tree.updateComment(nodeId, comment);
    }

    public void updateEvaluation(UUID nodeId, GameTree.Evaluation evaluation) {
        tree.updateEvaluation(nodeId, evaluation);
    }

    public void promoteToMainline(UUID nodeId) {
        tree.promoteToMainline(nodeId);
    }

    public int deleteSubtree(UUID nodeId) {
        return tree.deleteSubtree(nodeId);
    }

    public int size() {
        return tree.size();
    }

    public List<EvaluationPoint> evaluationSeries() {
        List<EvaluationPoint> result = new ArrayList<>();
        GameTree.Node node = tree.root();
        int ply = 0;
        if (node.evaluation().isPresent()) {
            GameTree.Evaluation evaluation = node.evaluation().orElseThrow();
            result.add(new EvaluationPoint(
                    node.id(), ply, numericScore(evaluation),
                    evaluationText(evaluation)));
        }
        while (node.mainlineChildId().isPresent()) {
            node = tree.node(node.mainlineChildId().orElseThrow());
            ply++;
            if (node.evaluation().isPresent()) {
                GameTree.Evaluation evaluation = node.evaluation().orElseThrow();
                result.add(new EvaluationPoint(
                        node.id(), ply, numericScore(evaluation),
                        evaluationText(evaluation)));
            }
        }
        return List.copyOf(result);
    }

    public List<CriticalMistake> criticalMistakes(int thresholdCentipawns) {
        if (thresholdCentipawns < 1) {
            throw new IllegalArgumentException("critical mistake threshold must be positive");
        }
        List<CriticalMistake> result = new ArrayList<>();
        EvaluationPoint previous = null;
        for (EvaluationPoint point : evaluationSeries()) {
            if (previous != null && point.ply() == previous.ply() + 1) {
                int swing = point.score() - previous.score();
                Row row = row(point.nodeId());
                boolean moverWasRed = row.positionFen().endsWith(" b");
                boolean moverWorsenedOwnPosition = moverWasRed
                        ? swing <= -thresholdCentipawns
                        : swing >= thresholdCentipawns;
                if (moverWorsenedOwnPosition) {
                    result.add(new CriticalMistake(
                            point.nodeId(), point.ply(), swing,
                            row.label() + "  " + (moverWasRed ? "红方" : "黑方")
                                    + "失误 " + signed(swing / 100.0)));
                }
            }
            previous = point;
        }
        return List.copyOf(result);
    }

    private Navigation navigation(UUID nodeId) {
        GameTree.Node node = tree.node(nodeId);
        ArrayDeque<String> moves = new ArrayDeque<>();
        GameTree.Node cursor = node;
        while (cursor.parentId().isPresent()) {
            moves.addFirst(cursor.move());
            cursor = tree.node(cursor.parentId().orElseThrow());
        }
        List<String> childMoves = tree.children(nodeId).stream()
                .map(GameTree.Node::move).toList();
        return new Navigation(
                node.id(), tree.initialFen(), node.positionFen(),
                List.copyOf(moves), childMoves);
    }

    private int ply(GameTree.Node node) {
        Integer known = plyById.get(node.id());
        if (known != null) return known;
        int result = 0;
        GameTree.Node cursor = node;
        while (cursor.parentId().isPresent()) {
            result++;
            cursor = tree.node(cursor.parentId().orElseThrow());
        }
        plyById.put(node.id(), result);
        return result;
    }

    private void cachePath(UUID nodeId) {
        List<UUID> path = pathTo(nodeId);
        for (int index = 0; index < path.size(); index++) {
            plyById.put(path.get(index), index);
        }
    }

    private static int numericScore(GameTree.Evaluation evaluation) {
        if (evaluation.centipawns() != null) return evaluation.centipawns();
        int mate = evaluation.mateIn();
        return mate >= 0 ? 100_000 - Math.min(mate, 10_000)
                : -100_000 + Math.min(Math.abs(mate), 10_000);
    }

    private static String evaluationText(GameTree.Evaluation evaluation) {
        if (evaluation == null) return "未评估";
        String score = evaluation.centipawns() != null
                ? signed(evaluation.centipawns() / 100.0)
                : "杀" + (evaluation.mateIn() >= 0 ? "+" : "") + evaluation.mateIn();
        return score + " / 深度 " + evaluation.depth() + " · " + evaluation.engine();
    }

    private static String signed(double score) {
        return String.format(Locale.ROOT, "%+.2f", score);
    }

    public record Row(UUID id,
                      UUID parentId,
                      String label,
                      String move,
                      String positionFen,
                      String comment,
                      String evaluationText,
                      boolean mainline,
                      boolean current,
                      boolean hasChildren) {
    }

    public record Navigation(UUID nodeId,
                             String initialFen,
                             String positionFen,
                             List<String> moves,
                             List<String> childMoves) {
        public Navigation {
            moves = List.copyOf(moves);
            childMoves = List.copyOf(childMoves);
        }
    }

    public record EvaluationPoint(UUID nodeId, int ply, int score, String label) {
    }

    public record CriticalMistake(UUID nodeId, int ply, int swing, String label) {
    }
}
