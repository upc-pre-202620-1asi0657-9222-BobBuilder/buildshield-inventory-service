package pe.buildshield.inventory.shared.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Permite calcular la huella y dejar el mismo cuerpo disponible para Spring MVC. */
final class BufferedRequest extends HttpServletRequestWrapper {
    static final int MAX_BYTES = 1024 * 1024;
    private final byte[] body;
    BufferedRequest(HttpServletRequest request) throws IOException {
        super(request);
        body = request.getInputStream().readNBytes(MAX_BYTES + 1);
    }
    byte[] body() { return body; }
    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new IllegalStateException("La API procesa solicitudes síncronas");
            }
        };
    }
    @Override public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(),
                getCharacterEncoding() == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding())));
    }
}
