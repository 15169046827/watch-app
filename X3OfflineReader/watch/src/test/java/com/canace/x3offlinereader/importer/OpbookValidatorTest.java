package com.canace.x3offlinereader.importer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

final class OpbookValidatorTest {
    @Test void acceptsExactCodePointSlice() {
        assertTrue(OpbookValidator.textMatches("字符𠀀。",0,4,"字符𠀀。"));
    }

    @Test void acceptsOnlyTheKnownLegacyTrailingWhitespaceMismatch() {
        assertTrue(OpbookValidator.textMatches("第一句。\n",0,5,"第一句。"));
        assertFalse(OpbookValidator.textMatches("第一句。错",0,5,"第一句。"));
    }

    @Test void acceptsKnownLegacyUnicodeLeadingWhitespaceMismatch() {
        assertTrue(OpbookValidator.textMatches("甲。　乙！",3,5,"　乙！"));
        assertFalse(OpbookValidator.textMatches("甲。错乙！",3,5,"错乙！"));
    }
}
