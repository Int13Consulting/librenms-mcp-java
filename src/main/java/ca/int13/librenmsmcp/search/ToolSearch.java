package ca.int13.librenmsmcp.search;

import ca.int13.librenmsmcp.librenms.PythonText;
import ca.int13.librenmsmcp.tools.ToolDefinition;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/**
 * Tool search, as FastMCP's {@code BM25SearchTransform} and {@code RegexSearchTransform}: same
 * searchable text, tokenizer, BM25 parameters and tie order, so rankings are identical.
 */
public final class ToolSearch {
    public enum Strategy {
        BM25,
        REGEX
    }

    static final int MAX_PATTERN_LENGTH = 1000;
    static final long REGEX_BUDGET_NANOS = 1_000_000_000L;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final Strategy strategy;
    private final int maxResults;

    public ToolSearch(Strategy strategy, int maxResults) {
        this.strategy = strategy;
        this.maxResults = maxResults;
    }

    public Strategy strategy() {
        return strategy;
    }

    /** The best matches among {@code tools} (in served order), at most {@code maxResults}. */
    public <T> List<T> search(List<T> tools, java.util.function.Function<T, ToolDefinition> definition, String input) {
        List<String> texts =
                tools.stream().map(t -> searchableText(definition.apply(t))).toList();
        List<Integer> hits = strategy == Strategy.BM25 ? bm25(texts, input) : regex(texts, input);
        return hits.stream().map(tools::get).toList();
    }

    /** FastMCP {@code _extract_searchable_text}: name, description, then each parameter's name and description. */
    static String searchableText(ToolDefinition d) {
        List<String> parts = new ArrayList<>();
        parts.add(d.name());
        if (d.description() != null && !d.description().isEmpty()) {
            parts.add(d.description());
        }
        JsonNode props = d.inputSchema() == null ? null : d.inputSchema().get("properties");
        if (props != null) {
            for (Map.Entry<String, JsonNode> p : props.properties()) {
                parts.add(p.getKey());
                JsonNode desc = p.getValue().isObject() ? p.getValue().get("description") : null;
                if (desc != null && desc.isString() && !desc.stringValue().isEmpty()) {
                    parts.add(desc.stringValue());
                }
            }
        }
        return String.join(" ", parts);
    }

    /** NFKC, casefold, runs of two or more letters/digits (Python {@code [^\W_]{2,}}). */
    static List<String> tokenize(String text) {
        Matcher m = TOKEN.matcher(PythonText.casefold(Normalizer.normalize(text, Normalizer.Form.NFKC)));
        List<String> tokens = new ArrayList<>();
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    private List<Integer> bm25(List<String> documents, String query) {
        int n = documents.size();
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || n == 0) {
            return List.of();
        }
        List<Map<String, Integer>> tf = new ArrayList<>();
        int[] lengths = new int[n];
        Map<String, Integer> df = new HashMap<>();
        long total = 0;
        for (int i = 0; i < n; i++) {
            List<String> tokens = tokenize(documents.get(i));
            lengths[i] = tokens.size();
            total += tokens.size();
            Map<String, Integer> counts = new HashMap<>();
            for (String t : tokens) {
                if (counts.merge(t, 1, Integer::sum) == 1) {
                    df.merge(t, 1, Integer::sum);
                }
            }
            tf.add(counts);
        }
        double avgDl = (double) total / n;
        double[] scores = new double[n];
        for (String token : queryTokens) {
            Integer d = df.get(token);
            if (d == null) {
                continue;
            }
            double idf = Math.log((n - d + 0.5) / (d + 0.5) + 1.0);
            for (int i = 0; i < n; i++) {
                int f = tf.get(i).getOrDefault(token, 0);
                if (f == 0) {
                    continue;
                }
                double numerator = f * (K1 + 1);
                double denominator = f + K1 * (1 - B + B * lengths[i] / avgDl);
                scores[i] += idf * numerator / denominator;
            }
        }
        // Python's sorted(..., reverse=True) is stable: equal scores keep served order.
        return IntStream.range(0, n)
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed())
                .limit(maxResults)
                .filter(i -> scores[i] > 0)
                .toList();
    }

    private List<Integer> regex(List<String> documents, String pattern) {
        Pattern compiled = compileLikeRust(pattern);
        if (compiled == null) {
            return List.of();
        }
        long deadline = System.nanoTime() + REGEX_BUDGET_NANOS;
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < documents.size() && matches.size() < maxResults; i++) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                if (compiled.matcher(new DeadlineCharSequence(documents.get(i), deadline))
                        .find()) {
                    matches.add(i);
                }
            } catch (DeadlineCharSequence.Expired e) {
                break; // like FastMCP, return the partial matches
            }
        }
        return matches;
    }

    /**
     * Compiles a pattern case-insensitively, refusing what FastMCP's Rust engine refuses
     * (lookarounds, backreferences) and Java-only constructs, so the same patterns match.
     */
    static Pattern compileLikeRust(String pattern) {
        if (pattern.length() > MAX_PATTERN_LENGTH
                || pattern.matches("(?s).*(\\(\\?<?[=!]|\\(\\?>|\\\\[1-9]|\\\\k<|[*+?}][+]).*")) {
            return null;
        }
        try {
            return Pattern.compile(pattern.replace("(?P<", "(?<"), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Fails a regex match that runs past the deadline (Java regexes can backtrack exponentially). */
    static final class DeadlineCharSequence implements CharSequence {
        static final class Expired extends RuntimeException {
            Expired() {
                super(null, null, false, false);
            }
        }

        private final CharSequence inner;
        private final long deadline;

        DeadlineCharSequence(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new Expired();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
