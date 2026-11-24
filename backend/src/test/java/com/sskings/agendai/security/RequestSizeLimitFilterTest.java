package com.sskings.agendai.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RequestSizeLimitFilterTest {

    @Test
    @DisplayName("Leitura limitada: o mesmo fluxo nas duas chamadas, conta byte a byte, repassa o estado do fluxo original")
    void limitedStream() throws Exception {
        // sem Content-Length (como um envio em pedaços): o limite é aplicado durante a leitura
        AtomicReference<ReadListener> listener = new AtomicReference<>();
        java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream("abcdef".getBytes());
        ServletInputStream original = new ServletInputStream() {
            @Override public boolean isFinished() { return bytes.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { listener.set(l); }
            @Override public int read() { return bytes.read(); }
        };
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() { return original; }
        };
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        new RequestSizeLimitFilter(5).doFilter(req, new MockHttpServletResponse(), (r, res) -> seen.set((HttpServletRequest) r));
        ServletInputStream in = seen.get().getInputStream();
        assertThat(seen.get().getInputStream()).as("mesmo fluxo").isSameAs(in);
        assertThat(in.isFinished()).isFalse();
        assertThat(in.isReady()).isTrue();
        ReadListener l = mock(ReadListener.class);
        in.setReadListener(l);
        assertThat(listener.get()).as("repassado ao fluxo original").isSameAs(l);
        byte[] five = new byte[5];
        assertThat(in.read(five, 0, 5)).isEqualTo(5);
        assertThatThrownBy(in::read).isInstanceOf(RequestSizeLimitFilter.PayloadTooLargeException.class);
    }

    @Test
    @DisplayName("Content-Length acima do limite → 413 sem ler nada; exatamente no limite passa")
    void contentLength() throws Exception {
        MockHttpServletRequest big = new MockHttpServletRequest("POST", "/x");
        big.setContent(new byte[6]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain chain = new org.springframework.mock.web.MockFilterChain();
        new RequestSizeLimitFilter(5).doFilter(big, res, chain);
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
        assertThat(res.getContentType()).startsWith("application/json");
        assertThat(res.getContentAsString()).isEqualTo("{\"status\":413,\"message\":\"Request body too large\"}");
        MockHttpServletRequest exact = new MockHttpServletRequest("POST", "/x");
        exact.setContent(new byte[5]);
        org.springframework.mock.web.MockFilterChain ok = new org.springframework.mock.web.MockFilterChain();
        new RequestSizeLimitFilter(5).doFilter(exact, new MockHttpServletResponse(), ok);
        assertThat(ok.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("Fim do fluxo (-1) não desconta; byte zero conta; o estado do fluxo original é repassado como está")
    void countingRules() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.Iterator<Integer> bulk = java.util.List.of(-1, 2).iterator();   // -1 antes de dados (fluxo assíncrono)
        ServletInputStream original = new ServletInputStream() {
            @Override public boolean isFinished() { return finished.get(); }
            @Override public boolean isReady() { return !finished.get(); }
            @Override public void setReadListener(ReadListener l) { }
            @Override public int read() { return 0; }
            @Override public int read(byte[] b, int off, int len) { return bulk.next(); }
        };
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() { return original; }
        };
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        new RequestSizeLimitFilter(2).doFilter(req, new MockHttpServletResponse(), (r, res) -> seen.set((HttpServletRequest) r));
        ServletInputStream in = seen.get().getInputStream();
        assertThat(in.isReady()).isTrue();
        assertThat(in.isFinished()).isFalse();
        finished.set(true);
        assertThat(in.isReady()).isFalse();
        assertThat(in.isFinished()).isTrue();
        assertThat(in.read(new byte[2], 0, 2)).isEqualTo(-1);
        assertThat(in.read(new byte[2], 0, 2)).isEqualTo(2);
        assertThatThrownBy(in::read).as("3º byte (zero) passa do limite de 2").isInstanceOf(RequestSizeLimitFilter.PayloadTooLargeException.class);
    }

    @Test
    @DisplayName("Corpo exatamente no limite: a leitura do fim (-1) não conta como byte e não recusa")
    void endOfStreamAtLimit() throws Exception {
        java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(new byte[]{1, 2});
        ServletInputStream original = new ServletInputStream() {
            @Override public boolean isFinished() { return bytes.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { }
            @Override public int read() { return bytes.read(); }
        };
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/x") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() { return original; }
        };
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        new RequestSizeLimitFilter(2).doFilter(req, new MockHttpServletResponse(), (r, res) -> seen.set((HttpServletRequest) r));
        ServletInputStream in = seen.get().getInputStream();
        assertThat(in.read()).isEqualTo(1);
        assertThat(in.read()).isEqualTo(2);
        assertThat(in.read()).isEqualTo(-1);
    }
}
