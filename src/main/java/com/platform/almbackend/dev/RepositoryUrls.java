package com.platform.almbackend.dev;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 저장소 URL 정규화(V41) — 같은 저장소는 같은 문자열이어야 커밋 멱등·등록 대조가 맞는다.
 * {@code https://GitHub.com/Owner/Repo.git/} → {@code https://github.com/Owner/Repo}. SSH 형식
 * {@code git@github.com:owner/repo.git}은 https로 읽는다. 계정 정보(토큰)가 든 URL은 거부한다 — 기록에 비밀이 남는다.
 * 경로 대소문자는 그대로 둔다(호스팅마다 다르다).
 */
public final class RepositoryUrls {

    public static final int MAX_URL = 300;
    private static final Pattern SCP_LIKE = Pattern.compile("^[A-Za-z0-9._-]+@([A-Za-z0-9.-]+):(.+)$");

    private RepositoryUrls() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("저장소 URL을 입력하세요");
        String value = raw.trim();
        Matcher scp = SCP_LIKE.matcher(value);
        if (scp.matches() && !value.contains("://")) value = "https://" + scp.group(1) + "/" + scp.group(2);
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("저장소 URL 형식이 올바르지 않습니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new IllegalArgumentException("저장소 URL은 http(s) 주소여야 합니다 (예: https://github.com/owner/repo)");
        }
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("저장소 URL에 계정 정보를 넣을 수 없습니다");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("저장소 URL에 질의·조각(?·#)을 넣을 수 없습니다");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.toLowerCase(Locale.ROOT).endsWith(".git")) path = path.substring(0, path.length() - 4);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String[] segments = path.isEmpty() ? new String[0] : path.substring(1).split("/");
        if (segments.length < 2) throw new IllegalArgumentException("저장소 URL은 소유자/저장소까지 있어야 합니다 (예: https://github.com/owner/repo)");
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("저장소 URL 경로가 올바르지 않습니다");
            }
        }
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String normalized = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port + path;
        if (normalized.length() > MAX_URL) throw new IllegalArgumentException("저장소 URL이 너무 깁니다(" + MAX_URL + "자 이하)");
        return normalized;
    }

    /** 표시 이름 기본값 — 경로 마지막 두 조각(owner/repo) */
    public static String defaultName(String normalizedUrl) {
        String path = URI.create(normalizedUrl).getRawPath();
        String[] segments = path.substring(1).split("/");
        String name = segments[segments.length - 2] + "/" + segments[segments.length - 1];
        return name.length() > 100 ? name.substring(name.length() - 100) : name;
    }

    /** 커밋 링크 — http(s)만, 계정 정보 금지, 500자 이하 */
    public static String commitUrl(String raw) {
        return webUrl(raw, "커밋");
    }

    /** 웹 링크(커밋·PR) — http(s)만, 계정 정보 금지, 500자 이하. {@code what}은 오류 문구의 주어 */
    public static String webUrl(String raw, String what) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(what + " URL이 비었습니다");
        String value = raw.trim();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(what + " URL 형식이 올바르지 않습니다");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(what + " URL은 계정 정보 없는 http(s) 주소여야 합니다");
        }
        if (value.length() > 500) throw new IllegalArgumentException(what + " URL이 너무 깁니다(500자 이하)");
        return value;
    }
}
