package com.canace.x3schedule.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class SqlScriptTest {
    @Test
    public void migrationCommentSemicolonCannotBecomeAnExecutableFragment() {
        String script = "-- Keep facts; rebuild protocol transfers.\r\n"
                + "DELETE FROM batch_in;\r\n  -- Preserve history; settings; receipts.\r\n"
                + "UPDATE meta SET value='1';\r\n";
        assertEquals(Arrays.asList("DELETE FROM batch_in", "UPDATE meta SET value='1'"),
                SqlScript.statements(script));
    }

    @Test
    public void standaloneCommentOnlyTailDoesNotExecuteAnEmptyQuery() {
        assertTrue(SqlScript.statements("-- Migration notes; nothing to execute\n").isEmpty());
    }
}
