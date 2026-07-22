package com.helix.decision.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dependency-free conversion of an already-assembled artifact HTML body (a credit
 * proposal or a generated document) into office document formats:
 *
 * <ul>
 *   <li><b>RTF</b> ({@code {\rtf1…}}) — Word-openable; headings/paragraphs/lists/tables
 *       reproduced as styled RTF paragraphs.</li>
 *   <li><b>SpreadsheetML 2003 XML</b> ({@code <?xml…?><Workbook xmlns=
 *       "urn:schemas-microsoft-com:office:spreadsheet">…}) — Excel-openable; every HTML
 *       {@code <table>} becomes a worksheet block. Needs no zip/OOXML library.</li>
 *   <li><b>CSV</b> — the tabular content, with the same OWASP CSV-injection guard used by
 *       the origination charge-Excel export.</li>
 * </ul>
 *
 * <p>Every method is a pure function: deterministic (no wall-clock, no randomness), no I/O
 * and no mutation of any source artifact. The stored artifact HTML is the single source of
 * truth — no figure is recomputed here.
 *
 * <p><b>Escaping discipline.</b> Real HTML tags are always stripped <em>before</em> the
 * HTML entities are un-escaped, so already-escaped free text (e.g. {@code &lt;script&gt;})
 * can never be re-introduced as live markup. Each target format then re-encodes for its own
 * grammar: RTF control-word escaping (backslash/braces + {@code \\uN} for non-ASCII),
 * XML entity escaping (with non-ASCII emitted as numeric char refs so the body is pure
 * ASCII and charset-agnostic), and the CSV formula-injection guard.
 */
final class OfficeRenderer {

    private OfficeRenderer() {
    }

    // ------------------------------------------------------------------ table model

    /** A parsed HTML table row: its cell text plus whether it was a header row ({@code <th>}). */
    record RowModel(List<String> cells, boolean header) {
    }

    /** A parsed HTML table: an ordered list of rows. */
    record TableModel(List<RowModel> rows) {
    }

    private static final Pattern TABLE =
            Pattern.compile("<table\\b[^>]*>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW =
            Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL =
            Pattern.compile("<(t[hd])\\b[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** Extract every {@code <table>} in document order into a light row/cell model. */
    static List<TableModel> extractTables(String html) {
        List<TableModel> out = new ArrayList<>();
        if (html == null) return out;
        Matcher tm = TABLE.matcher(html);
        while (tm.find()) {
            List<RowModel> rows = new ArrayList<>();
            Matcher rm = ROW.matcher(tm.group(1));
            while (rm.find()) {
                List<String> cells = new ArrayList<>();
                boolean header = false;
                Matcher cm = CELL.matcher(rm.group(1));
                while (cm.find()) {
                    if ("th".equalsIgnoreCase(cm.group(1))) header = true;
                    cells.add(cellText(cm.group(2)));
                }
                if (!cells.isEmpty()) rows.add(new RowModel(cells, header));
            }
            if (!rows.isEmpty()) out.add(new TableModel(rows));
        }
        return out;
    }

    private static String cellText(String inner) {
        // Strip inner markup FIRST, then un-escape entities (never re-introduces live markup).
        String noTags = inner.replaceAll("(?s)<[^>]*>", " ");
        return unescapeHtml(noTags).replaceAll("\\s+", " ").trim();
    }

    /**
     * Plain-text lines of the document, in order (block-level breaks preserved). Used as the
     * graceful fallback for the CSV / SpreadsheetML exports when the artifact carries no
     * {@code <table>} (e.g. a prose facility agreement) so those formats still return the
     * document's content rather than an empty file.
     */
    static List<String> textLines(String html) {
        if (html == null) return List.of();
        String s = html.replaceAll("(?i)</(h1|h2|h3|p|li|tr|div|section|ul|ol|table|footer)>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "• ");
        s = s.replaceAll("(?i)</t[hd]>", " ");
        s = s.replaceAll("(?s)<[^>]*>", "");
        s = unescapeHtml(s);
        List<String> out = new ArrayList<>();
        for (String ln : s.split("\n")) {
            String t = ln.replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ CSV

    static String csv(String html) {
        StringBuilder sb = new StringBuilder();
        List<TableModel> tables = extractTables(html);
        if (tables.isEmpty()) {
            for (String line : textLines(html)) sb.append(csvCell(line)).append("\r\n");
            return sb.toString();
        }
        boolean first = true;
        for (TableModel t : tables) {
            if (!first) sb.append("\r\n");   // blank line between distinct tables
            first = false;
            for (RowModel r : t.rows()) {
                for (int i = 0; i < r.cells().size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(csvCell(r.cells().get(i)));
                }
                sb.append("\r\n");
            }
        }
        return sb.toString();
    }

    /**
     * CSV field encoding with the OWASP formula-injection guard (mirrors the origination
     * charge-Excel export): a field that would otherwise begin with {@code = + - @}, TAB or CR
     * is prefixed with an apostrophe so Excel / Sheets / LibreOffice do not evaluate it as a
     * formula, even inside a quoted field; the field is then RFC-4180 quoted if it contains a
     * comma, quote or newline.
     */
    private static String csvCell(String s) {
        if (s == null) return "";
        String safe = s;
        if (!safe.isEmpty()) {
            char first = safe.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r') {
                safe = "'" + safe;
            }
        }
        boolean needs = safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r");
        String escaped = safe.replace("\"", "\"\"");
        return needs ? "\"" + escaped + "\"" : escaped;
    }

    // ------------------------------------------------------------------ SpreadsheetML 2003 XML

    /**
     * SpreadsheetML 2003 XML workbook. All cells are emitted as {@code ss:Type="String"} —
     * SpreadsheetML only treats a cell as a formula when it carries an {@code ss:Formula}
     * attribute, so string cells are inherently immune to spreadsheet-formula injection. Every
     * value is XML-escaped and non-ASCII is emitted as a numeric character reference, so the
     * body is pure ASCII and independent of the transport charset.
     */
    static String spreadsheetXml(String sheetName, String title, String html) {
        List<TableModel> tables = extractTables(html);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<?mso-application progid=\"Excel.Sheet\"?>\n");
        sb.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"")
                .append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"")
                .append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"")
                .append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n");
        sb.append("<Worksheet ss:Name=\"").append(sheetName(sheetName)).append("\">\n<Table>\n");
        if (title != null && !title.isBlank()) {
            sb.append(oneCellRow(title));
            sb.append("<Row/>\n");
        }
        if (tables.isEmpty()) {
            for (String line : textLines(html)) sb.append(oneCellRow(line));
        } else {
            boolean first = true;
            for (TableModel t : tables) {
                if (!first) sb.append("<Row/>\n");   // spacer row between tables
                first = false;
                for (RowModel r : t.rows()) {
                    sb.append("<Row>");
                    for (String c : r.cells()) sb.append(cellXml(c));
                    sb.append("</Row>\n");
                }
            }
        }
        sb.append("</Table>\n</Worksheet>\n</Workbook>\n");
        return sb.toString();
    }

