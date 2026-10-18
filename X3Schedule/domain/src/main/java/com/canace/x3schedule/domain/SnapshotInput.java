package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Validate external JSON before recursive model deserialization or constructor defaults are applied. */
final class SnapshotInput {
    private static final int MAX_DEPTH = 16;
    private static final String[] REQUIRED = {"protocol", "schema", "id", "source", "deviceId",
        "plans", "instances", "events", "settings", "settingsVersion", "revision"};

    private SnapshotInput() {
    }

    static String checkedText(byte[] bytes) {
        require(bytes != null && bytes.length > 0 && bytes.length <= Wire.MAX_BYTES, "文件大小无效");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                require(reader.peek() == JsonToken.BEGIN_OBJECT, "备份必须是 JSON 对象");
                readObject(reader, 0);
                require(reader.peek() == JsonToken.END_DOCUMENT, "文件包含多余内容");
            }
            return text;
        } catch (IOException error) {
            throw new IllegalArgumentException("文件必须为有效 UTF-8 JSON");
        }
    }

    private static void readObject(JsonReader reader, int depth) throws IOException {
        reader.beginObject();
        Set<String> names = new HashSet<>();
        while (reader.hasNext()) {
            String name = reader.nextName();
            require(names.add(name), "JSON 属性重复");
            if (depth == 0) {
                JsonToken expected = expectedType(name);
                require(expected == null || reader.peek() == expected, "备份字段类型无效");
            }
            readValue(reader, depth + 1);
        }
        reader.endObject();
        if (depth == 0) {
            require(names.containsAll(Arrays.asList(REQUIRED)), "备份字段缺失");
        }
    }

    private static JsonToken expectedType(String name) {
        switch (name) {
            case "protocol":
            case "schema":
            case "settingsVersion":
            case "revision":
                return JsonToken.NUMBER;
            case "id":
            case "source":
            case "deviceId":
                return JsonToken.STRING;
            case "plans":
            case "instances":
            case "events":
                return JsonToken.BEGIN_ARRAY;
            case "settings":
                return JsonToken.BEGIN_OBJECT;
            default:
                return null;
        }
    }

    private static void readValue(JsonReader reader, int depth) throws IOException {
        require(depth <= MAX_DEPTH, "JSON 嵌套过深");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                readObject(reader, depth);
                break;
            case BEGIN_ARRAY:
                reader.beginArray();
                while (reader.hasNext()) {
                    readValue(reader, depth + 1);
                }
                reader.endArray();
                break;
            case STRING:
            case NUMBER:
                reader.nextString();
                break;
            case BOOLEAN:
                reader.nextBoolean();
                break;
            case NULL:
                reader.nextNull();
                break;
            default:
                throw new IllegalArgumentException("JSON 值无效");
        }
    }
}
