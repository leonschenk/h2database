/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.util;

import java.io.ByteArrayOutputStream;
import java.lang.ref.SoftReference;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntPredicate;

import org.h2.api.ErrorCode;
import org.h2.engine.SysProperties;
import org.h2.message.DbException;

/**
 * A few String utility functions.
 */
public class StringUtils {

    private static SoftReference<String[]> softCache;
    private static long softCacheCreatedNs;

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int[] HEX_DECODE = new int['f' + 1];

    // memory used by this cache:
    // 4 * 1024 * 2 (strings per pair) * 64 * 2 (bytes per char) = 0.5 MB
    private static final int TO_UPPER_CACHE_LENGTH = 2 * 1024;
    private static final int TO_UPPER_CACHE_MAX_ENTRY_LENGTH = 64;
    private static final AtomicReferenceArray<String[]> TO_UPPER_CACHE = new AtomicReferenceArray<>(
            TO_UPPER_CACHE_LENGTH);

    static {
        Arrays.fill(HEX_DECODE, -1);
        for (int i = 0; i <= 9; i++) {
            HEX_DECODE[i + '0'] = i;
        }
        for (int i = 0; i <= 5; i++) {
            HEX_DECODE[i + 'a'] = HEX_DECODE[i + 'A'] = i + 10;
        }
    }

    private StringUtils() {
        // utility class
    }

    private static String[] getCache() {
        String[] cache;
        if (softCache != null) {
            cache = softCache.get();
            if (cache != null) {
                return cache;
            }
        }
        // create a new cache at most every 5 seconds
        // so that out of memory exceptions are not delayed
        long time = System.nanoTime();
        if (softCacheCreatedNs != 0 && time - softCacheCreatedNs < TimeUnit.SECONDS.toNanos(5)) {
            return null;
        }
        try {
            cache = new String[SysProperties.OBJECT_CACHE_SIZE];
            softCache = new SoftReference<>(cache);
            return cache;
        } finally {
            softCacheCreatedNs = System.nanoTime();
        }
    }

    /**
     * Convert a string to uppercase using the English locale.
     *
     * @param s the test to convert
     * @return the uppercase text
     */
    public static String toUpperEnglish(String s) {
        if (s.length() > TO_UPPER_CACHE_MAX_ENTRY_LENGTH) {
            return s.toUpperCase(Locale.ENGLISH);
        }
        int index = s.hashCode() & (TO_UPPER_CACHE_LENGTH - 1);
        String[] e = TO_UPPER_CACHE.get(index);
        if (e != null) {
            if (e[0].equals(s)) {
                return e[1];
            }
        }
        String s2 = s.toUpperCase(Locale.ENGLISH);
        TO_UPPER_CACHE.compareAndSet(index, e, new String[] { s, s2 });
        return s2;
    }

    /**
     * Convert a string to lowercase using the English locale.
     *
     * @param s the text to convert
     * @return the lowercase text
     */
    public static String toLowerEnglish(String s) {
        return s.toLowerCase(Locale.ENGLISH);
    }

    /**
     * Convert a string to a SQL literal. Null is converted to NULL. The text is
     * enclosed in single quotes. If there are any special characters, the
     * Unicode character string literal is used.
     *
     * @param s the text to convert.
     * @return the SQL literal
     */
    public static String quoteStringSQL(String s) {
        if (s == null) {
            return "NULL";
        }
        return quoteStringSQL(new StringBuilder(s.length() + 2), s).toString();
    }

    /**
     * Convert a string to a SQL character string literal. Null is converted to
     * NULL. If there are any special characters, the Unicode character string
     * literal is used.
     *
     * @param builder
     *            string builder to append result to
     * @param s the text to convert
     * @return the specified string builder
     */
    public static StringBuilder quoteStringSQL(StringBuilder builder, String s) {
        if (s == null) {
            return builder.append("NULL");
        }
        return quoteIdentifierOrLiteral(builder, s, '\'');
    }