    private static String oneCellRow(String v) {
        return "<Row>" + cellXml(v) + "</Row>\n";
    }

    private static String cellXml(String v) {
        return "<Cell><Data ss:Type=\"String\">" + xmlText(v) + "</Data></Cell>";
    }

    /** Excel worksheet names cannot contain {@code : \ / ? * [ ]} and are capped at 31 chars. */
    private static String sheetName(String s) {
        String name = (s == null || s.isBlank()) ? "Sheet1" : s.replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        if (name.length() > 31) name = name.substring(0, 31);
        return xmlAttr(name);
    }

    private static String xmlAttr(String s) {
        return xmlText(s).replace("\"", "&quot;");
    }

    /** XML text escaping; non-ASCII becomes a numeric char ref, control chars become spaces. */
    private static String xmlText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> {
                    if (c < 0x20) {
                        sb.append(' ');
                    } else if (c < 0x80) {
                        sb.append(c);
                    } else {
                        sb.append("&#").append((int) c).append(';');
                    }
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ RTF

    // Sentinel control chars used while linearising the HTML; none occur in artifact text.
    private static final char BR = '\u0001';
    private static final char H1 = '\u0002';
    private static final char H2 = '\u0003';
    private static final char BOLD_ON = '\u0004';
    private static final char BOLD_OFF = '\u0005';
    private static final char IT_ON = '\u0006';
    private static final char IT_OFF = '\u0007';

    /**
     * A Word-openable RTF rendering of the artifact. A deterministic Helix letterhead is
     * followed by the artifact body linearised into styled paragraphs (headings bold/larger,
     * bullets, table rows as tab-separated paragraphs). Non-ASCII is emitted as {@code \\uN}
     * with {@code \\uc0} so no fallback byte is required — the whole file is ASCII.
     */
    static String rtf(String title, String html) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\deff0\\uc0\n");
        sb.append("{\\fonttbl{\\f0\\fswiss\\fcharset0 Helvetica;}}\n");
        sb.append("\\viewkind4\\f0\n");
        sb.append("{\\pard\\sa40\\b\\fs30 HELIX BANK\\par}\n");
        sb.append("{\\pard\\sa160\\i\\fs16 Governed AI for wholesale credit \\u8226  ")
                .append("AI where it helps \\u8226  humans where regulation demands \\u8226  ")
                .append("deterministic figures throughout\\par}\n");
        String body = rtfBody(html);
        if (body.isEmpty() && title != null && !title.isBlank()) {
            body = "{\\pard\\sa120\\b\\fs32 " + rtfEscape(title) + "\\par}\n";
        }
        sb.append(body);
        sb.append("}");
        return sb.toString();
    }

