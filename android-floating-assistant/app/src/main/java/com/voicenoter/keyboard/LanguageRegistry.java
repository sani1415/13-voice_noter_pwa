package com.voicenoter.keyboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One language source for settings, Record prompts, and Soniox Live hints. */
public final class LanguageRegistry {
    public static final String AUTO = "auto";
    public static final String DEFAULT_QUICK = "auto,bn,en,ar,hi,es";

    public static final class Language {
        public final String code;
        public final String englishName;
        public final String nativeName;

        Language(String code, String englishName, String nativeName) {
            this.code = code;
            this.englishName = englishName;
            this.nativeName = nativeName;
        }

        public String displayName() {
            return nativeName.equals(englishName) ? englishName : nativeName + " — " + englishName;
        }

        public String shortLabel() {
            return AUTO.equals(code) ? "AUTO" : code.toUpperCase(Locale.US);
        }
    }

    private static Language lang(String code, String english, String nativeName) {
        return new Language(code, english, nativeName);
    }

    private static final List<Language> ALL = Collections.unmodifiableList(Arrays.asList(
        lang(AUTO, "Auto detect", "Auto detect"),
        lang("af", "Afrikaans", "Afrikaans"),
        lang("ar", "Arabic", "العربية"),
        lang("bn", "Bengali", "বাংলা"),
        lang("bg", "Bulgarian", "Български"),
        lang("zh", "Chinese", "中文"),
        lang("hr", "Croatian", "Hrvatski"),
        lang("cs", "Czech", "Čeština"),
        lang("da", "Danish", "Dansk"),
        lang("nl", "Dutch", "Nederlands"),
        lang("en", "English", "English"),
        lang("et", "Estonian", "Eesti"),
        lang("fi", "Finnish", "Suomi"),
        lang("fr", "French", "Français"),
        lang("de", "German", "Deutsch"),
        lang("el", "Greek", "Ελληνικά"),
        lang("he", "Hebrew", "עברית"),
        lang("hi", "Hindi", "हिन्दी"),
        lang("hu", "Hungarian", "Magyar"),
        lang("id", "Indonesian", "Bahasa Indonesia"),
        lang("it", "Italian", "Italiano"),
        lang("ja", "Japanese", "日本語"),
        lang("ko", "Korean", "한국어"),
        lang("lv", "Latvian", "Latviešu"),
        lang("lt", "Lithuanian", "Lietuvių"),
        lang("no", "Norwegian", "Norsk"),
        lang("pl", "Polish", "Polski"),
        lang("pt", "Portuguese", "Português"),
        lang("ro", "Romanian", "Română"),
        lang("ru", "Russian", "Русский"),
        lang("sr", "Serbian", "Српски"),
        lang("sk", "Slovak", "Slovenčina"),
        lang("sl", "Slovenian", "Slovenščina"),
        lang("es", "Spanish", "Español"),
        lang("sw", "Swahili", "Kiswahili"),
        lang("sv", "Swedish", "Svenska"),
        lang("th", "Thai", "ไทย"),
        lang("tr", "Turkish", "Türkçe"),
        lang("uk", "Ukrainian", "Українська"),
        lang("vi", "Vietnamese", "Tiếng Việt")
    ));

    private LanguageRegistry() { }

    public static List<Language> all() { return ALL; }

    public static Language find(String code) {
        if (code != null) {
            for (Language language : ALL) {
                if (language.code.equalsIgnoreCase(code)) return language;
            }
        }
        return find("bn");
    }

    public static boolean supports(String code) {
        if (code == null) return false;
        for (Language language : ALL) if (language.code.equalsIgnoreCase(code)) return true;
        return false;
    }

    public static List<String> parseQuick(String raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (String value : raw.split(",")) {
                String code = value.trim().toLowerCase(Locale.US);
                if (supports(code) && !result.contains(code)) result.add(code);
            }
        }
        if (result.isEmpty()) result.addAll(Arrays.asList(DEFAULT_QUICK.split(",")));
        return result;
    }
}
