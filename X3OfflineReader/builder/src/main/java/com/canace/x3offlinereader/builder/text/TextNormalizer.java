package com.canace.x3offlinereader.builder.text;

import java.text.Normalizer;

public final class TextNormalizer {
    private TextNormalizer() { }

    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .replace('\u00a0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
