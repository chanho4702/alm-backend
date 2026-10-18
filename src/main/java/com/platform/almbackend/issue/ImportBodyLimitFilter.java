package com.platform.almbackend.issue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * CSV 가져오기 본문 크기 상한(감사 A-14) — 항목 수 상한({@code IssueImportRequest.MAX_ITEMS})만으로는 항목마다
 * 설명 5만 자를 채운 요청이 수백 MB까지 커질 수 있다. JSON을 다 읽어 들이기 전에 끊는다.
 *
 * <p>Content-Length가 있으면 바로 413 {"error"}(공통 오류 계약). 없으면(chunked) 읽은 바이트를 세다가 넘는 순간
 * 읽기를 실패시킨다 — 그 경우는 본문 파싱 실패(400)로 끝난다.
 */
@Component
public class ImportBodyLimitFilter extends OncePerRequestFilter {
    private static final String PATTERN = "/api/alm/projects/*/issues/import";
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final long maxBytes;

    public ImportBodyLimitFilter(@Value("${platform.alm.import.max-body-size:20MB}") DataSize maxBodySize) {
        this.maxBytes = maxBodySize.toBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !PATHS.match(PATTERN, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length > maxBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"가져오기 파일은 " + DataSize.ofBytes(maxBytes).toMegabytes()
                    + "MB까지 올릴 수 있습니다. 나눠서 가져오세요\"}");
            return;
        }
        chain.doFilter(length >= 0 ? request : new Counting(request, maxBytes), response);
    }

    /** 길이를 모르는 본문 — 읽은 만큼 세다가 상한을 넘으면 IOException */
    private static final class Counting extends HttpServletRequestWrapper {
        private final long maxBytes;

        Counting(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) count(1);
                    return b;
                }

                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    if (n > 0) count(n);
                    return n;
                }

                private void count(long n) throws IOException {
                    read += n;
                    if (read > maxBytes) throw new IOException("가져오기 본문이 상한을 넘었습니다");
                }

                @Override public boolean isFinished() { return in.isFinished(); }
                @Override public boolean isReady() { return in.isReady(); }
                @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
            };
        }
    }
}
