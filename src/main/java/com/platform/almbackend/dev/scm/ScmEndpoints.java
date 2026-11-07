package com.platform.almbackend.dev.scm;

import com.platform.almbackend.domain.RepositoryWebhook;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 저장소 URL → API 주소, 그리고 SSRF 가드. 폴링은 관리자가 넣은 주소로 서버가 HTTP를 보내는 기능이라, "어디로든 보내는 프록시"가
 * 되지 않게 <b>등록한 저장소 URL의 호스트(또는 api.&lt;호스트&gt;)만</b> API 주소로 받는다. 사내 Git(사설 IP)은 이 기능의 본래 대상이라
 * 사설 대역은 막지 않는다 — 대신 호스트를 저장소 등록과 묶는다.
 *
 * <ul>
 *   <li>github.com → {@code https://api.github.com}, 그 밖의 GitHub(Enterprise Server) → {@code <오리진>/api/v3}</li>
 *   <li>GitLab(.com·self-managed) → {@code <오리진>/api/v4}</li>
 * </ul>
 */
public final class ScmEndpoints {
    private ScmEndpoints() {}

    /** 제공자를 비워 보냈을 때 — 잘 알려진 호스트만 짐작한다. 나머지는 관리자가 고른다 */
    public static String guessProvider(String repositoryUrl) {
        String host = URI.create(repositoryUrl).getHost().toLowerCase(Locale.ROOT);
        if (host.equals("github.com")) return RepositoryWebhook.GITHUB;
        if (host.equals("gitlab.com")) return RepositoryWebhook.GITLAB;
        return null;
    }

    public static String defaultApiBase(String provider, String repositoryUrl) {
        URI repo = URI.create(repositoryUrl);
        String origin = origin(repo);
        if (RepositoryWebhook.GITHUB.equals(provider)) {
            return repo.getHost().equalsIgnoreCase("github.com") ? "https://api.github.com" : origin + "/api/v3";
        }
        return origin + "/api/v4";
    }

    /**
     * 관리자가 넣은 API 주소 — http(s)·계정 정보 없음·질의/조각 없음, 호스트는 저장소 호스트 또는 api.&lt;저장소 호스트&gt;.
     * 끝 {@code /}는 지운다.
     */
    public static String validateApiBase(String raw, String repositoryUrl) {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("API 주소 형식이 올바르지 않습니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new IllegalArgumentException("API 주소는 http(s) 주소여야 합니다");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("API 주소에 계정 정보·질의·조각을 넣을 수 없습니다");
        }
        String repoHost = URI.create(repositoryUrl).getHost().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals(repoHost) && !host.equals("api." + repoHost)) {
            throw new IllegalArgumentException("API 주소의 호스트는 저장소 호스트(" + repoHost + ")여야 합니다");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String normalized = scheme + "://" + host + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + path;
        if (normalized.length() > 300) throw new IllegalArgumentException("API 주소가 너무 깁니다(300자 이하)");
        return normalized;
    }

    /** 요청을 보내도 되는 유일한 대상 — scheme://host[:port] */
    public static String allowedOrigin(String apiBase) {
        return origin(URI.create(apiBase));
    }

    /** owner/repo(GitLab은 하위 그룹까지) — 저장소 URL 경로에서 앞 / 를 뗀 것 */
    public static String repositoryPath(String repositoryUrl) {
        return URI.create(repositoryUrl).getRawPath().substring(1);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port < 0 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
