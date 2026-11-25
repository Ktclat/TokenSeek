package io.tokenseek.app;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formats selectable native text, preserving unsupported Markdown as plain text. */
final class NativeChatMarkdown {
    private static final Pattern INLINE = Pattern.compile(
            "`([^`\n]+)`|\\*\\*([^*\n]+)\\*\\*|__([^_\n]+)__|\\[([^\\]\n]+)\\]\\((https?://[^ )\n]+)\\)");
    private static final int EXCLUSIVE = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

    static CharSequence render(String source, int codeFill) {
        if (source.isEmpty()) return source;
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean code = false;
        int start = 0;
        for (String line : source.split("\n", -1)) {
            if (line.startsWith("```")) {
                code = !code;
                if (!code && out.length() > start) {
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(codeFill), start, out.length(), EXCLUSIVE);
                } else start = out.length();
                continue;
            }
            if (code) {
                out.append(line).append('\n');
                continue;
            }
            int header = 0;
            while (header < Math.min(6,line.length()) && line.charAt(header) == '#') header++;
            boolean heading = header > 0 && header < line.length() && line.charAt(header) == ' ';
            boolean bullet = line.startsWith("- ") || line.startsWith("* ");
            int at = out.length();
            inline(out, line.substring(heading ? header + 1 : bullet ? 2 : 0), codeFill);
            if (heading && out.length() > at) {
                out.setSpan(new StyleSpan(Typeface.BOLD),at,out.length(),EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(header == 1 ? 1.24f : 1.1f),at,out.length(),EXCLUSIVE);
            }
            out.append('\n');
            if (bullet) out.setSpan(new BulletSpan(10),at,out.length(),EXCLUSIVE);
        }
        if (out.length() > 0) out.delete(out.length()-1,out.length());
        if (code && out.length() > start) out.setSpan(new TypefaceSpan("monospace"),start,out.length(),EXCLUSIVE);
        return out;
    }

    private static void inline(SpannableStringBuilder out, String line, int codeFill) {
        Matcher match = INLINE.matcher(line);
        int end = 0;
        while (match.find()) {
            out.append(line,end,match.start());
            int at = out.length();
            if (match.group(1) != null) {
                out.append(match.group(1));
                out.setSpan(new TypefaceSpan("monospace"),at,out.length(),EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeFill),at,out.length(),EXCLUSIVE);
            } else if (match.group(4) != null) {
                out.append(match.group(4));
                out.setSpan(new URLSpan(match.group(5)),at,out.length(),EXCLUSIVE);
            } else {
                out.append(match.group(2) != null ? match.group(2) : match.group(3));
                out.setSpan(new StyleSpan(Typeface.BOLD),at,out.length(),EXCLUSIVE);
            }
            end = match.end();
        }
        out.append(line,end,line.length());
    }

    private NativeChatMarkdown() {}
}
