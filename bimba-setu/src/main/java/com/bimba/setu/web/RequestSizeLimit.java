package com.bimba.setu.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Refuses request bodies over the limit before anything reads them (the endpoints read the whole body into a String
 * and check its size afterwards, so without this an anonymous POST of any size, e.g. to /system/bimba-agent-enroll,
 * would be held in memory). A declared Content-Length over the limit gets 413 at once; a body without one (chunked)
 * fails while it is read once it passes the limit. The limits are well above the endpoints' own (256 KB, 4 MB for the
 * tag registry import), so they only stop what those would refuse anyway.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimit extends OncePerRequestFilter {
    static final long DEFAULT_LIMIT = 1024 * 1024;
    static final long IMPORT_LIMIT = 5 * 1024 * 1024;

    static long limitFor(String path) {
        return path != null && path.endsWith("/registry/import") ? IMPORT_LIMIT : DEFAULT_LIMIT;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitFor(req.getRequestURI());
        long declared = req.getContentLengthLong();
        if (declared > limit) {
            resp.setStatus(413);
            resp.setHeader("Cache-Control", "no-store");
            resp.setHeader("Connection", "close");
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"The request body is larger than " + limit / 1024 + " KB.\",\"code\":\"body\"}");
            return;
        }
        chain.doFilter(declared < 0 ? new Limited(req, limit) : req, resp);
    }

    /** thrown while reading a body without Content-Length that passes the limit (ApiErrors answers 413) */
    public static final class TooLarge extends IOException {
        TooLarge(long limit) {
            super("The request body is larger than " + limit / 1024 + " KB.");
        }
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream in;

        Limited(HttpServletRequest req, long limit) {
            super(req);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (in == null) {
                ServletInputStream raw = super.getInputStream();
                in = new ServletInputStream() {
                    private long read;

                    private int count(int n) throws TooLarge {
                        if (n > 0 && (read += n) > limit) {
                            throw new TooLarge(limit);
                        }
                        return n;
                    }

                    @Override
                    public int read() throws IOException {
                        int b = raw.read();
                        if (b >= 0) {
                            count(1);
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        return count(raw.read(b, off, len));
                    }

                    @Override
                    public boolean isFinished() {
                        return raw.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return raw.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        raw.setReadListener(listener);
                    }
                };
            }
            return in;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
            return new BufferedReader(new InputStreamReader(getInputStream(), cs));
        }
    }
}