    private static String rtfBody(String html) {
        if (html == null) return "";
        String s = html;
        // Scrub any pre-existing control characters from the source so free text can never be
        // misread as one of our private structural sentinels (-) below. Keep the
        // ordinary whitespace controls (\t \n \r); drop the rest of the C0 range.
        s = s.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]", "");
        s = s.replaceAll("(?i)<h1[^>]*>", BR + "" + H1);
        s = s.replaceAll("(?i)</h1>", String.valueOf(BR));
        s = s.replaceAll("(?i)<h2[^>]*>", BR + "" + H2);
        s = s.replaceAll("(?i)</h2>", String.valueOf(BR));
        s = s.replaceAll("(?i)<h3[^>]*>", BR + "" + H2);
        s = s.replaceAll("(?i)</h3>", String.valueOf(BR));
        s = s.replaceAll("(?i)<li[^>]*>", BR + "• ");
        s = s.replaceAll("(?i)</t[hd]>", "\t");
        s = s.replaceAll("(?i)</tr>", String.valueOf(BR));
        s = s.replaceAll("(?i)</p>", String.valueOf(BR));
        s = s.replaceAll("(?i)</ul>", String.valueOf(BR));
        s = s.replaceAll("(?i)</ol>", String.valueOf(BR));
        s = s.replaceAll("(?i)</table>", String.valueOf(BR));
        s = s.replaceAll("(?i)</section>", String.valueOf(BR));
        s = s.replaceAll("(?i)<br\\s*/?>", String.valueOf(BR));
        s = s.replaceAll("(?i)<(b|strong)>", String.valueOf(BOLD_ON));
        s = s.replaceAll("(?i)</(b|strong)>", String.valueOf(BOLD_OFF));
        s = s.replaceAll("(?i)<(i|em)>", String.valueOf(IT_ON));
        s = s.replaceAll("(?i)</(i|em)>", String.valueOf(IT_OFF));
        // Strip any remaining real tags, THEN un-escape entities (keeps escaped text inert).
        s = s.replaceAll("(?s)<[^>]*>", "");
        s = unescapeHtml(s);

        StringBuilder out = new StringBuilder();
        for (String segment : s.split(String.valueOf(BR))) {
            int level = 0;
            if (segment.indexOf(H1) >= 0) level = 1;
            else if (segment.indexOf(H2) >= 0) level = 2;
            StringBuilder inner = new StringBuilder();
            boolean visible = false;
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                switch (c) {
                    case H1, H2 -> { /* level marker — drop */ }
                    case BOLD_ON -> inner.append("{\\b ");
                    case BOLD_OFF, IT_OFF -> inner.append('}');
                    case IT_ON -> inner.append("{\\i ");
                    case '\t' -> inner.append("\\tab ");
                    case '\\' -> { inner.append("\\\\"); visible = true; }
                    case '{' -> { inner.append("\\{"); visible = true; }
                    case '}' -> { inner.append("\\}"); visible = true; }
                    case '\n', '\r' -> inner.append(' ');
                    default -> {
                        if (c < 0x20) {
                            // stray control char — drop
                        } else if (c < 0x80) {
                            inner.append(c);
                            if (c != ' ') visible = true;
                        } else {
                            int code = c;
                            if (code > 32767) code -= 65536;
                            inner.append("\\u").append(code).append(' ');
                            visible = true;
                        }
                    }
                }
            }
            if (!visible) continue;
            String content = inner.toString().trim();
            switch (level) {
                case 1 -> out.append("{\\pard\\sa140\\b\\fs32 ").append(content).append("\\par}\n");
                case 2 -> out.append("{\\pard\\sa90\\b\\fs26 ").append(content).append("\\par}\n");
                default -> out.append("{\\pard\\sa60\\fs20 ").append(content).append("\\par}\n");
            }
        }
        return out.toString();
    }

    /** RTF-escape a plain string (used only for the title fallback). */
    private static String rtfEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '{' -> sb.append("\\{");
                case '}' -> sb.append("\\}");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> {
                    if (c < 0x20) {
                        /* drop */
                    } else if (c < 0x80) {
                        sb.append(c);
                    } else {
                        int code = c;
                        if (code > 32767) code -= 65536;
                        sb.append("\\u").append(code).append(' ');
                    }
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ HTML entities

    /** Un-escape the small set of HTML entities the artifact renderers emit, plus numeric refs. */
    static String unescapeHtml(String s) {
        if (s == null) return "";
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i + 1);
                if (semi > i && semi - i <= 12) {
                    String rep = entity(s.substring(i + 1, semi));
                    if (rep != null) {
                        sb.append(rep);
                        i = semi + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String entity(String ent) {
        switch (ent) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return " ";
            case "middot": return "·";
            case "rarr": return "→";
            case "larr": return "←";
            case "ne": return "≠";
            case "ge": return "≥";
            case "le": return "≤";
            case "times": return "×";
            case "hellip": return "…";
            case "mdash": return "—";
            case "ndash": return "–";
            case "star": return "★";
            case "check": return "✓";
            default:
                try {
                    if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        return codePoint(Integer.parseInt(ent.substring(2), 16));
                    }
                    if (ent.startsWith("#")) {
                        return codePoint(Integer.parseInt(ent.substring(1)));
                    }
                } catch (NumberFormatException ignored) {
                    return null;
                }
                return null;
        }
    }

    private static String codePoint(int cp) {
        if (cp <= 0 || cp > 0x10FFFF) return null;
        return new String(Character.toChars(cp));
    }
}
