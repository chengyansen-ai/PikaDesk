package com.sojourners.chess.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PropertiesDefaultsTest {

    @Test
    void newInstallStartsWithCloudAndBookQueriesDisabled() {
        Properties defaults = Properties.createDefault();

        assertFalse(defaults.getUseCloudBook());
        assertFalse(defaults.getBookSwitch());
    }

    @Test
    void serializedUserOptInRemainsEnabledAfterReload() throws Exception {
        Properties configured = Properties.createDefault();
        configured.setUseCloudBook(true);
        configured.setBookSwitch(true);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(configured);
        }

        Properties reloaded;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            reloaded = (Properties) input.readObject();
        }

        assertTrue(reloaded.getUseCloudBook());
        assertTrue(reloaded.getBookSwitch());
    }
}
