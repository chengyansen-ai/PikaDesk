package com.sojourners.chess.game.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable aggregate with immutable nodes. Every edit replaces affected node
 * values, so UI consumers never observe a node changing underneath them.
 */
public final class GameTree {

    static final int MAX_NODES = 100_000;
    private static final int MAX_LINE_MOVES = 512;
    private static final int MAX_COMMENT_CHARS = 16_384;

    private final String initialFen;
    private final UUID rootId;
    private final LinkedHashMap<UUID, Node> nodes;
    private UUID currentId;

    private GameTree(String initialFen,
                     UUID rootId,
                     UUID currentId,
                     Map<UUID, Node> nodes,
                     boolean validate) {
        this.initialFen = XiangqiPosition.normalizeFen(initialFen);
        this.rootId = Objects.requireNonNull(rootId, "rootId");
        this.currentId = Objects.requireNonNull(currentId, "currentId");
        this.nodes = new LinkedHashMap<>(Objects.requireNonNull(nodes, "nodes"));
        if (validate) validateGraph();
    }

    public static GameTree create(String initialFen) {
        String normalized = XiangqiPosition.normalizeFen(initialFen);
        UUID rootId = UUID.randomUUID();
        Node root = new Node(
                rootId, Optional.empty(), "", normalized,
                List.of(), Optional.empty(), "", Optional.empty());
        return new GameTree(normalized, rootId, rootId, Map.of(rootId, root), true);
    }

    static GameTree restore(String initialFen,
                            UUID rootId,
                            UUID currentId,
                            Map<UUID, Node> nodes) {
        return new GameTree(initialFen, rootId, currentId, nodes, true);
    }

    public String initialFen() {
        return initialFen;
    }

    public Node root() {
        return node(rootId);
    }

    public Node current() {
        return node(currentId);
    }

    public Node node(UUID id) {
        Node node = nodes.get(Objects.requireNonNull(id, "id"));
        if (node == null) throw new IllegalArgumentException("unknown game node: " + id);
        return node;
    }

    public int size() {
        return nodes.size();
    }

    public boolean contains(UUID id) {
        return nodes.containsKey(Objects.requireNonNull(id, "id"));
    }

    Map<UUID, Node> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    public List<Node> children(UUID id) {
        return node(id).children().stream().map(this::node).toList();
    }

    public MergeResult insert(UUID parentId, String move) {
        Node parent = node(parentId);
        String nextFen = XiangqiPosition.applyMove(parent.positionFen(), move);
        Node existing = childWithMove(nodes, parent, move);
        if (existing != null) {
            if (!existing.positionFen().equals(nextFen)) {
                throw new IllegalStateException("existing branch has an inconsistent position");
            }
            currentId = existing.id();
            return new MergeResult(existing.id(), 0, 1);
        }
        if (nodes.size() >= MAX_NODES) {
            throw new IllegalStateException("game tree node limit exceeded");
        }
        UUID id = uniqueId(nodes);
        Node child = new Node(
                id, Optional.of(parent.id()), move, nextFen,
                List.of(), Optional.empty(), "", Optional.empty());
        List<UUID> children = new ArrayList<>(parent.children());
        children.add(id);
        Optional<UUID> mainline = parent.mainlineChildId().isPresent()
                ? parent.mainlineChildId() : Optional.of(id);
        nodes.put(parent.id(), withChildren(parent, children, mainline));
        nodes.put(id, child);
        currentId = id;
        return new MergeResult(id, 1, 0);
    }

    /**
     * Atomically merges a line by reusing identical moves under the same
     * parents. No prefix is committed when a later move is invalid.
     */
    public MergeResult mergeLine(UUID parentId, List<String> moves) {
        Objects.requireNonNull(moves, "moves");
        List<String> requested = List.copyOf(moves);
        if (requested.isEmpty() || requested.size() > MAX_LINE_MOVES) {
            throw new IllegalArgumentException("a merged line must contain 1 to 512 moves");
        }

        LinkedHashMap<UUID, Node> staged = new LinkedHashMap<>(nodes);
        Node parent = requireNode(staged, parentId);
        int created = 0;
        int reused = 0;
        for (String move : requested) {
            String nextFen = XiangqiPosition.applyMove(parent.positionFen(), move);
            Node existing = childWithMove(staged, parent, move);
            if (existing != null) {
                if (!existing.positionFen().equals(nextFen)) {
                    throw new IllegalStateException("existing branch has an inconsistent position");
                }
                parent = existing;
                reused++;
                continue;
            }
            if (staged.size() >= MAX_NODES) {
                throw new IllegalStateException("game tree node limit exceeded");
            }
            UUID id = uniqueId(staged);
            Node child = new Node(
                    id, Optional.of(parent.id()), move, nextFen,
                    List.of(), Optional.empty(), "", Optional.empty());
            List<UUID> children = new ArrayList<>(parent.children());
            children.add(id);
            Optional<UUID> mainline = parent.mainlineChildId().isPresent()
                    ? parent.mainlineChildId() : Optional.of(id);
            parent = withChildren(parent, children, mainline);
            staged.put(parent.id(), parent);
            staged.put(id, child);
            parent = child;
            created++;
        }

        nodes.clear();
        nodes.putAll(staged);
        currentId = parent.id();
        return new MergeResult(parent.id(), created, reused);
    }

