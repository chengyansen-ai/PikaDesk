package com.sojourners.chess.openbook;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class PersonalOpeningBookBuilderAcceptanceTest {

    @Test
    void buildsFromExplicitlySelectedLocalAssets() throws Exception {
        String primary = System.getProperty("primaryObk", "");
        String corpus = System.getProperty("ccpdOpeningDir", "");
        String destination = System.getProperty("personalObk", "");
        assumeTrue(!primary.isBlank() && !corpus.isBlank() && !destination.isBlank(),
                "set primaryObk, ccpdOpeningDir and personalObk to run the local build");

        Path output = Path.of(destination);
        PersonalOpeningBookBuilder.Report report = new PersonalOpeningBookBuilder().build(
                Path.of(primary), Path.of(corpus), output);
        System.out.printf(
                "PERSONAL_OBK primary=%d corpus=%d examined=%d followed=%d inserted=%d conflicts=%d completed=%d output=%s bytes=%d%n",
                report.primaryRows(), report.corpusLines(), report.examinedRows(),
                report.followedRows(), report.insertedGapRows(),
                report.conflictingLinesStopped(), report.completedLines(),
                output, Files.size(output));
        assertTrue(Files.isRegularFile(output));
        new BhOpenBook(output.toString()).close();
    }
}
