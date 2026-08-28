package com.sojourners.chess.enginee;

import com.sojourners.chess.book.CcpdOpeningCorpusAuditor;
import com.sojourners.chess.game.tree.GameTree;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class PikafishPairedMatchAcceptanceTest {

    private static final Pattern SCORE = Pattern.compile("\\bscore (cp|mate) (-?\\d+)\\b");
    private static final int OPENING_PLIES = 12;
    private static final int MAX_PLAYED_PLIES = 160;
    private static final int ADJUDICATION_CP = 800;
    private static final int ADJUDICATION_PLIES = 8;

    @Test
    void runsAnExplicitLocalPairedMatch() throws Exception {
        String stableExecutable = System.getProperty("stableEngine", "");
        String stableNetwork = System.getProperty("stableNet", "");
        String candidateExecutable = System.getProperty("candidateEngine", "");
        String candidateNetwork = System.getProperty("candidateNet", "");
        String corpusDirectory = System.getProperty("ccpdOpeningDir", "");
        assumeTrue(!stableExecutable.isBlank() && !stableNetwork.isBlank()
                        && !candidateExecutable.isBlank() && !candidateNetwork.isBlank()
                        && !corpusDirectory.isBlank(),
                "set both engine/network pairs and ccpdOpeningDir to run the match");

        int pairs = Integer.getInteger("matchPairs", 6);
        int moveTimeMs = Integer.getInteger("matchMoveTimeMs", 50);
        int nodes = Integer.getInteger("matchNodes", 0);
        if (pairs < 1 || pairs > 50 || moveTimeMs < 20 || moveTimeMs > 10_000
                || nodes < 0 || nodes > 10_000_000) {
            throw new IllegalArgumentException("paired-match limits are invalid");
        }
        List<List<String>> openings = selectOpenings(Path.of(corpusDirectory), pairs);

        int candidateWins = 0;
        int stableWins = 0;
        int draws = 0;
        long started = System.nanoTime();
        try (UciEngine stable = new UciEngine(
                "stable-2026-01-02", Path.of(stableExecutable), Path.of(stableNetwork));
             UciEngine candidate = new UciEngine(
                     "master-b97ef0f", Path.of(candidateExecutable), Path.of(candidateNetwork))) {
            for (int index = 0; index < openings.size(); index++) {
                Outcome first = play(openings.get(index), candidate, stable, moveTimeMs, nodes);
                Outcome second = play(openings.get(index), stable, candidate, moveTimeMs, nodes);
                for (Outcome outcome : List.of(first, second)) {
                    if (outcome == Outcome.CANDIDATE) candidateWins++;
                    else if (outcome == Outcome.STABLE) stableWins++;
                    else draws++;
                }
                System.out.printf(
                        "PIKAFISH_PAIR pair=%d candidateRed=%s stableRed=%s running=%d-%d-%d%n",
                        index + 1, first, second, candidateWins, draws, stableWins);
            }
        }
        double score = (candidateWins + draws * 0.5) / (2.0 * openings.size());
        double elo = score <= 0.0 ? Double.NEGATIVE_INFINITY
                : score >= 1.0 ? Double.POSITIVE_INFINITY
                : -400.0 * Math.log10(1.0 / score - 1.0);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf(Locale.ROOT,
                "PIKAFISH_MATCH games=%d candidateW=%d draws=%d stableW=%d score=%.3f eloEstimate=%+.1f elapsedMs=%d movetimeMs=%d nodes=%d%n",
                openings.size() * 2, candidateWins, draws, stableWins,
                score, elo, elapsedMs, moveTimeMs, nodes);
        assertEquals(openings.size() * 2, candidateWins + draws + stableWins);
    }

    private List<List<String>> selectOpenings(Path directory, int count) {
        List<List<String>> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var line : new CcpdOpeningCorpusAuditor().audit(directory).lines()) {
            if (line.moves().size() < OPENING_PLIES) continue;
            List<String> prefix = List.copyOf(line.moves().subList(0, OPENING_PLIES));
            if (seen.add(String.join(" ", prefix))) selected.add(prefix);
            if (selected.size() == count) break;
        }
        if (selected.size() != count) {
            throw new IllegalArgumentException("the corpus has too few distinct legal openings");
        }
        return selected;
    }

    private Outcome play(List<String> opening,
                         UciEngine redEngine,
                         UciEngine blackEngine,
                         int moveTimeMs,
                         int nodes) throws Exception {
        GameTree game = GameTree.create(
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1");
        for (String move : opening) game.insert(game.current().id(), move);
        redEngine.newGame();
        blackEngine.newGame();
        Map<String, Integer> repetitions = new HashMap<>();
        repetitions.put(game.current().positionFen(), 1);
        int adjudicationSign = 0;
        int adjudicationCount = 0;

        for (int played = 0; played < MAX_PLAYED_PLIES; played++) {
            String fen = game.current().positionFen();
            boolean redToMove = fen.endsWith(" w");
            UciEngine moving = redToMove ? redEngine : blackEngine;
            Search search = moving.search(fen, moveTimeMs, nodes);
            if (search.move().equals("(none)") || search.move().equals("0000")) {
                return winner(!redToMove, redEngine);
            }
            try {
                game.insert(game.current().id(), search.move());
            } catch (IllegalArgumentException exception) {
                return winner(!redToMove, redEngine);
            }

            if (search.score() != null) {
                int redScore = redToMove ? search.score() : -search.score();
                int sign = Math.abs(redScore) >= ADJUDICATION_CP
                        ? Integer.signum(redScore) : 0;
                if (sign != 0 && sign == adjudicationSign) adjudicationCount++;
                else {
                    adjudicationSign = sign;
                    adjudicationCount = sign == 0 ? 0 : 1;
                }
                if (adjudicationCount >= ADJUDICATION_PLIES) {
                    return winner(adjudicationSign > 0, redEngine);
                }
            }

            String next = game.current().positionFen();
            if (repetitions.merge(next, 1, Integer::sum) >= 3) return Outcome.DRAW;
        }
        return Outcome.DRAW;
    }

    private Outcome winner(boolean redWon, UciEngine redEngine) {
        boolean candidateWon = redWon == redEngine.name.equals("master-b97ef0f");
        return candidateWon ? Outcome.CANDIDATE : Outcome.STABLE;
    }

    private enum Outcome { CANDIDATE, STABLE, DRAW }

    private record Search(String move, Integer score) { }

    private final class UciEngine implements AutoCloseable {

        private final String name;
        private final Process process;
        private final BufferedWriter input;
        private final BufferedReader output;

        private UciEngine(String name, Path executable, Path network) throws Exception {
            this.name = name;
            if (!Files.isRegularFile(executable) || !Files.isRegularFile(network)) {
                throw new IllegalArgumentException("engine pair is incomplete: " + name);
            }
            process = new ProcessBuilder(executable.toAbsolutePath().toString())
                    .directory(executable.toAbsolutePath().getParent().toFile())
                    .redirectErrorStream(true)
                    .start();
            input = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8));
            command("uci");
            waitFor("uciok", 10_000);
            command("setoption name Threads value 1");
            command("setoption name Hash value 64");
            command("setoption name MultiPV value 1");
            command("setoption name EvalFile value " + network.getFileName());
            command("isready");
            waitFor("readyok", 15_000);
        }

        private void newGame() throws Exception {
            command("ucinewgame");
            command("isready");
            waitFor("readyok", 10_000);
        }

        private Search search(String fen, int moveTimeMs, int nodes) throws Exception {
            command("position fen " + fen + " - - 0 1");
            command(nodes > 0 ? "go nodes " + nodes : "go movetime " + moveTimeMs);
            long deadline = System.nanoTime()
                    + Duration.ofMillis(Math.max(5_000L, moveTimeMs * 30L)).toNanos();
            Integer score = null;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException(name + " exited unexpectedly");
                if (!output.ready()) {
                    Thread.sleep(2);
                    continue;
                }
                String line = output.readLine();
                if (line == null) throw new IOException(name + " closed its output");
                Matcher scoreMatcher = SCORE.matcher(line);
                while (scoreMatcher.find()) {
                    int value = Integer.parseInt(scoreMatcher.group(2));
                    score = scoreMatcher.group(1).equals("mate")
                            ? Integer.signum(value) * 100_000 : value;
                }
                if (line.startsWith("bestmove ")) {
                    String[] fields = line.split("\\s+");
                    if (fields.length < 2) throw new IOException(name + " returned no move");
                    return new Search(fields[1], score);
                }
            }
            throw new IOException(name + " search timed out");
        }

        private void command(String command) throws IOException {
            input.write(command);
            input.newLine();
            input.flush();
        }

        private void waitFor(String expected, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) throw new IOException(name + " exited during startup");
                if (!output.ready()) {
                    Thread.sleep(2);
                    continue;
                }
                String line = output.readLine();
                if (line == null) throw new IOException(name + " closed its output");
                if (line.equals(expected)) return;
            }
            throw new IOException(name + " did not return " + expected);
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                try {
                    command("quit");
                } catch (IOException ignored) {
                    // The bounded wait below is the authoritative shutdown path.
                }
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
            }
            input.close();
            output.close();
        }
    }
}