    public int deleteSubtree(UUID nodeId) {
        Node target = node(nodeId);
        if (target.id().equals(rootId)) {
            throw new IllegalArgumentException("the root node cannot be deleted");
        }
        UUID parentId = target.parentId().orElseThrow();
        Set<UUID> removed = descendants(nodeId);
        Node parent = node(parentId);
        List<UUID> remaining = parent.children().stream()
                .filter(id -> !removed.contains(id)).toList();
        Optional<UUID> mainline = parent.mainlineChildId()
                .filter(id -> !removed.contains(id));
        if (mainline.isEmpty() && !remaining.isEmpty()) mainline = Optional.of(remaining.getFirst());
        nodes.put(parentId, withChildren(parent, remaining, mainline));
        removed.forEach(nodes::remove);
        if (removed.contains(currentId)) currentId = parentId;
        return removed.size();
    }

    public void promoteToMainline(UUID nodeId) {
        Node target = node(nodeId);
        UUID parentId = target.parentId().orElseThrow(() ->
                new IllegalArgumentException("the root node has no parent line"));
        Node parent = node(parentId);
        List<UUID> reordered = new ArrayList<>(parent.children());
        if (!reordered.remove(nodeId)) {
            throw new IllegalStateException("parent does not contain the requested child");
        }
        reordered.addFirst(nodeId);
        nodes.put(parentId, withChildren(parent, reordered, Optional.of(nodeId)));
    }

    public Node jumpTo(UUID nodeId) {
        Node target = node(nodeId);
        currentId = target.id();
        return target;
    }

    public void updateComment(UUID nodeId, String comment) {
        Node node = node(nodeId);
        String safe = Objects.requireNonNull(comment, "comment");
        if (safe.length() > MAX_COMMENT_CHARS) {
            throw new IllegalArgumentException("comment exceeds 16384 characters");
        }
        nodes.put(nodeId, new Node(
                node.id(), node.parentId(), node.move(), node.positionFen(),
                node.children(), node.mainlineChildId(), safe, node.evaluation()));
    }

    public void updateEvaluation(UUID nodeId, Evaluation evaluation) {
        Node node = node(nodeId);
        nodes.put(nodeId, new Node(
                node.id(), node.parentId(), node.move(), node.positionFen(),
                node.children(), node.mainlineChildId(), node.comment(),
                Optional.of(Objects.requireNonNull(evaluation, "evaluation"))));
    }

    public void clearEvaluation(UUID nodeId) {
        Node node = node(nodeId);
        nodes.put(nodeId, new Node(
                node.id(), node.parentId(), node.move(), node.positionFen(),
                node.children(), node.mainlineChildId(), node.comment(), Optional.empty()));
    }

    private void validateGraph() {
        if (nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("game tree must contain 1 to 100000 nodes");
        }
        Node root = requireNode(nodes, rootId);
        requireNode(nodes, currentId);
        if (root.parentId().isPresent() || !root.move().isEmpty()
                || !root.positionFen().equals(initialFen)) {
            throw new IllegalArgumentException("invalid game tree root");
        }

        Map<UUID, Integer> incoming = new HashMap<>();
        for (Map.Entry<UUID, Node> entry : nodes.entrySet()) {
            Node parent = entry.getValue();
            if (!entry.getKey().equals(parent.id())) {
                throw new IllegalArgumentException("node map key does not match node identity");
            }
            if (parent.children().isEmpty() != parent.mainlineChildId().isEmpty()) {
                throw new IllegalArgumentException("mainline must identify one child when children exist");
            }
            Set<String> moves = new HashSet<>();
            for (UUID childId : parent.children()) {
                Node child = requireNode(nodes, childId);
                if (!child.parentId().equals(Optional.of(parent.id()))) {
                    throw new IllegalArgumentException("child points to a different parent");
                }
                if (!moves.add(child.move())) {
                    throw new IllegalArgumentException("siblings cannot contain duplicate moves");
                }
                incoming.merge(childId, 1, Integer::sum);
                String expected = XiangqiPosition.applyMove(parent.positionFen(), child.move());
                if (!expected.equals(child.positionFen())) {
                    throw new IllegalArgumentException("child position does not match its move");
                }
            }
        }
        for (Node node : nodes.values()) {
            if (node.id().equals(rootId)) continue;
            if (incoming.getOrDefault(node.id(), 0) != 1 || node.parentId().isEmpty()) {
                throw new IllegalArgumentException("every non-root node must have one parent");
            }
        }

        Set<UUID> visited = new LinkedHashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        pending.push(rootId);
        while (!pending.isEmpty()) {
            UUID id = pending.pop();
            if (!visited.add(id)) throw new IllegalArgumentException("game tree contains a cycle");
            node(id).children().forEach(pending::push);
        }
        if (visited.size() != nodes.size()) {
            throw new IllegalArgumentException("game tree contains unreachable nodes");
        }
    }

