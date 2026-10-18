package com.canace.x3schedule.domain;

import java.util.ArrayList;
import java.util.List;

/** Maintained schema assets use standalone line comments and no semicolons in SQL literals or triggers. */
public final class SqlScript {
    private SqlScript() {
    }

    public static List<String> statements(String script) {
        String executable = script.replaceAll("(?m)^[\\t ]*--[^\\r\\n]*", "");
        List<String> result = new ArrayList<>();
        for (String statement : executable.split(";")) {
            if (!statement.trim().isEmpty()) {
                result.add(statement.trim());
            }
        }
        return result;
    }
}
