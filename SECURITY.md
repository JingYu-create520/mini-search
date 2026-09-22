# Security

## What this software is

mini-search is a search engine that runs on one machine, holds one index on disk, and has no user
accounts, no tokens and no multi-tenant story. It is built to be read top to bottom by one person,
not to be a shared service. Every statement below follows from that.

## Supported versions

| version | supported |
|---|---|
| 0.1.x | yes |
| anything older | fix-forward: reproduce on the current `main` first |

## The boundary, stated plainly

`serve` binds `127.0.0.1` by default. The write endpoints (`POST /api/index`, `DELETE /api/index`,
`POST /api/crawl`) and `GET /api/search` are **unauthenticated**, because on your own laptop that is
the right trade and an auth layer nobody asked for would be a worse one.

That trade stops being yours the moment the socket is reachable from elsewhere. `--host 0.0.0.0` does
that directly, and so does publishing the container port on an interface other than loopback
(`docker-compose.yml` maps `127.0.0.1:9200:9200` and the container's own `0.0.0.0` is only there so the
host can reach it through that mapping). Anyone who can reach the port can:

- rewrite the index and delete documents;
- make the process fetch a URL of their choosing, within the target policy below;
- read back whatever that fetch returned, because the response body is indexed and `/api/search`
  echoes it;
- hand it documents to store, up to the request cap.

So: exposing this service is a decision you make, not a default it makes for you. If you need it
reachable, put it behind something that authenticates, and treat everything in the index as public to
whoever can reach it.

## What the code does about it

**Loopback by default, with a warning.** Starting on another interface prints a line saying the write
endpoints have no authentication. Read it; it is the only notice you will get.

**The crawler refuses private targets by default.** `crawl`, `index http://…` and `POST /api/crawl`
check the target *before* any request goes out, including the `robots.txt` lookup: a scheme other than
http(s), a hostname that does not resolve, or any address in loopback, `0.0.0.0/8`, link-local
(which is where cloud metadata services live, `169.254.169.254`), the RFC 1918 private ranges,
carrier-grade NAT (`100.64.0.0/10`), IPv6 unique-local (`fc00::/7`) or multicast is refused with a
reason in the result rather than fetched. A public page that redirects into one of those ranges is
refused after the redirect too, so its content is not indexed or echoed.

`--allow-private` (CLI) and `--allow-private-crawls` (server) turn the check off for the process. They
are deliberately not per-request: a flag an attacker can set with the same request they are abusing
protects nothing.

**Nothing here is a dependency.** There is no third-party code in the runtime jar, so the supply-chain
surface of the thing you are running is the JDK plus the source in this repository. That is also why
adding a dependency is treated as a design change, not a convenience.

**Writes are capped.** `POST /api/index` and `POST /api/crawl` refuse a body over 8 MiB with a 413
instead of reading it, so an unauthenticated write endpoint is not also an unbounded memory grant.
`topK` and `from` are clamped rather than trusted, and `search` never materialises more than its
candidate pool.

**A bad request still gets an answer.** The hand-written JSON parser caps nesting at 96 levels — before
that, a 120 KB body of sixty thousand open brackets produced a `StackOverflowError`, which is an `Error`
and so walked straight past the error handling on every path. And each route is wrapped: a truncated
body, a stray bracket or an unexpected internal failure now returns a 400/500 with the reason instead of
closing the socket silently, which from a client looks exactly like a server that is down.

## Known residual risks

These are open, not overlooked.

1. **DNS rebinding.** The target is resolved and checked before the request and again on the final URL
   after redirects, but the name-to-address mapping is not pinned for the connection. A hostname with
   two records and a short TTL -- one public, one internal -- can still get a request out. Mitigation:
   do not expose the server, or keep the crawler pointed at names you control.
2. **Crawling something private is a data path, not a read-only probe.** With `--allow-private`, a
   successful fetch is indexed and searchable. That is the feature when you are ingesting your own
   wiki, and an exfiltration channel when someone else can call `/api/crawl`.
3. **No TLS and no rate limiting on the HTTP API.** Request bodies are capped at 8 MiB and paging
   parameters are clamped, but there is no per-client throttle and nothing is encrypted. Loopback on a
   laptop is the intended setting; anything else needs a proxy you control.
4. **Memory is sized by the JVM, not by the application.** The whole snapshot is loaded at startup;
   50k documents used about 1.9 GB of heap in the committed benchmark. `docker-compose.yml` puts a
   1 GB ceiling on it on purpose (`MS_XMX` overrides), which means a large crawl inside the container
   is expected to hit that ceiling and die rather than consume the machine — a fact about the cap, not
   a mitigation for a hostile input. Running the jar directly inherits the JVM's default instead.
5. **The snapshot file is trusted.** `index.msnap` carries per-section CRC32s, which catch truncation
   and corruption; they are not a signature. Anyone who can write that file can put arbitrary text in
   your index. That is the same trust level as the corpus directory or `--dict`.
6. **The crawler sends a User-Agent that names this project.** That is the polite-crawler contract,
   and it is also information about you leaking to whatever you point it at. Use your own UA string
   if that matters for your target.

## Reporting a vulnerability

Prefer GitHub's **Security → Report a vulnerability** on
[the repository](https://github.com/JingYu-create520/mini-search) (private vulnerability report). If
that is not enabled on the repo, open a normal issue with as little detail as still lets it be
reproduced, and we will move it somewhere private. Disclosure timing is yours to set; nothing here is a
service anyone depends on, so a public fix is usually fine.

Reports that amount to "it has no authentication" or "it can crawl the internal network once you hand
it the flag that lets it crawl the internal network" are already documented above and are not
vulnerabilities -- but if you think the *default* can be beaten while staying the default, that is
exactly the report worth sending.
