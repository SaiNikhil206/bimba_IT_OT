package com.bimba.gateway.collect;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small expression language for computed tags (no field device), compatible with the common subset of
 * Ignition expressions:
 * <pre>
 *   240 + 15 * sin(toMillis(now(1000)) / 60000.0)
 *   getSecond(now(1000)) &lt; 50
 *   if(getSecond(now(1000)) &lt; 50, 'RUNNING', 'IDLE')
 * </pre>
 * Numbers, 'strings', true/false, + - * / %, comparisons (&lt; &lt;= &gt; &gt;= = == != &lt;&gt;), &amp;&amp; || !, and the functions
 * now([ms]), toMillis(d), getSecond/getMinute/getHour24/getDayOfWeek(d), sin, cos, tan, abs, sqrt, round(x[,digits]),
 * floor, ceil, min, max, if(c, a, b). Parsed once, evaluated every poll.
 */
public final class Expression {
    private interface Node {
        Object eval();
    }

    private final Node root;
    private final String text;
    private int pos;

    private Expression(String text) {
        this.text = text;
        this.root = parseOr();
        skip();
        if (pos < text.length()) {
            throw error("unexpected '" + text.charAt(pos) + "'");
        }
    }

    public static Expression parse(String text) {
        return new Expression(text == null ? "" : text);
    }

    public Object evaluate() {
        return root.eval();
    }

    // ------------------------------------------------------------------ parser (recursive descent)

    private Node parseOr() {
        Node l = parseAnd();
        while (eat("||")) {
            Node a = l, b = parseAnd();
            l = () -> bool(a.eval()) || bool(b.eval());
        }
        return l;
    }

    private Node parseAnd() {
        Node l = parseCompare();
        while (eat("&&")) {
            Node a = l, b = parseCompare();
            l = () -> bool(a.eval()) && bool(b.eval());
        }
        return l;
    }

    private Node parseCompare() {
        Node l = parseAdd();
        for (String op : new String[]{"<=", ">=", "==", "!=", "<>", "<", ">", "="}) {
            if (eat(op)) {
                Node a = l, b = parseAdd();
                return () -> compare(op, a.eval(), b.eval());
            }
        }
        return l;
    }

    private Node parseAdd() {
        Node l = parseMul();
        while (true) {
            if (eat("+")) {
                Node a = l, b = parseMul();
                l = () -> {
                    Object x = a.eval(), y = b.eval();
                    return x instanceof String || y instanceof String ? str(x) + str(y) : num(x) + num(y);
                };
            } else if (eat("-")) {
                Node a = l, b = parseMul();
                l = () -> num(a.eval()) - num(b.eval());
            } else {
                return l;
            }
        }
    }

    private Node parseMul() {
        Node l = parseUnary();
        while (true) {
            if (eat("*")) {
                Node a = l, b = parseUnary();
                l = () -> num(a.eval()) * num(b.eval());
            } else if (eat("/")) {
                Node a = l, b = parseUnary();
                l = () -> num(a.eval()) / num(b.eval());
            } else if (eat("%")) {
                Node a = l, b = parseUnary();
                l = () -> num(a.eval()) % num(b.eval());
            } else {
                return l;
            }
        }
    }

    private Node parseUnary() {
        if (eat("-")) {
            Node a = parseUnary();
            return () -> -num(a.eval());
        }
        if (eat("!")) {
            Node a = parseUnary();
            return () -> !bool(a.eval());
        }
        return parsePrimary();
    }