    private Set<UUID> descendants(UUID nodeId) {
        Set<UUID> result = new LinkedHashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        pending.push(nodeId);
        while (!pending.isEmpty()) {
            UUID id = pending.pop();
            if (!result.add(id)) throw new IllegalStateException("game tree contains a cycle");
            node(id).children().forEach(pending::push);
        }
        return result;
    }

    private Node childWithMove(Map<UUID, Node> source, Node parent, String move) {
        for (UUID childId : parent.children()) {
            Node child = requireNode(source, childId);
            if (child.move().equals(move)) return child;
        }
        return null;
    }

    private static Node requireNode(Map<UUID, Node> source, UUID id) {
        Node node = source.get(Objects.requireNonNull(id, "id"));
        if (node == null) throw new IllegalArgumentException("unknown game node: " + id);
        return node;
    }

    private static UUID uniqueId(Map<UUID, Node> source) {
        UUID id;
        do id = UUID.randomUUID(); while (source.containsKey(id));
        return id;
    }

    private static Node withChildren(Node node,
                                     List<UUID> children,
                                     Optional<UUID> mainline) {
        return new Node(
                node.id(), node.parentId(), node.move(), node.positionFen(),
                children, mainline, node.comment(), node.evaluation());
    }

    public record Node(UUID id,
                       Optional<UUID> parentId,
                       String move,
                       String positionFen,
                       List<UUID> children,
                       Optional<UUID> mainlineChildId,
                       String comment,
                       Optional<Evaluation> evaluation) {
        public Node {
            Objects.requireNonNull(id, "id");
            parentId = Objects.requireNonNull(parentId, "parentId");
            move = Objects.requireNonNull(move, "move");
            if (!move.isEmpty() && !move.matches("[a-i][0-9][a-i][0-9]")) {
                throw new IllegalArgumentException("invalid node move");
            }
            String normalized = XiangqiPosition.normalizeFen(positionFen);
            if (!normalized.equals(positionFen)) {
                throw new IllegalArgumentException("node position FEN is not normalized");
            }
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            if (children.size() != new HashSet<>(children).size()
                    || children.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("node children must be unique identities");
            }
            mainlineChildId = Objects.requireNonNull(mainlineChildId, "mainlineChildId");
            if (mainlineChildId.isPresent() && !children.contains(mainlineChildId.orElseThrow())) {
                throw new IllegalArgumentException("mainline must be one of the node children");
            }
            comment = Objects.requireNonNull(comment, "comment");
            if (comment.length() > MAX_COMMENT_CHARS) {
                throw new IllegalArgumentException("comment exceeds 16384 characters");
            }
            evaluation = Objects.requireNonNull(evaluation, "evaluation");
        }
    }

    public record Evaluation(Integer centipawns,
                             Integer mateIn,
                             int depth,
                             String engine) {
        public Evaluation {
            if ((centipawns == null) == (mateIn == null)) {
                throw new IllegalArgumentException("evaluation needs exactly one score kind");
            }
            if (centipawns != null && Math.abs((long) centipawns) > 1_000_000L) {
                throw new IllegalArgumentException("centipawn score exceeds limit");
            }
            if (mateIn != null && Math.abs((long) mateIn) > 10_000L) {
                throw new IllegalArgumentException("mate distance exceeds limit");
            }
            if (depth < 1 || depth > 256) {
                throw new IllegalArgumentException("evaluation depth must be between 1 and 256");
            }
            engine = Objects.requireNonNull(engine, "engine").trim();
            if (engine.isEmpty() || engine.length() > 128) {
                throw new IllegalArgumentException("invalid evaluation engine name");
            }
        }
    }

    public record MergeResult(UUID endNodeId, int createdNodes, int reusedNodes) {
        public MergeResult {
            Objects.requireNonNull(endNodeId, "endNodeId");
            if (createdNodes < 0 || reusedNodes < 0 || createdNodes + reusedNodes < 1) {
                throw new IllegalArgumentException("invalid merge counts");
            }
        }
    }
}