    /**
     * Decodes a Unicode SQL string.
     *
     * @param s
     *            the string to decode
     * @param uencode
     *            the code point of UENCODE character, or '\\'
     * @return the decoded string
     * @throws DbException
     *             on format exception
     */
    public static String decodeUnicodeStringSQL(String s, int uencode) {
        int l = s.length();
        StringBuilder builder = new StringBuilder(l);
        for (int i = 0; i < l;) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == uencode) {
                if (i >= l) {
                    throw getFormatException(s, i);
                }
                cp = s.codePointAt(i);
                if (cp == uencode) {
                    i += Character.charCount(cp);
                } else {
                    if (i + 4 > l) {
                        throw getFormatException(s, i);
                    }
                    char ch = s.charAt(i);
                    try {
                        if (ch == '+') {
                            if (i + 7 > l) {
                                throw getFormatException(s, i);
                            }
                            cp = Integer.parseUnsignedInt(s.substring(i + 1, i += 7), 16);
                        } else {
                            cp = Integer.parseUnsignedInt(s.substring(i, i += 4), 16);
                        }
                    } catch (NumberFormatException e) {
                        throw getFormatException(s, i);
                    }
                }
            }
            builder.appendCodePoint(cp);
        }
        return builder.toString();
    }

    /**
     * Convert a string to a Java literal using the correct escape sequences.
     * The literal is not enclosed in double quotes. The result can be used in
     * properties files or in Java source code.
     *
     * @param s the text to convert
     * @return the Java representation
     */
    public static String javaEncode(String s) {
        StringBuilder buff = new StringBuilder(s.length());
        javaEncode(s, buff, false);
        return buff.toString();
    }

    /**
     * Convert a string to a Java literal using the correct escape sequences.
     * The literal is not enclosed in double quotes. The result can be used in
     * properties files or in Java source code.
     *
     * @param s the text to convert
     * @param buff the Java representation to return
     * @param forSQL true if we embedding this inside a STRINGDECODE SQL command
     */
    public static void javaEncode(String s, StringBuilder buff, boolean forSQL) {
        int length = s.length();
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i);
            switch (c) {
//            case '\b':
//                // BS backspace
//                // not supported in properties files
//                buff.append("\\b");
//                break;
            case '\t':
                // HT horizontal tab
                buff.append("\\t");
                break;
            case '\n':
                // LF linefeed
                buff.append("\\n");
                break;
            case '\f':
                // FF form feed
                buff.append("\\f");
                break;
            case '\r':
                // CR carriage return
                buff.append("\\r");
                break;
            case '"':
                // double quote
                buff.append("\\\"");
                break;
            case '\'':
                // quote:
                if (forSQL) {
                    buff.append('\'');
                }
                buff.append('\'');
                break;
            case '\\':
                // backslash
                buff.append("\\\\");
                break;
            default:
                if (c >= ' ' && (c < 0x80)) {
                    buff.append(c);
                // not supported in properties files
                // } else if (c < 0xff) {
                // buff.append("\\");
                // // make sure it's three characters (0x200 is octal 1000)
                // buff.append(Integer.toOctalString(0x200 | c).substring(1));
                } else {
                    buff.append("\\u")
                            .append(HEX[c >>> 12])
                            .append(HEX[c >>> 8 & 0xf])
                            .append(HEX[c >>> 4 & 0xf])
                            .append(HEX[c & 0xf]);
                }
            }
        }
    }

    /**
     * Add an asterisk ('[*]') at the given position. This format is used to
     * show where parsing failed in a statement.
     *
     * @param s the text
     * @param index the position
     * @return the text with asterisk
     */
    public static String addAsterisk(String s, int index) {
        if (s != null) {
            int len = s.length();
            index = Math.min(index, len);
            s = new StringBuilder(len + 3).append(s, 0, index).append("[*]").append(s, index, len).toString();
        }
        return s;
    }

    private static DbException getFormatException(String s, int i) {
        return DbException.get(ErrorCode.STRING_FORMAT_ERROR_1, addAsterisk(s, i));
    }

    /**
     * Decode a text that is encoded as a Java string literal. The Java
     * properties file format and Java source code format is supported.
     *
     * @param s the encoded string
     * @return the string
     */
    public static String javaDecode(String s) {
        int length = s.length();
        StringBuilder buff = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 >= s.length()) {
                    throw getFormatException(s, i);
                }
                c = s.charAt(++i);
                switch (c) {
                case 't':
                    buff.append('\t');
                    break;
                case 'r':
                    buff.append('\r');
                    break;
                case 'n':
                    buff.append('\n');
                    break;
                case 'b':
                    buff.append('\b');
                    break;
                case 'f':
                    buff.append('\f');
                    break;
                case '#':
                    // for properties files
                    buff.append('#');
                    break;
                case '=':
                    // for properties files
                    buff.append('=');
                    break;
                case ':':
                    // for properties files
                    buff.append(':');
                    break;
                case '"':
                    buff.append('"');
                    break;
                case '\\':
                    buff.append('\\');
                    break;
                case 'u': {
                    if (i + 4 >= length) {
                        throw getFormatException(s, i);
                    }
                    try {
                        c = (char) Integer.parseInt(s.substring(i + 1, i + 5), 16);
                    } catch (NumberFormatException e) {
                        throw getFormatException(s, i);
                    }
                    i += 4;
                    buff.append(c);
                    break;
                }
                default:
                    if (c >= '0' && c <= '9' && i + 2 < length) {
                        try {
                            c = (char) Integer.parseInt(s.substring(i, i + 3), 8);
                        } catch (NumberFormatException e) {
                            throw getFormatException(s, i);
                        }
                        i += 2;
                        buff.append(c);
                    } else {
                        throw getFormatException(s, i);
                    }
                }
            } else {
                buff.append(c);
            }
        }
        return buff.toString();
    }

    /**
     * Convert a string to the Java literal and enclose it with double quotes.
     * Null will result in "null" (without double quotes).
     *
     * @param s the text to convert
     * @return the Java representation
     */
    public static String quoteJavaString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(s.length() + 2).append('"');
        javaEncode(s, builder, false);
        return builder.append('"').toString();
    }

    /**
     * Convert a string array to the Java source code that represents this
     * array. Null will be converted to 'null'.
     *
     * @param array the string array
     * @return the Java source code (including new String[]{})
     */
    public static String quoteJavaStringArray(String[] array) {
        if (array == null) {
            return "null";
        }
        StringBuilder buff = new StringBuilder("new String[]{");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                buff.append(", ");
            }
            buff.append(quoteJavaString(array[i]));
        }
        return buff.append('}').toString();
    }

    /**
     * Convert an int array to the Java source code that represents this array.
     * Null will be converted to 'null'.
     *
     * @param array the int array
     * @return the Java source code (including new int[]{})
     */
    public static String quoteJavaIntArray(int[] array) {
        if (array == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("new int[]{");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(array[i]);
        }
        return builder.append('}').toString();
    }

    /**
     * Encode the string as a URL.
     *
     * @param s the string to encode
     * @return the encoded string
     */
    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // UnsupportedEncodingException
            throw DbException.convert(e);
        }
    }

    /**
     * Decode the URL to a string.
     *
     * @param encoded the encoded URL
     * @return the decoded string
     */
    public static String urlDecode(String encoded) {
        int length = encoded.length();
        byte[] buff = new byte[length];
        int j = 0;
        for (int i = 0; i < length; i++) {
            char ch = encoded.charAt(i);
            if (ch == '+') {
                buff[j++] = ' ';
            } else if (ch == '%') {
                buff[j++] = (byte) Integer.parseInt(encoded.substring(i + 1, i + 3), 16);
                i += 2;
            } else if (ch <= 127 && ch >= ' ') {
                buff[j++] = (byte) ch;
            } else {
                throw new IllegalArgumentException("Unexpected char " + (int) ch + " decoding " + encoded);
            }
        }
        return new String(buff, 0, j, StandardCharsets.UTF_8);
    }

    /**
     * Split a string into an array of strings using the given separator. A null
     * string will result in a null array, and an empty string in a zero element
     * array.
     *
     * @param s the string to split
     * @param separatorChar the separator character
     * @param trim whether each element should be trimmed
     * @return the array list
     */
    public static String[] arraySplit(String s, char separatorChar, boolean trim) {
        if (s == null) {
            return null;
        }
        int length = s.length();
        if (length == 0) {
            return new String[0];
        }
        ArrayList<String> list = Utils.newSmallArrayList();
        StringBuilder buff = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i);
            if (c == separatorChar) {
                String e = buff.toString();
                list.add(trim ? e.trim() : e);
                buff.setLength(0);
            } else if (c == '\\' && i < length - 1) {
                buff.append(s.charAt(++i));
            } else {
                buff.append(c);
            }
        }
        String e = buff.toString();
        list.add(trim ? e.trim() : e);
        return list.toArray(new String[0]);
    }

    /**
     * Combine an array of strings to one array using the given separator
     * character. A backslash and the separator character and escaped using a
     * backslash.
     *
     * @param list the string array
     * @param separatorChar the separator character
     * @return the combined string
     */
    public static String arrayCombine(String[] list, char separatorChar) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < list.length; i++) {
            if (i > 0) {
                builder.append(separatorChar);
            }
            String s = list[i];
            if (s == null) {
                continue;
            }
            for (int j = 0, length = s.length(); j < length; j++) {
                char c = s.charAt(j);
                if (c == '\\' || c == separatorChar) {
                    builder.append('\\');
                }
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * Creates an XML attribute of the form name="value".
     * A single space is prepended to the name,
     * so that multiple attributes can be concatenated.
     * @param name the attribute name
     * @param value the attribute value
     * @return the attribute
     */
    public static String xmlAttr(String name, String value) {
        return " " + name + "=\"" + xmlText(value) + "\"";
    }

    /**
     * Create an XML node with optional attributes and content.
     * The data is indented with 4 spaces if it contains a newline character.
     *
     * @param name the element name
     * @param attributes the attributes (may be null)
     * @param content the content (may be null)
     * @return the node
     */
    public static String xmlNode(String name, String attributes, String content) {
        return xmlNode(name, attributes, content, true);
    }

    /**
     * Create an XML node with optional attributes and content. The data is
     * indented with 4 spaces if it contains a newline character and the indent
     * parameter is set to true.
     *
     * @param name the element name
     * @param attributes the attributes (may be null)
     * @param content the content (may be null)
     * @param indent whether to indent the content if it contains a newline
     * @return the node
     */
    public static String xmlNode(String name, String attributes,
            String content, boolean indent) {
        StringBuilder builder = new StringBuilder();
        builder.append('<').append(name);
        if (attributes != null) {
            builder.append(attributes);
        }
        if (content == null) {
            builder.append("/>\n");
            return builder.toString();
        }
        builder.append('>');
        if (indent && content.indexOf('\n') >= 0) {
            builder.append('\n');
            indent(builder, content, 4, true);
        } else {
            builder.append(content);
        }
        builder.append("</").append(name).append(">\n");
        return builder.toString();
    }

    /**
     * Indents a string with spaces and appends it to a specified builder.
     *
     * @param builder string builder to append to
     * @param s the string
     * @param spaces the number of spaces
     * @param newline append a newline if there is none
     * @return the specified string builder
     */
    public static StringBuilder indent(StringBuilder builder, String s, int spaces, boolean newline) {
        for (int i = 0, length = s.length(); i < length;) {
            for (int j = 0; j < spaces; j++) {
                builder.append(' ');
            }
            int n = s.indexOf('\n', i);
            n = n < 0 ? length : n + 1;
            builder.append(s, i, n);
            i = n;
        }
        if (newline && !s.endsWith("\n")) {
            builder.append('\n');
        }
        return builder;
    }

    /**
     * Escapes a comment.
     * If the data contains '--', it is converted to '- -'.
     * The data is indented with 4 spaces if it contains a newline character.
     *
     * @param data the comment text
     * @return &lt;!-- data --&gt;
     */
    public static String xmlComment(String data) {
        int idx = 0;
        while (true) {
            idx = data.indexOf("--", idx);
            if (idx < 0) {
                break;
            }
            data = data.substring(0, idx + 1) + " " + data.substring(idx + 1);
        }
        // must have a space at the beginning and at the end,
        // otherwise the data must not contain '-' as the first/last character
        if (data.indexOf('\n') >= 0) {
            StringBuilder builder = new StringBuilder(data.length() + 18).append("<!--\n");
            return indent(builder, data, 4, true).append("-->\n").toString();
        }
        return "<!-- " + data + " -->\n";
    }

    /**
     * Converts the data to a CDATA element.
     * If the data contains ']]&gt;', it is escaped as a text element.
     *
     * @param data the text data
     * @return &lt;![CDATA[data]]&gt;
     */
    public static String xmlCData(String data) {
        if (data.contains("]]>")) {
            return xmlText(data);
        }
        boolean newline = data.endsWith("\n");
        data = "<![CDATA[" + data + "]]>";
        return newline ? data + "\n" : data;
    }

    /**
     * Returns &lt;?xml version="1.0"?&gt;
     * @return &lt;?xml version="1.0"?&gt;
     */
    public static String xmlStartDoc() {
        return "<?xml version=\"1.0\"?>\n";
    }

    /**
     * Escapes an XML text element.
     *
     * @param text the text data
     * @return the escaped text
     */
    public static String xmlText(String text) {
        return xmlText(text, false);
    }

    /**
     * Escapes an XML text element.
     *
     * @param text the text data
     * @param escapeNewline whether to escape newlines
     * @return the escaped text
     */
    public static String xmlText(String text, boolean escapeNewline) {
        int length = text.length();
        StringBuilder buff = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            switch (ch) {
            case '<':
                buff.append("&lt;");
                break;
            case '>':
                buff.append("&gt;");
                break;
            case '&':
                buff.append("&amp;");
                break;
            case '\'':
                // &apos; is not valid in HTML
                buff.append("&#39;");
                break;
            case '\"':
                buff.append("&quot;");
                break;
            case '\r':
            case '\n':
                if (escapeNewline) {
                    buff.append("&#x").
                        append(Integer.toHexString(ch)).
                        append(';');
                } else {
                    buff.append(ch);
                }
                break;
            case '\t':
                buff.append(ch);
                break;
            default:
                if (ch < ' ' || ch > 127) {
                    buff.append("&#x").
                        append(Integer.toHexString(ch)).
                        append(';');
                } else {
                    buff.append(ch);
                }
            }
        }
        return buff.toString();
    }

    /**
     * Replace all occurrences of the before string with the after string. Unlike
     * {@link String#replaceAll(String, String)} this method reads {@code before}
     * and {@code after} arguments as plain strings and if {@code before} argument
     * is an empty string this method returns original string {@code s}.
     *
     * @param s the string
     * @param before the old text
     * @param after the new text
     * @return the string with the before string replaced
     */
    public static String replaceAll(String s, String before, String after) {
        int next = s.indexOf(before);
        if (next < 0 || before.isEmpty()) {
            return s;
        }
        StringBuilder buff = new StringBuilder(
                s.length() - before.length() + after.length());
        int index = 0;
        while (true) {
            buff.append(s, index, next).append(after);
            index = next + before.length();
            next = s.indexOf(before, index);
            if (next < 0) {
                buff.append(s, index, s.length());
                break;
            }
        }
        return buff.toString();
    }

    /**
     * Enclose a string with double quotes. A double quote inside the string is
     * escaped using a double quote.
     *
     * @param s the text
     * @return the double quoted text
     */
    public static String quoteIdentifier(String s) {
        return quoteIdentifierOrLiteral(new StringBuilder(s.length() + 2), s, '"').toString();
    }

    /**
     * Enclose a string with double quotes and append it to the specified
     * string builder. A double quote inside the string is escaped using a
     * double quote.
     *
     * @param builder string builder to append to
     * @param s the text
     * @return the specified builder
     */
    public static StringBuilder quoteIdentifier(StringBuilder builder, String s) {
        return quoteIdentifierOrLiteral(builder, s, '"');
    }

    private static StringBuilder quoteIdentifierOrLiteral(StringBuilder builder, String s, char q) {
        int builderLength = builder.length();
        builder.append(q);
        for (int i = 0, l = s.length(); i < l;) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < ' ' || cp > 127) {
                // need to start from the beginning
                builder.setLength(builderLength);
                builder.append("U&").append(q);
                for (i = 0; i < l;) {
                    cp = s.codePointAt(i);
                    i += Character.charCount(cp);
                    if (cp >= ' ' && cp < 127) {
                        char ch = (char) cp;
                        if (ch == q || ch == '\\') {
                            builder.append(ch);
                        }
                        builder.append(ch);
                    } else if (cp <= 0xffff) {
                        appendHex(builder.append('\\'), cp, 2);
                    } else {
                        appendHex(builder.append("\\+"), cp, 3);
                    }
                }
                break;
            }
            if (cp == q) {
                builder.append(q);
            }
            builder.append((char) cp);
        }
        return builder.append(q);
    }

    /**
     * Check if a String is null or empty (the length is null).
     *
     * @param s the string to check
     * @return true if it is null or empty
     */
    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /**
     * Pad a string. This method is used for the SQL function RPAD and LPAD.
     *
     * @param string the original string
     * @param n the target length
     * @param padding the padding string
     * @param right true if the padding should be appended at the end
     * @return the padded string
     */
    public static String pad(String string, int n, String padding, boolean right) {
        if (n < 0) {
            n = 0;
        }
        if (n < string.length()) {
            return string.substring(0, n);
        } else if (n == string.length()) {
            return string;
        }
        int paddingChar;
        if (padding == null || padding.isEmpty()) {
            paddingChar = ' ';
        } else {
            paddingChar = padding.codePointAt(0);
        }
        StringBuilder buff = new StringBuilder(n);
        n -= string.length();
        if (Character.isSupplementaryCodePoint(paddingChar)) {
            n >>= 1;
        }
        if (right) {
            buff.append(string);
        }
        for (int i = 0; i < n; i++) {
            buff.appendCodePoint(paddingChar);
        }
        if (!right) {
            buff.append(string);
        }
        return buff.toString();
    }

    /**
     * Create a new char array and copy all the data. If the size of the byte
     * array is zero, the same array is returned.
     *
     * @param chars the char array (may be null)
     * @return a new char array
     */
    public static char[] cloneCharArray(char[] chars) {
        if (chars == null) {
            return null;
        }
        int len = chars.length;
        if (len == 0) {
            return chars;
        }
        return Arrays.copyOf(chars, len);
    }

    /**
     * Trim a character from a string.
     *
     * @param s the string
     * @param leading if leading characters should be removed
     * @param trailing if trailing characters should be removed
     * @param characters what to remove or {@code null} for a space
     * @return the trimmed string
     */
    public static String trim(String s, boolean leading, boolean trailing, String characters) {
        if (characters == null || characters.isEmpty()) {
            return trim(s, leading, trailing, ' ');
        }
        int length = characters.length();
        if (length == 1) {
            return trim(s, leading, trailing, characters.charAt(0));
        }
        IntPredicate test;
        int count = characters.codePointCount(0, length);
        check: if (count <= 2) {
            int cp = characters.codePointAt(0);
            if (count > 1) {
                int cp2 = characters.codePointAt(Character.charCount(cp));
                if (cp != cp2) {
                    test = value -> value == cp || value == cp2;
                    break check;
                }
            }
            test = value -> value == cp;
        } else {
            HashSet<Integer> set = new HashSet<>();
            characters.codePoints().forEach(set::add);
            test = set::contains;
        }
        return trim(s, leading, trailing, test);
    }

    private static String trim(String s, boolean leading, boolean trailing, IntPredicate test) {
        int begin = 0, end = s.length();
        if (leading) {
            int cp;
            while (begin < end && test.test(cp = s.codePointAt(begin))) {
                begin += Character.charCount(cp);
            }
        }
        if (trailing) {
            int cp;
            while (end > begin && test.test(cp = s.codePointBefore(end))) {
                end -= Character.charCount(cp);
            }
        }
        // substring() returns self if start == 0 && end == length()
        return s.substring(begin, end);
    }

    /**
     * Trim a character from a string.
     *
     * @param s the string
     * @param leading if leading characters should be removed
     * @param trailing if trailing characters should be removed
     * @param character what to remove
     * @return the trimmed string
     */
    public static String trim(String s, boolean leading, boolean trailing, char character) {
        int begin = 0, end = s.length();
        if (leading) {
            while (begin < end && s.charAt(begin) == character) {
                begin++;
            }
        }
        if (trailing) {
            while (end > begin && s.charAt(end - 1) == character) {
                end--;
            }
        }
        // substring() returns self if start == 0 && end == length()
        return s.substring(begin, end);
    }

    /**
     * Trim a whitespace from a substring. Equivalent of
     * {@code substring(beginIndex).trim()}.
     *
     * @param s the string
     * @param beginIndex start index of substring (inclusive)
     * @return trimmed substring
     */
    public static String trimSubstring(String s, int beginIndex) {
        return trimSubstring(s, beginIndex, s.length());
    }

    /**
     * Trim a whitespace from a substring. Equivalent of
     * {@code substring(beginIndex, endIndex).trim()}.
     *
     * @param s the string
     * @param beginIndex start index of substring (inclusive)
     * @param endIndex end index of substring (exclusive)
     * @return trimmed substring
     */
    public static String trimSubstring(String s, int beginIndex, int endIndex) {
        while (beginIndex < endIndex && s.charAt(beginIndex) <= ' ') {
            beginIndex++;
        }
        while (beginIndex < endIndex && s.charAt(endIndex - 1) <= ' ') {
            endIndex--;
        }
        return s.substring(beginIndex, endIndex);
    }

    /**
     * Trim a whitespace from a substring and append it to a specified string
     * builder. Equivalent of
     * {@code builder.append(substring(beginIndex, endIndex).trim())}.
     *
     * @param builder string builder to append to
     * @param s the string
     * @param beginIndex start index of substring (inclusive)
     * @param endIndex end index of substring (exclusive)
     * @return the specified builder
     */
    public static StringBuilder trimSubstring(StringBuilder builder, String s, int beginIndex, int endIndex) {
        while (beginIndex < endIndex && s.charAt(beginIndex) <= ' ') {
            beginIndex++;
        }
        while (beginIndex < endIndex && s.charAt(endIndex - 1) <= ' ') {
            endIndex--;
        }
        return builder.append(s, beginIndex, endIndex);
    }

    /**
     * Truncates the specified string to the specified length. This method,
     * unlike {@link String#substring(int, int)}, doesn't break Unicode code
     * points. If the specified length in characters breaks a valid pair of
     * surrogates, the whole pair is not included into result.
     *
     * @param s
     *            the string to truncate
     * @param maximumLength
     *            the maximum length in characters
     * @return the specified string if it isn't longer than the specified
     *         maximum length, and the truncated string otherwise
     */
    public static String truncateString(String s, int maximumLength) {
        if (s.length() > maximumLength) {
            s = maximumLength > 0 ? s.substring(0,
                    Character.isSurrogatePair(s.charAt(maximumLength - 1), s.charAt(maximumLength)) ? maximumLength - 1
                            : maximumLength)
                    : "";
        }
        return s;
    }

    /**
     * Get the string from the cache if possible. If the string has not been
     * found, it is added to the cache. If there is such a string in the cache,
     * that one is returned.
     *
     * @param s the original string
     * @return a string with the same content, if possible from the cache
     */
    public static String cache(String s) {
        if (!SysProperties.OBJECT_CACHE) {
            return s;
        }
        if (s == null) {
            return s;
        } else if (s.isEmpty()) {
            return "";
        }
        String[] cache = getCache();
        if (cache != null) {
            int hash = s.hashCode();
            int index = hash & (SysProperties.OBJECT_CACHE_SIZE - 1);
            String cached = cache[index];
            if (s.equals(cached)) {
                return cached;
            }
            cache[index] = s;
        }
        return s;
    }

    /**
     * Clear the cache. This method is used for testing.
     */
    public static void clearCache() {
        softCache = null;
    }

    /**
     * Parses an unsigned 31-bit integer. Neither - nor + signs are allowed.
     *
     * @param s string to parse
     * @param start the beginning index, inclusive
     * @param end the ending index, exclusive
     * @return the unsigned {@code int} not greater than {@link Integer#MAX_VALUE}.
     */
    public static int parseUInt31(String s, int start, int end) {
        if (end > s.length() || start < 0 || start > end) {
            throw new IndexOutOfBoundsException();
        }
        if (start == end) {
            throw new NumberFormatException("");
        }
        int result = 0;
        for (int i = start; i < end; i++) {
            char ch = s.charAt(i);
            // Ensure that character is valid and that multiplication by 10 will
            // be performed without overflow
            if (ch < '0' || ch > '9' || result > 214_748_364) {
                throw new NumberFormatException(s.substring(start, end));
            }
            result = result * 10 + ch - '0';
            if (result < 0) {
                // Overflow
                throw new NumberFormatException(s.substring(start, end));
            }
        }
        return result;
    }

    /**
     * Convert a hex encoded string to a byte array.
     *
     * @param s the hex encoded string
     * @return the byte array
     */
    public static byte[] convertHexToBytes(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            throw DbException.get(ErrorCode.HEX_STRING_ODD_1, s);
        }
        len /= 2;
        byte[] buff = new byte[len];
        int mask = 0;
        int[] hex = HEX_DECODE;
        try {
            for (int i = 0; i < len; i++) {
                int d = hex[s.charAt(i + i)] << 4 | hex[s.charAt(i + i + 1)];
                mask |= d;
                buff[i] = (byte) d;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw DbException.get(ErrorCode.HEX_STRING_WRONG_1, s);
        }
        if ((mask & ~255) != 0) {
            throw DbException.get(ErrorCode.HEX_STRING_WRONG_1, s);
        }
        return buff;
    }

    /**
     * Parses a hex encoded string with possible space separators and appends
     * the decoded binary string to the specified output stream.
     *
     * @param baos the output stream, or {@code null}
     * @param s the hex encoded string
     * @param start the start index
     * @param end the end index, exclusive
     * @return the specified output stream or a new output stream
     */
    public static ByteArrayOutputStream convertHexWithSpacesToBytes(ByteArrayOutputStream baos, String s, int start,
            int end) {
        if (baos == null) {
            baos = new ByteArrayOutputStream((end - start) >>> 1);
        }
        int mask = 0;
        int[] hex = HEX_DECODE;
        try {
            loop: for (int i = start;;) {
                char c1, c2;
                do {
                    if (i >= end) {
                        break loop;
                    }
                    c1 = s.charAt(i++);
                } while (c1 == ' ');
                do {
                    if (i >= end) {
                        if (((mask | hex[c1]) & ~255) != 0) {
                            throw getHexStringException(ErrorCode.HEX_STRING_WRONG_1, s, start, end);
                        }
                        throw getHexStringException(ErrorCode.HEX_STRING_ODD_1, s, start, end);
                    }
                    c2 = s.charAt(i++);
                } while (c2 == ' ');
                int d = hex[c1] << 4 | hex[c2];
                mask |= d;
                baos.write(d);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw getHexStringException(ErrorCode.HEX_STRING_WRONG_1, s, start, end);
        }
        if ((mask & ~255) != 0) {
            throw getHexStringException(ErrorCode.HEX_STRING_WRONG_1, s, start, end);
        }
        return baos;
    }

    private static DbException getHexStringException(int code, String s, int start, int end) {
        return DbException.get(code, s.substring(start, end));
    }

    /**
     * Convert a byte array to a hex encoded string.
     *
     * @param value the byte array
     * @return the hex encoded string
     */
    public static String convertBytesToHex(byte[] value) {
        return convertBytesToHex(value, value.length);
    }

    /**
     * Convert a byte array to a hex encoded string.
     *
     * @param value the byte array
     * @param len the number of bytes to encode
     * @return the hex encoded string
     */
    public static String convertBytesToHex(byte[] value, int len) {
        byte[] bytes = new byte[len * 2];
        char[] hex = HEX;
        for (int i = 0, j = 0; i < len; i++) {
            int c = value[i] & 0xff;
            bytes[j++] = (byte) hex[c >> 4];
            bytes[j++] = (byte) hex[c & 0xf];
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * Convert a byte array to a hex encoded string and appends it to a specified string builder.
     *
     * @param builder string builder to append to
     * @param value the byte array
     * @return the hex encoded string
     */
    public static StringBuilder convertBytesToHex(StringBuilder builder, byte[] value) {
        return convertBytesToHex(builder, value, value.length);
    }

    /**
     * Convert a byte array to a hex encoded string and appends it to a specified string builder.
     *
     * @param builder string builder to append to
     * @param value the byte array
     * @param len the number of bytes to encode
     * @return the hex encoded string
     */
    public static StringBuilder convertBytesToHex(StringBuilder builder, byte[] value, int len) {
        char[] hex = HEX;
        for (int i = 0; i < len; i++) {
            int c = value[i] & 0xff;
            builder.append(hex[c >>> 4]).append(hex[c & 0xf]);
        }
        return builder;
    }

    /**
     * Appends specified number of trailing bytes from unsigned long value to a
     * specified string builder.
     *
     * @param builder
     *            string builder to append to
     * @param x
     *            value to append
     * @param bytes
     *            number of bytes to append
     * @return the specified string builder
     */
    public static StringBuilder appendHex(StringBuilder builder, long x, int bytes) {
        char[] hex = HEX;
        for (int i = bytes * 8; i > 0;) {
            builder.append(hex[(int) (x >> (i -= 4)) & 0xf]).append(hex[(int) (x >> (i -= 4)) & 0xf]);
        }
        return builder;
    }

    /**
     * Check if this string is a decimal number.
     *
     * @param s the string
     * @return true if it is
     */
    public static boolean isNumber(String s) {
        int l = s.length();
        if (l == 0) {
            return false;
        }
        for (int i = 0; i < l; i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the specified string is empty or contains only whitespace.
     *
     * @param s
     *            the string
     * @return whether the specified string is empty or contains only whitespace
     */
    public static boolean isWhitespaceOrEmpty(String s) {
        for (int i = 0, l = s.length(); i < l; i++) {
            if (s.charAt(i) > ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Append a zero-padded number from 00 to 99 to a string builder.
     *
     * @param builder the string builder
     * @param positiveValue the number to append
     * @return the specified string builder
     */
    public static StringBuilder appendTwoDigits(StringBuilder builder, int positiveValue) {
        if (positiveValue < 10) {
            builder.append('0');
        }
        return builder.append(positiveValue);
    }

    /**
     * Append a zero-padded number to a string builder.
     *
     * @param builder the string builder
     * @param length the number of characters to append
     * @param positiveValue the number to append
     * @return the specified string builder
     */
    public static StringBuilder appendZeroPadded(StringBuilder builder, int length, int positiveValue) {
        String s = Integer.toString(positiveValue);
        length -= s.length();
        for (; length > 0; length--) {
            builder.append('0');
        }
        return builder.append(s);
    }

    /**
     * Appends the specified string or its part to the specified builder with
     * maximum builder length limit.
     *
     * @param builder the string builder
     * @param s the string to append
     * @param length the length limit
     * @return the specified string builder
     */
    public static StringBuilder appendToLength(StringBuilder builder, String s, int length) {
        int builderLength = builder.length();
        if (builderLength < length) {
            int need = length - builderLength;
            if (need >= s.length()) {
                builder.append(s);
            } else {
                builder.append(s, 0, need);
            }
        }
        return builder;
    }

    /**
     * Escape table or schema patterns used for DatabaseMetaData functions.
     *
     * @param pattern the pattern
     * @return the escaped pattern
     */
    public static String escapeMetaDataPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return pattern;
        }
        return replaceAll(pattern, "\\", "\\\\");
    }

    /**
     * Case-sensitive check if a {@param text} starts with a {@param prefix}.
     * It only calls {@code String.startsWith()} and is only here for API consistency
     *
     * @param text the full text starting with a prefix
     * @param prefix the full text starting with a prefix
     * @return TRUE only if text starts with the prefix
     */
    public static boolean startsWith(String text, String prefix) {
        return text.startsWith(prefix);
    }

    /**
     * Case-Insensitive check if a {@param text} starts with a {@param prefix}.
     *
     * @param text the full text starting with a prefix
     * @param prefix the full text starting with a prefix
     * @return TRUE only if text starts with the prefix
     */
    public static boolean startsWithIgnoringCase(String text, String prefix) {
        if (text.length() < prefix.length()) {
            return false;
        } else {
            Collator collator = Collator.getInstance();
            collator.setStrength(Collator.PRIMARY);
            return collator.equals(text.substring(0, prefix.length()), prefix);
        }
    }
}
