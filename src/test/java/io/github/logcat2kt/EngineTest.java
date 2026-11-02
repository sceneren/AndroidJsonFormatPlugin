package io.github.logcat2kt;

import org.mozilla.javascript.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class EngineTest {
    private static int passed;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
    }
    public static void main(String[] args) throws Exception {
        ConversionEngine engine = new ConversionEngine();
        var opts = ConversionEngine.Options.defaults();
        var sample = engine.convert(ConversionEngine.resource("/web/sample.log"), opts);
        check(sample.count() == 2 && sample.merged() > 0, "sample groups and truncated merge");
        var longId = engine.convert("{\"id\":9007199254740993123,\"name\":\"张三\"}", opts);
        check(longId.json().contains("9007199254740993123") && longId.kotlin().contains("Long?"), "64-bit precision");
        check(engine.convert("{\"user_id\":1}", opts).kotlin().contains("@SerializedName"), "Gson renamed-field annotations");
        check(engine.convert("Response(code=0, data=User(id=1, name=hello))", opts).count() == 1, "toString");
        check(engine.convert("D/PB: id: 1\nD/PB: name: \"Alice\"\nD/PB: active: true", opts).json().contains("Alice"), "flat protobuf by tag");
        check(engine.convert("08 96 01 12 05 68 65 6c 6c 6f", opts).json().contains("hello"), "protobuf UTF-8");
        check(engine.convert("08 ff ff ff ff ff ff ff ff 7f", opts).json().contains("9223372036854775807"), "protobuf varint64");
        check(engine.convert("09 ff ff ff ff ff ff ff 7f", opts).json().contains("9223372036854775807"), "protobuf fixed64");
        check(engine.convert("{bad:}", opts) != null, "lenient malformed input stays bounded");
        check(engine.convert("Unrelated plain log", opts).count() == 0, "non-structured input");
        check(engine.convert("", opts).json().isEmpty(), "empty input");
        String unsafe = "{\"name\":\"<html><img src='https://example.test/x'>\",\"value\":\"java.lang.Runtime\"}";
        check(engine.convert(unsafe, opts).json().contains("java.lang.Runtime"), "log strings remain data");
        for (String anno : new String[]{"none", "gson", "kotlinx", "moshi"}) {
            var r = engine.convert("{\"user_id\":1}", new ConversionEngine.Options("Account", anno, "none", "camel", true));
            check(r.kotlin().contains("data class Account") && r.kotlin().contains("userId: Int"), "options " + anno);
        }
        check(engine.convert("{\"create_time\":123}", opts).kotlin().contains("Long?"), "timestamp promotion");
        try { engine.convert("x".repeat(ConversionEngine.MAX_INPUT + 1), opts); throw new AssertionError("size limit"); }
        catch (IllegalArgumentException expected) { passed++; }
        Thread.currentThread().interrupt();
        try { engine.convert("{\"x\":[1,2,3]}".repeat(1000), opts); throw new AssertionError("cancellation"); }
        catch (CancellationException expected) { passed++; }
        finally { Thread.interrupted(); }
        check(engine.convert("{\"ok\":true}", opts).count() == 1, "engine recovers after cancellation");
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < 5100; i++) { if (i > 0) big.append(','); big.append(i); }
        var limited = engine.convert(big.append(']').toString(), opts);
        check(limited.limited() && limited.json().contains("5099"), "tree budget preserves full copy");
        chunkedLogRegression(engine, opts);
        numericChunkAndAnnotationRegression(engine, opts);
        runUpstream();
        System.out.println("Plugin integration checks: " + passed + " passed");
    }

    private static void numericChunkAndAnnotationRegression(ConversionEngine engine, ConversionEngine.Options opts) throws Exception {
        Path fixtures = Path.of("src/test/resources/regressions");
        String raw = Files.readString(fixtures.resolve("apicodec-three-chunks.log"));
        String expected = Files.readString(fixtures.resolve("apicodec-three-chunks.expected.json")).replace("\r\n", "\n").strip();
        var actual = engine.convert(raw, opts);
        check(actual.json().equals(expected), "full attached 23-record log preserves split timestamp 1793597004 without phantom 004 field");
        check(actual.kotlin().equals(engine.convert(expected, opts).kotlin()), "attached log Kotlin schema matches complete JSON");
        check(actual.merged() == 2 && !actual.kotlin().contains("f004"), "both attached chunk boundaries merge cleanly");
        // Every cut in strings, numbers, signs, decimals, exponents, literals and delimiters.
        String json = "{\"id\":9007199254740993123,\"timestamp\":1793597004,\"negative\":-1234,\"decimal\":12.345,\"exponent\":1.23e-4,\"active\":true,\"off\":false,\"missing\":null,\"message\":\"hello world\"}";
        String formatted = engine.convert(json, opts).json();
        for (int i = 1; i < json.length(); i++) {
            String split = "D/ApiCodec: [1/2] " + json.substring(0, i) + "\nD/ApiCodec: [2/2] " + json.substring(i);
            if (!engine.convert(split, opts).json().equals(formatted)) throw new AssertionError("numbered chunk at character " + i);
        }
        check(true, "numbered chunks preserve JSON at every token boundary");
        for (String annotation : new String[]{"gson", "kotlinx", "moshi"}) {
            String code = engine.convert("{\"user_id\":1,\"create_time\":1793597004,\"name\":\"test\"}",
                new ConversionEngine.Options("Response", annotation, "all", "camel", true)).kotlin();
            String mark = annotation.equals("gson") ? "@SerializedName(\"user_id\")" : annotation.equals("kotlinx") ? "@SerialName(\"user_id\")" : "@Json(name = \"user_id\")";
            check(code.contains("    " + mark + "\n    val userId: Int? = null,"), annotation + " annotation on preceding line");
            check(!java.util.regex.Pattern.compile("(?m)^\\s*@[^\\n]*\\bval\\s").matcher(code).find(), annotation + " never shares property line");
        }
        String none = engine.convert("{\"user_id\":1}", new ConversionEngine.Options("Response", "none", "all", "camel", true)).kotlin();
        check(none.contains("    val userId: Int? = null") && !none.contains("@"), "unannotated mode remains unchanged");
    }

    private static void chunkedLogRegression(ConversionEngine engine, ConversionEngine.Options opts) {
        // Same break in the property name as the reported ApiCodec response.
        String json = "{\"data\":{\"lists\":[{\"id\":8913787372438528,\"question\":\"4\",\"image_icon\":\"https\\:/example.test/a.png\"},"
            + "{\"id\":8913786334086144,\"question\":\"3\"}]}}";
        int seam = json.indexOf("question") + 3;
        String prefix = "13:24:29.069 ApiCodec                com.example.demo                       D  ";
        String raw = json.substring(0, seam) + "\n" + prefix + "[2/2] " + json.substring(seam);
        var parsed = engine.convert(raw, opts);
        check(parsed.json().equals(engine.convert(json, opts).json()), "ApiCodec split key rejoins exact JSON");
        check(parsed.kotlin().equals(engine.convert(json, opts).kotlin()), "ApiCodec split produces identical Kotlin schema");
        check(parsed.merged() == 1 && !parsed.kotlin().contains("ApiCodec"), "prefix and chunk marker never become fields");
        check(engine.convert(raw.replace("\n", "\\\n"), opts).json().equals(parsed.json()), "copied trailing backslash before split key");
        for (String header : new String[]{prefix, "2026-11-02 13:24:29.069 1234-1234 ApiCodec com.example.demo D  ", "D/ApiCodec: "}) {
            var fromStart = engine.convert(header + "[1/2] " + json.substring(0, seam) + "\n" + header + "[2/2] " + json.substring(seam), opts);
            check(fromStart.json().equals(engine.convert(json, opts).json()), "numbered chunks with prefix " + header);
        }
        String literal = "{\"message\":\"[2/2] is data; 13:24:29.069 ApiCodec com.example.demo D  keep\"}";
        check(engine.convert(literal, opts).json().contains("[2/2] is data; 13:24:29.069"), "metadata-like string values stay intact");
        String spaces = "D/ApiCodec: [1/2] {\"message\":\"hello\nD/ApiCodec: [2/2]  world\"}";
        check(engine.convert(spaces, opts).json().contains("hello world"), "payload leading whitespace survives chunk marker removal");
        StringBuilder response = new StringBuilder("{\"code\":200,\"err_code\":0,\"current_time\":1793597069,\"msg\":\"\",\"data\":{\"lists\":[");
        for (int i = 13; i >= 1; i--) {
            if (i < 13) response.append(',');
            response.append("{\"id\":").append(8913782897902592L + i).append(",\"archive_no\":\"2026-11-02-").append(i)
                .append("\",\"archive_date\":\"2026-11-02\",\"question\":\"").append(i)
                .append("\",\"supplement\":\"\",\"image_icon\":\"https\\:/example.test/a.png\",\"status\":1,\"create_time\":1793597069,\"update_time\":1793597069}");
        }
        String complete = response.append("],\"limit\":50}}").toString();
        int tenth = complete.indexOf("\"question\":\"4\"") + 4;
        var thirteen = engine.convert(complete.substring(0, tenth) + "\n" + prefix + "[2/2] " + complete.substring(tenth), opts);
        check(thirteen.json().equals(engine.convert(complete, opts).json()) && thirteen.kotlin().equals(engine.convert(complete, opts).kotlin()),
            "13 archive items with key split in tenth record preserve all fields and shared Kotlin schema");
    }

    /** Execute upstream's unmodified assertion bodies against the exact shipped core in Rhino. */
    private static void runUpstream() throws Exception {
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ES6); cx.setInterpretedMode(true);
            Scriptable scope = cx.initSafeStandardObjects();
            String test = Files.readString(Path.of("vendor/upstream-test.cjs"), StandardCharsets.UTF_8);
            test = test.substring(test.indexOf("let pass = 0"));
            test = test.replace("process.exit(fail ? 1 : 0);", "if (fail) throw new Error(fail + ' upstream tests failed'); pass;");
            cx.evaluateString(scope, ConversionEngine.resource("/web/core.js"), "core.js", 1, null);
            cx.evaluateString(scope, ConversionEngine.resource("/web/runtime.js"), "runtime.js", 1, null);
            cx.evaluateString(scope, "var L = L2K; var console = {log:function(){}};", "test-env", 1, null);
            Object count = cx.evaluateString(scope, test, "upstream-test.cjs", 1, null);
            check(Context.toNumber(count) == 88, "all 88 upstream tests pass in shipped Rhino engine");
            System.out.println("Upstream regression checks in Rhino: " + Context.toString(count) + " passed");
        }
    }
}
