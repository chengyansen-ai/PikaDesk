package com.sojourners.chess.openbook;

import com.sojourners.chess.util.XiangqiUtils;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class PersonalOpeningBookQualityAcceptanceTest {

    private static final String INITIAL_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w";

    @Test
    void auditsTheExplicitlySelectedPersonalObk() throws Exception {
        String configured = System.getProperty("personalObk", "");
        assumeTrue(!configured.isBlank(), "set personalObk to run the local quality audit");
        Path selected = Path.of(configured);

        long rows;
        long supplements;
        long invalidRows;
        long duplicateRows;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + selected);
             Statement statement = connection.createStatement()) {
            try (ResultSet check = statement.executeQuery("PRAGMA quick_check")) {
                assertTrue(check.next());
                assertEquals("ok", check.getString(1));
            }
            rows = scalar(statement, "SELECT count(*) FROM bhobk");
            supplements = scalar(statement,
                    "SELECT count(*) FROM bhobk WHERE vmemo LIKE 'CCPD CC BY 4.0 gap-fill;%'");
            invalidRows = scalar(statement, """
                    SELECT count(*) FROM bhobk
                    WHERE vkey IS NULL OR vmove IS NULL OR vscore < 3 OR vvalid <> 1
                       OR vwin < 0 OR vdraw < 0 OR vlost < 0
                       OR ((vmove >> 8) & 15) NOT BETWEEN 3 AND 11
                       OR ((vmove >> 12) & 15) NOT BETWEEN 3 AND 12
                       OR (vmove & 15) NOT BETWEEN 3 AND 11
                       OR ((vmove >> 4) & 15) NOT BETWEEN 3 AND 12
                    """);
            duplicateRows = scalar(statement, """
                    SELECT count(*) FROM (
                        SELECT typeof(vkey), quote(vkey), vmove
                        FROM bhobk
                        GROUP BY typeof(vkey), quote(vkey), vmove
                        HAVING count(*) > 1)
                    """);
        }
        try (BhOpenBook book = new BhOpenBook(selected.toString())) {
            int initialMoves = book.get(XiangqiUtils.fenToBoard(INITIAL_FEN), true).size();
            System.out.printf(
                    "PERSONAL_OBK_AUDIT rows=%d supplements=%d invalid=%d duplicates=%d initialMoves=%d%n",
                    rows, supplements, invalidRows, duplicateRows, initialMoves);
            assertTrue(initialMoves > 0);
        }
        assertTrue(rows > 0);
        assertTrue(supplements > 0);
        assertEquals(0, invalidRows);
        assertEquals(0, duplicateRows);
    }

    private long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
