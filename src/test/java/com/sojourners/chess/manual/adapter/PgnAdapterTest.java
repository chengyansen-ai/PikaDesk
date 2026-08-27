package com.sojourners.chess.manual.adapter;

import com.sojourners.chess.game.tree.GameTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PgnAdapterTest {

    private static final String VALID = """
            [Game "Chinese Chess"]
            [Event "本地测试赛"]
            [Site "上海"]
            [Result "1/2-1/2"]
            [FEN "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"]
            [Format "ICCS"]

            1. B0-C2 {先活马} (H0-G2) B9-C7 (B9-A7) 2. H0-G2 1/2-1/2
            """;

    @Test
    void importsMainlineVariationsResultMetadataAndComments() throws IOException {
        PgnAdapter.ReadResult result = new PgnAdapter().read(bytes(VALID));
        ManualDocument document = result.document();
        GameTree tree = document.tree();
        GameTree.Node first = tree.children(tree.root().id()).getFirst();

        assertEquals(ManualDocument.Result.DRAW, document.result());
        assertEquals("本地测试赛", document.metadata().get("Event"));
        assertEquals("上海", document.metadata().get("Site"));
        assertEquals(6, tree.size());
        assertEquals("b0c2", first.move());
        assertEquals("先活马", first.comment());
        assertEquals("b0c2", tree.node(tree.root().mainlineChildId().orElseThrow()).move());
        assertEquals(List.of("b0c2", "h0g2"),
                tree.children(tree.root().id()).stream().map(GameTree.Node::move).toList());
        assertEquals(List.of("b9c7", "b9a7"),
                tree.children(first.id()).stream().map(GameTree.Node::move).toList());
        assertTrue(result.notices().isEmpty());
    }

    @Test
    void canonicalUtf8RoundTripPreservesRepresentableContent() throws IOException {
        PgnAdapter adapter = new PgnAdapter();
        ManualDocument first = adapter.read(bytes(VALID)).document();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        PgnAdapter.WriteResult write = adapter.write(first, output);
        String encoded = output.toString(StandardCharsets.UTF_8);
        ManualDocument second = adapter.read(
                new ByteArrayInputStream(output.toByteArray())).document();

        assertTrue(write.notices().isEmpty());
        assertTrue(encoded.startsWith("[Game \"Chinese Chess\"]\n"));
        assertTrue(encoded.contains("[Format \"ICCS\"]"));
        assertTrue(encoded.endsWith("1/2-1/2\n"));
        assertEquals(first.result(), second.result());
        assertEquals(first.metadata(), second.metadata());
        assertEquals(semanticTree(first.tree()), semanticTree(second.tree()));
    }

    @Test
    void blackToMoveFenUsesTheBlackMoveNumberMarkerOnExport() throws IOException {
        String source = """
                [Game "Chinese Chess"]
                [Result "*"]
                [FEN "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR b"]
                [Format "ICCS"]

                1... B9-C7 *
                """;
        PgnAdapter adapter = new PgnAdapter();
        ManualDocument document = adapter.read(bytes(source)).document();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        adapter.write(document, output);

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("1... B9-C7"));
    }

    @Test
    void importsDomesticLegacyEncodingWithAnExplicitNotice() throws IOException {
        byte[] gb18030 = VALID.getBytes(Charset.forName("GB18030"));

        PgnAdapter.ReadResult result = new PgnAdapter().read(
                new ByteArrayInputStream(gb18030));

        assertEquals("本地测试赛", result.document().metadata().get("Event"));
        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("LEGACY_ENCODING")));
    }

    @Test
    void unsupportedNagIsSkippedButReportedAndMovesAreRetained() throws IOException {
        String source = VALID.replace("B0-C2 {先活马}", "B0-C2 $1 {先活马}");

        PgnAdapter.ReadResult result = new PgnAdapter().read(bytes(source));

        assertEquals(6, result.document().tree().size());
        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.code().equals("UNSUPPORTED_NAG")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[Game \"Chinese Chess\"]\n[Format \"Chinese\"]\n1. 马二进三 *\n",
            "[Game \"Chinese Chess\"\n[Format \"ICCS\"]\nB0-C2 *\n",
            "[Game \"Chinese Chess\"]\n[Game \"Chinese Chess\"]\n[Format \"ICCS\"]\n*\n",
            "[Game \"Chinese Chess\"]\n[Format \"ICCS\"]\nA0-A9 *\n",
            "[Game \"Chinese Chess\"]\n[Format \"ICCS\"]\n(B0-C2) *\n",
            "[Game \"Chinese Chess\"]\n[Format \"ICCS\"]\nB0-C2 {unterminated *\n",
            "[Game \"Chinese Chess\"]\n[Result \"1-0\"]\n[Format \"ICCS\"]\nB0-C2 0-1\n",
            "[Game \"Western Chess\"]\n[Format \"ICCS\"]\n*\n",
            "[Game \"Chinese Chess\"]\n[Format \"ICCS\"]\nB0-C2 * H9-G7\n"
    })
    void damagedOrUnsupportedInputFailsWithAStructuredCode(String source) {
        PgnAdapter.PgnException failure = assertThrows(PgnAdapter.PgnException.class,
                () -> new PgnAdapter().read(bytes(source)));

        assertFalse(failure.code().isBlank());
        assertTrue(failure.offset() >= 0);
    }

    @Test
    void gameTagMustBeFirstAndDuplicateVariationIsNotSilentlyCollapsed() {
        String wrongOrder = "[Site \"上海\"]\n[Game \"Chinese Chess\"]\n"
                + "[Format \"ICCS\"]\n*\n";
        String duplicateVariation = "[Game \"Chinese Chess\"]\n"
                + "[Format \"ICCS\"]\nB0-C2 (B0-C2) *\n";

        assertEquals("GAME_TAG_ORDER", assertThrows(PgnAdapter.PgnException.class,
                () -> new PgnAdapter().read(bytes(wrongOrder))).code());
        assertEquals("DUPLICATE_VARIATION", assertThrows(PgnAdapter.PgnException.class,
                () -> new PgnAdapter().read(bytes(duplicateVariation))).code());
    }

    @Test
    void excessiveUnsupportedAnnotationsCannotGrowTheNoticeListWithoutBound() {
        String source = "[Game \"Chinese Chess\"]\n[Format \"ICCS\"]\n"
                + "$1 ".repeat(PgnAdapter.MAX_NOTICES + 1) + "*\n";

        PgnAdapter.PgnException failure = assertThrows(PgnAdapter.PgnException.class,
                () -> new PgnAdapter().read(bytes(source)));

        assertEquals("NOTICE_LIMIT", failure.code());
    }

    @Test
    void oversizedInputIsRejectedAfterOnlyTheBoundedPrefixIsRead() {
        CountingInputStream oversized = new CountingInputStream(PgnAdapter.MAX_INPUT_BYTES + 10_000L);

        PgnAdapter.PgnException failure = assertThrows(PgnAdapter.PgnException.class,
                () -> new PgnAdapter().read(oversized));

        assertEquals("SIZE_LIMIT", failure.code());
        assertEquals(PgnAdapter.MAX_INPUT_BYTES + 1L, oversized.bytesRead());
    }

    @Test
    void malformedTextEncodingIsRejectedInsteadOfReplacementDecoded() {
        byte[] invalid = {(byte) 0xff, (byte) 0xff, (byte) 0xff};

        PgnAdapter.PgnException failure = assertThrows(PgnAdapter.PgnException.class,
                () -> new PgnAdapter().read(new ByteArrayInputStream(invalid)));

        assertEquals("INVALID_ENCODING", failure.code());
    }

    @Test
    void unrepresentableClosingBraceInCommentIsAnExplicitExportError() throws IOException {
        ManualDocument document = new PgnAdapter().read(bytes(VALID)).document();
        UUID first = document.tree().root().mainlineChildId().orElseThrow();
        document.tree().updateComment(first, "不能无损保存 } 字符");

        PgnAdapter.PgnException failure = assertThrows(PgnAdapter.PgnException.class,
                () -> new PgnAdapter().write(document, new ByteArrayOutputStream()));

        assertEquals("UNREPRESENTABLE_COMMENT", failure.code());
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> semanticTree(GameTree tree) {
        List<String> rows = new ArrayList<>();
        List<GameTree.Node> pending = new ArrayList<>();
        pending.add(tree.root());
        while (!pending.isEmpty()) {
            GameTree.Node parent = pending.removeFirst();
            List<GameTree.Node> children = tree.children(parent.id());
            rows.add(parent.move() + "|" + parent.comment() + "|"
                    + parent.mainlineChildId().map(id -> tree.node(id).move()).orElse("-")
                    + "|" + children.stream().map(GameTree.Node::move).toList());
            pending.addAll(children);
        }
        return rows;
    }

    private static final class CountingInputStream extends InputStream {
        private final long length;
        private long position;

        private CountingInputStream(long length) {
            this.length = length;
        }

        @Override
        public int read() {
            if (position >= length) return -1;
            position++;
            return 'x';
        }

        private long bytesRead() {
            return position;
        }
    }
}
