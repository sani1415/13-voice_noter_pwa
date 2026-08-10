package com.voicenoter.keyboard;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Prefix-completion dictionary for the suggestion bar, backed by a plain-text
 * asset holding one word per line ordered by corpus frequency (most common
 * first). Lookup is a binary search over a lexicographically sorted copy,
 * ranked by the original frequency order.
 */
final class BanglaDictionary {
    private final String[] sortedWords;
    private final int[] frequencyRank;

    private BanglaDictionary(String[] sortedWords, int[] frequencyRank) {
        this.sortedWords = sortedWords;
        this.frequencyRank = frequencyRank;
    }

    static BanglaDictionary load(Context context) throws IOException {
        List<String> byFrequency = new ArrayList<>(30000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("dict_bn.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) byFrequency.add(line);
            }
        }
        Integer[] order = new Integer[byFrequency.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> byFrequency.get(a).compareTo(byFrequency.get(b)));
        String[] sorted = new String[order.length];
        int[] rank = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            sorted[i] = byFrequency.get(order[i]);
            rank[i] = order[i];
        }
        return new BanglaDictionary(sorted, rank);
    }

    /** Most frequent words starting with {@code prefix}, excluding the prefix itself. */
    List<String> suggest(String prefix, int max) {
        List<String> results = new ArrayList<>(max);
        if (prefix == null || prefix.isEmpty()) return results;
        int[] bestRank = new int[max];
        String[] bestWord = new String[max];
        int found = 0;
        for (int i = lowerBound(prefix); i < sortedWords.length; i++) {
            String word = sortedWords[i];
            if (!word.startsWith(prefix)) break;
            if (word.length() == prefix.length()) continue;
            int rank = frequencyRank[i];
            int pos = found < max ? found : max - 1;
            if (found >= max && rank >= bestRank[pos]) continue;
            while (pos > 0 && rank < bestRank[pos - 1]) {
                bestRank[pos] = bestRank[pos - 1];
                bestWord[pos] = bestWord[pos - 1];
                pos--;
            }
            bestRank[pos] = rank;
            bestWord[pos] = word;
            if (found < max) found++;
        }
        for (int i = 0; i < found; i++) results.add(bestWord[i]);
        return results;
    }

    private int lowerBound(String prefix) {
        int lo = 0;
        int hi = sortedWords.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sortedWords[mid].compareTo(prefix) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