    private Node parsePrimary() {
        skip();
        if (pos >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(pos);
        if (eat("(")) {
            Node e = parseOr();
            expect(")");
            return e;
        }
        if (c == '\'' || c == '"') {
            int end = text.indexOf(c, pos + 1);
            if (end < 0) {
                throw error("unterminated string");
            }
            String s = text.substring(pos + 1, end);
            pos = end + 1;
            return () -> s;
        }
        if (Character.isDigit(c) || c == '.') {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.'
                    || text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
            }
            double v = Double.parseDouble(text.substring(start, pos));
            return () -> v;
        }
        if (Character.isLetter(c)) {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            String name = text.substring(start, pos).toLowerCase(Locale.ROOT);
            if (name.equals("true") || name.equals("false")) {
                boolean b = name.equals("true");
                return () -> b;
            }
            List<Node> args = new ArrayList<>();
            expect("(");
            if (!eat(")")) {
                do {
                    args.add(parseOr());
                } while (eat(","));
                expect(")");
            }
            return function(name, args);
        }
        throw error("unexpected '" + c + "'");
    }

    private Node function(String name, List<Node> a) {
        switch (name) {
            case "now":
                return () -> Instant.now();
            case "tomillis":
                arity(name, a, 1);
                return () -> instant(a.get(0).eval()).toEpochMilli();
            case "getsecond":
                arity(name, a, 1);
                return () -> (double) utc(a.get(0).eval()).getSecond();
            case "getminute":
                arity(name, a, 1);
                return () -> (double) utc(a.get(0).eval()).getMinute();
            case "gethour24":
                arity(name, a, 1);
                return () -> (double) utc(a.get(0).eval()).getHour();
            case "getdayofweek":
                arity(name, a, 1);
                return () -> (double) (utc(a.get(0).eval()).getDayOfWeek().getValue() % 7 + 1);
            case "sin": case "cos": case "tan": case "abs": case "sqrt": case "floor": case "ceil":
                arity(name, a, 1);
                return () -> {
                    double x = num(a.get(0).eval());
                    return switch (name) {
                        case "sin" -> Math.sin(x);
                        case "cos" -> Math.cos(x);
                        case "tan" -> Math.tan(x);
                        case "abs" -> Math.abs(x);
                        case "sqrt" -> Math.sqrt(x);
                        case "floor" -> Math.floor(x);
                        default -> Math.ceil(x);
                    };
                };
            case "round":
                return () -> {
                    double x = num(a.get(0).eval());
                    double f = Math.pow(10, a.size() > 1 ? num(a.get(1).eval()) : 0);
                    return Math.round(x * f) / f;
                };
            case "min":
                arity(name, a, 2);
                return () -> Math.min(num(a.get(0).eval()), num(a.get(1).eval()));
            case "max":
                arity(name, a, 2);
                return () -> Math.max(num(a.get(0).eval()), num(a.get(1).eval()));
            case "if":
                arity(name, a, 3);
                return () -> bool(a.get(0).eval()) ? a.get(1).eval() : a.get(2).eval();
            default:
                throw error("unknown function " + name + "()");
        }
    }

    private void arity(String name, List<Node> a, int n) {
        if (a.size() != n) {
            throw error(name + "() takes " + n + " argument(s)");
        }
    }

    // ------------------------------------------------------------------ values

    private static Object compare(String op, Object x, Object y) {
        int c;
        if (x instanceof String || y instanceof String) {
            c = str(x).compareTo(str(y));
        } else {
            c = Double.compare(num(x), num(y));
        }
        return switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "!=", "<>" -> c != 0;
            default -> c == 0;
        };
    }

    static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Instant i) {
            return i.toEpochMilli();
        }
        try {
            return Double.parseDouble(str(v));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return s.equalsIgnoreCase("true");
        }
        return num(v) != 0;
    }

    private static String str(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(v);
    }

    private static Instant instant(Object v) {
        return v instanceof Instant i ? i : Instant.ofEpochMilli((long) num(v));
    }

    private static ZonedDateTime utc(Object v) {
        return instant(v).atZone(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ lexer helpers

    private void skip() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private boolean eat(String token) {
        skip();
        if (text.startsWith(token, pos)) {
            // "<" must not eat the "<" of "<=" / "<>" (longer operators are tried first by the caller)
            pos += token.length();
            return true;
        }
        return false;
    }

    private void expect(String token) {
        if (!eat(token)) {
            throw error("expected '" + token + "'");
        }
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at position " + pos + " in: " + text);
    }
}
