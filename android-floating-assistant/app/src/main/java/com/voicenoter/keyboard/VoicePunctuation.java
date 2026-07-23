package com.voicenoter.keyboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts spoken punctuation phrases in transcripts into real symbols.
 */
public final class VoicePunctuation {
    private VoicePunctuation() {}

    private static final String[][] RULES = {
        // Bangla phrases
        {"পূর্ণচ্ছেদ", "।"},
        {"পূর্ণ ছেদ", "।"},
        {"দণ্ড", "।"},
        {"ডট", "."},
        {"প্রশ্নবোধক", "؟"},
        {"প্রশ্ন চিহ্ন", "?"},
        {"প্রশ্নবোধক চিহ্ন", "?"},
        {"বিস্ময়সূচক", "!"},
        {"বিস্ময়সূচক", "!"},
        {"কমা", ","},
        {"সেমিকোলন", ";"},
        {"কোলন", ":"},
        {"নতুন লাইন", "\n"},
        {"নিউ লাইন", "\n"},
        {"লাইন ব্রেক", "\n"},
        {"স্পেস", " "},
        {"হাইফেন", "-"},
        {"ড্যাশ", "—"},
        // English phrases
        {"full stop", "."},
        {"period", "."},
        {"question mark", "?"},
        {"exclamation mark", "!"},
        {"exclamation point", "!"},
        {"comma", ","},
        {"semicolon", ";"},
        {"colon", ":"},
        {"new line", "\n"},
        {"newline", "\n"},
        {"line break", "\n"},
        {"open quote", "\""},
        {"close quote", "\""},
        {"apostrophe", "'"},
        {"hyphen", "-"},
        {"dash", "—"},
    };

    public static String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        for (String[] rule : RULES) {
            String phrase = rule[0];
            String replacement = rule[1];
            Pattern p = Pattern.compile(
                "(?i)(?<!\\p{L})" + Pattern.quote(phrase) + "(?!\\p{L})"
            );
            Matcher m = p.matcher(result);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(sb);
            result = sb.toString();
        }
        // Tidy spaces before punctuation
        result = result.replaceAll(" +([.,!?;:।؟])", "$1");
        result = result.replaceAll(" *\\n *", "\n");
        return result;
    }

    public static String normalizeLangHint(String layoutLang) {
        if (layoutLang == null) return "";
        return layoutLang.toLowerCase(Locale.US);
    }
}
