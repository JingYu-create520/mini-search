package dev.jingyu.ms.crawl;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Which crawl targets this process is allowed to reach.
 *
 * <p>The reason this exists is the combination of two things that are each fine on their own: the
 * write endpoints are unauthenticated (on a laptop, that is the right trade), and
 * {@code serve --host 0.0.0.0} plus the Docker image put those endpoints on a network. A peer that
 * can post to {@code /api/crawl} can otherwise make the server fetch {@code 127.0.0.1}, the
 * {@code 169.254.169.254} metadata service, or an internal admin panel, and then read the body back
 * out of the index through {@code /api/search}. That is a server-side request forgery with a built-in
 * exfiltration path, and "it is only a local tool" stops being true the moment someone binds a port.
 *
 * <p>So by default a target must name a http(s) host that resolves to a public address. Crawling
 * something internal is a legitimate thing to want, which is what {@code --allow-private} (CLI) and
 * {@code --allow-private-crawls} (server) are for; they are opt-in per process rather than per
 * request, because a flag the attacker can set by the same request they are abusing protects nothing.
 *
 * <p>What this does not do: it checks the address before the request and again on the final URL after
 * redirects, but it cannot pin the name-to-address mapping for the duration of the connection, so a
 * host with a short TTL and two records -- one public, one internal -- can still slip through
 * (DNS rebinding). The residual risk and the mitigation (run the server on loopback) are in SECURITY.md.
 */
public final class UrlPolicy {

    /** Decision with a human-readable reason, which is what ends up in {@code CrawlResult.reason}. */
    public record Decision(boolean allowed, String reason) {
        static Decision ok() { return new Decision(true, ""); }

        static Decision no(String why) { return new Decision(false, why); }
    }

    /** Production default: public addresses only. */
    public static final UrlPolicy STANDARD = new UrlPolicy(false);

    /** For the CLI crawl of a wiki on the LAN, for tests against a local HTTP server, and for --allow-private. */
    public static final UrlPolicy ALLOW_PRIVATE = new UrlPolicy(true);

    private final boolean allowPrivate;

    public UrlPolicy(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public boolean allowsPrivate() {
        return allowPrivate;
    }

    /** Check a URL before any request goes out. No HTTP; a hostname does get resolved. */
    public Decision check(String url) {
        URI u;
        try {
            u = URI.create(url);
        } catch (RuntimeException e) {
            return Decision.no("malformed url");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Decision.no("only http(s) targets are crawled");
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) return Decision.no("url has no host");
        if (allowPrivate) return Decision.ok();

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Unresolved is refused rather than fetched-and-hoped: a name that does not resolve is
            // not evidence of a safe target, and the crawler has its own error path for this.
            return Decision.no("host does not resolve: " + host);
        }
        if (addresses.length == 0) return Decision.no("host resolved to nothing: " + host);
        for (InetAddress a : addresses) {
            String why = privateReason(a);
            if (why != null) return Decision.no(why + " (" + host + " -> " + a.getHostAddress() + ")");
        }
        return Decision.ok();
    }

    /** Null means "this address is fine to crawl". */
    static String privateReason(InetAddress a) {
        if (a.isLoopbackAddress()) return "loopback target";
        if (a.isAnyLocalAddress()) return "unspecified address";
        if (a.isLinkLocalAddress()) return "link-local target (cloud metadata lives here)";
        if (a.isSiteLocalAddress()) return "private network target";
        if (a.isMulticastAddress()) return "multicast target";
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) return "IPv6 unique local address";
        if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return "carrier-grade NAT range";
        if (b.length == 4 && (b[0] & 0xFF) == 0) return "0.0.0.0/8";
        return null;
    }
}
