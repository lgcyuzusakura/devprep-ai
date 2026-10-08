package com.devprep.ai.model;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class ModelText {
    private ModelText() { }

    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    static String truncate(String value, int length) {
        return length(value) <= length ? value : value.substring(0, value.offsetByCodePoints(0, length));
    }

    static byte[] utf8(String value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    static String decode(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value)).toString();
    }

    static boolean forbiddenUrlCharacter(int character) {
        return character == '\\' || Character.isWhitespace(character) || Character.isSpaceChar(character)
                || character < 32 || character == 127;
    }
}
