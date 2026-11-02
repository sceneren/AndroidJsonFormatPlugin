package io.github.logcat2kt;

import org.mozilla.javascript.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Runs only bundled scripts. Pasted log text is passed as data, never evaluated. */
public final class ConversionEngine {
    public static final int MAX_INPUT = 2_000_000;
    public record Options(String rootName, String anno, String nullMode, String caseMode, boolean tsLong) {
        public static Options defaults() { return new Options("Response", "gson", "all", "camel", true); }
    }
    public record Node(String key, String value, String type, List<Node> children) {}
    public record Result(String json, String kotlin, List<Node> roots, int lines, int entries,
                         int count, int merged, int lost, boolean limited) {}
    private final ThreadLocal<Long> deadline = new ThreadLocal<>();
    private final ContextFactory contexts = new ContextFactory() {
        @Override protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInterpretedMode(true);
            cx.setMaximumInterpreterStackDepth(256);
            cx.setInstructionObserverThreshold(10_000);
            cx.setClassShutter(name -> false);
            return cx;
        }
        @Override protected void observeInstructionCount(Context cx, int count) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (System.nanoTime() > deadline.get()) throw new IllegalArgumentException("解析超时，请减少日志长度或嵌套层级");
        }
    };
    private Scriptable scope;

    public synchronized Result convert(String raw, Options options) {
        if (raw.length() > MAX_INPUT) throw new IllegalArgumentException("输入超过 200 万字符，请分段处理");
        deadline.set(System.nanoTime() + 8_000_000_000L);
        try {
            return contexts.call(cx -> {
                ensureScope(cx);
                Scriptable opts = cx.newObject(scope);
                ScriptableObject.putProperty(opts, "rootName", options.rootName().trim().isEmpty() ? "Response" : options.rootName().trim());
                ScriptableObject.putProperty(opts, "anno", options.anno());
                ScriptableObject.putProperty(opts, "nullMode", options.nullMode());
                ScriptableObject.putProperty(opts, "caseMode", options.caseMode());
                ScriptableObject.putProperty(opts, "tsLong", options.tsLong());
                Function fn = (Function) ScriptableObject.getProperty(scope, "convertLog");
                Scriptable result = (Scriptable) fn.call(cx, scope, scope, new Object[]{raw, opts});
                List<Node> roots = new ArrayList<>();
                NativeArray groups = (NativeArray) get(result, "groups");
                int[] budget = {5000};
                for (Object groupObject : groups) {
                    Scriptable group = (Scriptable) groupObject;
                    List<Node> nodes = new ArrayList<>();
                    for (Object ast : (NativeArray) get(group, "nodes")) {
                        if (budget[0] <= 0) break;
                        nodes.add(readNode((Scriptable) ast, "", budget));
                    }
                    roots.add(new Node(string(group, "tag"), "", "group", nodes));
                    if (budget[0] <= 0) break;
                }
                Scriptable stats = (Scriptable) get(result, "stats");
                return new Result(string(result, "json"), string(result, "kotlin"), roots,
                    integer(stats, "lines"), integer(stats, "entries"), integer(result, "count"),
                    integer(stats, "merged"), integer(stats, "lost"), budget[0] <= 0);
            });
        } catch (StackOverflowError error) {
            throw new IllegalArgumentException("嵌套层级过深，请分段处理", error);
        } finally { deadline.remove(); }
    }

    private void ensureScope(Context cx) {
        if (scope != null) return;
        Scriptable fresh = cx.initSafeStandardObjects();
        cx.evaluateString(fresh, resource("/web/core.js"), "core.js", 1, null);
        cx.evaluateString(fresh, resource("/web/runtime.js"), "runtime.js", 1, null);
        scope = fresh; // Publish only once initialization has succeeded.
    }

    private static Node readNode(Scriptable ast, String key, int[] budget) {
        budget[0]--;
        String type = string(ast, "type");
        List<Node> children = new ArrayList<>();
        String value;
        if (type.equals("object")) {
            NativeArray entries = (NativeArray) get(ast, "entries");
            value = "{ " + entries.size() + " 个字段 }";
            for (Object e : entries) {
                if (budget[0] <= 0) break;
                Scriptable entry = (Scriptable) e;
                children.add(readNode((Scriptable) get(entry, "value"), string(entry, "key"), budget));
            }
        } else if (type.equals("array")) {
            NativeArray items = (NativeArray) get(ast, "items");
            value = "[ " + items.size() + " 项 ]";
            int index = 0;
            for (Object item : items) {
                if (budget[0] <= 0) break;
                children.add(readNode((Scriptable) item, "[" + index++ + "]", budget));
            }
        } else if (type.equals("number")) value = string(ast, "raw");
        else if (type.equals("null")) value = "null";
        else value = string(ast, "value");
        return new Node(key, value, type, children);
    }
    private static Object get(Scriptable object, String key) { return ScriptableObject.getProperty(object, key); }
    private static String string(Scriptable object, String key) { return Context.toString(get(object, key)); }
    private static int integer(Scriptable object, String key) {
        Object value = get(object, key);
        return value == Scriptable.NOT_FOUND ? 0 : (int) Context.toNumber(value);
    }
    public static String resource(String path) {
        try (var input = ConversionEngine.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing bundled resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
