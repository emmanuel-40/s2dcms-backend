package com.myproject.S2dcms.interceptor;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/* Reads the request body once and stores it so the body can be hashed for
 * idempotency and still consumed by the controller. Reads eagerly on
 * construction. Do not wrap multipart requests.
 */
public class ReplayableRequestWrapper extends HttpServletRequestWrapper {

    private static final int MAX_CACHED_BODY_BYTES = 1024 * 1024; // 1 MB

    private final byte[] cachedBody;
    private final boolean oversized;

    public ReplayableRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        byte[] bytes = StreamUtils.copyToByteArray(request.getInputStream());
        if (bytes.length > MAX_CACHED_BODY_BYTES) {
            this.cachedBody = new byte[0];
            this.oversized = true;
        } else {
            this.cachedBody = bytes;
            this.oversized = false;
        }
    }

    /* Returns true when the original request body exceeded the 1 MB cache
     * limit. The filter rejects such requests with HTTP 413. */
    public boolean isOversized() {
        return oversized;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return cachedBody.length;
    }

    @Override
    public long getContentLengthLong() {
        return cachedBody.length;
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }
    private static class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream inputStream;

        public CachedBodyServletInputStream(byte[] cachedBody) {
            this.inputStream = new ByteArrayInputStream(cachedBody);
        }

        @Override
        public boolean isFinished() {
            return inputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
            return inputStream.read();
        }
    }
}
