package com.tricrotism.uworldguard.service;

import com.tricrotism.uworldguard.integration.ReportedVersion;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Announces a newer release in the console at startup, and says nothing at all otherwise, an
 * up-to-date server, an unreachable network and a malformed response are all silent, because a
 * version check is never important enough to add noise to a clean startup.
 *
 * <p>Runs on the async scheduler and touches no Bukkit API, so a slow or hanging endpoint delays
 * nothing; the plugin is fully enabled before the request is even sent.
 */
@NullMarked
public final class UpdateChecker {

    private static final String RELEASES_URL =
        "https://api.github.com/repos/tricrotism/uWorldGuard/releases/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int SUMMARY_LIMIT = 120;

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final String BODY_KEY = "\"body\"";
    /**
     * Splits a version into its numeric parts, so {@code v1.0.10} and {@code 1.0.10} compare equal and
     * both sort above {@code 1.0.9}, which a plain string comparison gets backwards.
     */
    private static final Pattern NOT_DIGITS = Pattern.compile("[^0-9]+");

    private final Plugin plugin;

    public UpdateChecker(final Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        plugin.getServer().getAsyncScheduler().runNow(plugin, _ -> {
            try {
                run();
            } catch (final Throwable t) {
                plugin.getLogger().log(Level.FINE, "Update check failed", t);
            }
        });
    }

    private void run() {
        final String current = ReportedVersion.real(plugin);
        final String body = fetch();
        if (body == null) {
            return;
        }

        final Matcher tag = TAG.matcher(body);
        if (!tag.find()) {
            return;
        }
        final String latest = tag.group(1).trim();
        if (compare(latest, current) <= 0) {
            return;
        }

        plugin.getLogger().info("Update available: " + latest + ", you are running " + current + "!");
        final String summary = summaryOf(body, tag.end());
        if (!summary.isEmpty()) {
            plugin.getLogger().info("  " + summary);
        }
    }

    /**
     * A one-line description of the release: its title, or the first real line of its changelog when
     * the title is just the version number again — which is the common case, and would otherwise print
     * a line that only repeats what the notice above it already said.
     *
     * @param from where the tag match ended, so the first {@code "name"} found belongs to the release
     *             itself rather than to an asset further down the document
     */
    private static String summaryOf(final String body, final int from) {
        final Matcher name = NAME.matcher(body);
        if (name.find(from)) {
            final String title = summarise(name.group(1));
            if (informative(title)) {
                return title;
            }
        }
        final String changelog = rawBody(body, from);
        if (changelog != null) {
            for (final String line : unescape(changelog).split("\n")) {
                final String cleaned = line.replaceAll("^[#*\\-\\s]+", "").trim();
                if (informative(cleaned)) {
                    return summarise(cleaned);
                }
            }
        }
        return "";
    }

    /**
     * Reads the changelog string by scanning rather than by pattern. A JSON string that permits escaped
     * quotes needs an alternation such as {@code (?:[^"\\]|\\.)*} to express, and Java matches a
     * repeated <em>group</em> by recursion — on a changelog of any real length that overflows the
     * stack, which is not a way for a version check to take a server's startup thread down with it.
     * Scanning forward is linear and exact, and cannot run out of stack however long the release notes.
     *
     * @return the raw (still escaped) body, or {@code null} if the document has none
     */
    private static @Nullable String rawBody(final String json, final int from) {
        final int key = json.indexOf(BODY_KEY, from);
        if (key < 0) {
            return null;
        }

        final int open = json.indexOf('"', key + BODY_KEY.length());
        if (open < 0) {
            return null;
        }
        for (int i = open + 1; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return json.substring(open + 1, i);
            }
        }
        return null;
    }

    /**
     * Whether the text says anything a version number has not. A title of "1.0.6" carries no
     * information next to the line above it; anything with a letter in it does.
     */
    private static boolean informative(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private @Nullable String fetch() {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            final HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_URL))
                .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", plugin.getName() + "/" + ReportedVersion.real(plugin))
                .timeout(TIMEOUT)
                .GET()
                .build();
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final Exception e) {
            plugin.getLogger().log(Level.FINE, "Update check failed", e);
            return null;
        }
    }

    /**
     * Undoes the JSON string escapes that a title or changelog realistically carries. Enough to read a
     * line by; this is a console notice, not a parser.
     */
    private static String unescape(final String raw) {
        return raw.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
            .replace("\\\"", "\"").replace("\\t", " ").replace("\\\\", "\\");
    }

    /**
     * Trims to one tidy console line, cutting at a word boundary when it has to cut at all.
     */
    private static String summarise(final String raw) {
        final String text = unescape(raw).trim();
        if (text.length() <= SUMMARY_LIMIT) {
            return text;
        }
        final String cut = text.substring(0, SUMMARY_LIMIT);
        final int space = cut.lastIndexOf(' ');
        return (space > 0 ? cut.substring(0, space) : cut) + "…";
    }

    /**
     * Compares dotted numeric versions. Positive when {@code a} is newer. A component that is missing
     * counts as zero, so {@code 1.1} and {@code 1.1.0} are the same version.
     */
    static int compare(final String a, final String b) {
        final String[] left = NOT_DIGITS.split(strip(a));
        final String[] right = NOT_DIGITS.split(strip(b));
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            final int result = Integer.compare(part(left, i), part(right, i));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    private static String strip(final String version) {
        final String trimmed = version.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
    }

    private static int part(final String[] parts, final int index) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
