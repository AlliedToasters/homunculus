package dev.toast.homunculus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer + parser for v1 endpoints.
 * Writer: null, Boolean, Number, String, List, Map.
 * Parser: same value shapes; numbers as Long if integral, otherwise Double.
 */
public final class Json {
	private Json() {}

	public static String write(Object value) {
		StringBuilder sb = new StringBuilder();
		writeValue(sb, value);
		return sb.toString();
	}

	public static Object parse(String input) {
		Parser p = new Parser(input);
		Object v = p.readValue();
		p.skipWs();
		if (p.pos < p.src.length()) throw p.fail("trailing content");
		return v;
	}

	private static void writeValue(StringBuilder sb, Object value) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof Boolean b) {
			sb.append(b ? "true" : "false");
		} else if (value instanceof Number n) {
			sb.append(n);
		} else if (value instanceof String s) {
			writeString(sb, s);
		} else if (value instanceof Map<?, ?> map) {
			writeObject(sb, map);
		} else if (value instanceof List<?> list) {
			writeArray(sb, list);
		} else {
			throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass());
		}
	}

	private static void writeObject(StringBuilder sb, Map<?, ?> map) {
		sb.append('{');
		boolean first = true;
		for (Map.Entry<?, ?> e : map.entrySet()) {
			if (!first) sb.append(',');
			first = false;
			writeString(sb, String.valueOf(e.getKey()));
			sb.append(':');
			writeValue(sb, e.getValue());
		}
		sb.append('}');
	}

	private static void writeArray(StringBuilder sb, List<?> list) {
		sb.append('[');
		boolean first = true;
		for (Object item : list) {
			if (!first) sb.append(',');
			first = false;
			writeValue(sb, item);
		}
		sb.append(']');
	}

	private static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	private static final class Parser {
		final String src;
		int pos;

		Parser(String src) { this.src = src; }

		Object readValue() {
			skipWs();
			if (pos >= src.length()) throw fail("unexpected end of input");
			char c = src.charAt(pos);
			return switch (c) {
				case '{' -> readObject();
				case '[' -> readArray();
				case '"' -> readString();
				case 't', 'f' -> readBool();
				case 'n' -> readNull();
				default -> readNumber();
			};
		}

		Map<String, Object> readObject() {
			expect('{');
			Map<String, Object> out = new LinkedHashMap<>();
			skipWs();
			if (peek() == '}') { pos++; return out; }
			while (true) {
				skipWs();
				String key = readString();
				skipWs();
				expect(':');
				Object value = readValue();
				out.put(key, value);
				skipWs();
				char c = peek();
				if (c == ',') { pos++; continue; }
				if (c == '}') { pos++; return out; }
				throw fail("expected ',' or '}'");
			}
		}

		List<Object> readArray() {
			expect('[');
			List<Object> out = new ArrayList<>();
			skipWs();
			if (peek() == ']') { pos++; return out; }
			while (true) {
				out.add(readValue());
				skipWs();
				char c = peek();
				if (c == ',') { pos++; continue; }
				if (c == ']') { pos++; return out; }
				throw fail("expected ',' or ']'");
			}
		}

		String readString() {
			expect('"');
			StringBuilder sb = new StringBuilder();
			while (pos < src.length()) {
				char c = src.charAt(pos++);
				if (c == '"') return sb.toString();
				if (c == '\\') {
					if (pos >= src.length()) throw fail("dangling escape");
					char esc = src.charAt(pos++);
					switch (esc) {
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						case '/' -> sb.append('/');
						case 'b' -> sb.append('\b');
						case 'f' -> sb.append('\f');
						case 'n' -> sb.append('\n');
						case 'r' -> sb.append('\r');
						case 't' -> sb.append('\t');
						case 'u' -> {
							if (pos + 4 > src.length()) throw fail("bad unicode escape");
							sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
							pos += 4;
						}
						default -> throw fail("unknown escape \\" + esc);
					}
				} else {
					sb.append(c);
				}
			}
			throw fail("unterminated string");
		}

		Object readNumber() {
			int start = pos;
			boolean fractional = false;
			if (peek() == '-') pos++;
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (c >= '0' && c <= '9') { pos++; continue; }
				if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { fractional = true; pos++; continue; }
				break;
			}
			String num = src.substring(start, pos);
			if (num.isEmpty() || "-".equals(num)) throw fail("bad number");
			return fractional ? Double.parseDouble(num) : Long.parseLong(num);
		}

		Boolean readBool() {
			if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
			if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
			throw fail("expected bool");
		}

		Object readNull() {
			if (src.startsWith("null", pos)) { pos += 4; return null; }
			throw fail("expected null");
		}

		void skipWs() {
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
				else break;
			}
		}

		char peek() { return pos < src.length() ? src.charAt(pos) : '\0'; }

		void expect(char c) {
			if (peek() != c) throw fail("expected '" + c + "'");
			pos++;
		}

		IllegalArgumentException fail(String msg) {
			return new IllegalArgumentException(msg + " at pos " + pos);
		}
	}
}
