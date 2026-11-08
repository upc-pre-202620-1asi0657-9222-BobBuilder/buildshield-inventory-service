package pe.buildshield.inventory.shared.correlation;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generates_a_correlation_id_when_the_request_has_none() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response,
                (req, res) -> seenInChain.set(MDC.get(CorrelationId.MDC_KEY)));

        assertThat(seenInChain.get()).isNotBlank();
        assertThat(UUID.fromString(seenInChain.get())).isNotNull();
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(seenInChain.get());
    }

    @Test
    void keeps_the_correlation_id_received_in_the_header() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "web-7f3a9c");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(CorrelationId.current().orElseThrow()));

        assertThat(seenInChain.get()).isEqualTo("web-7f3a9c");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("web-7f3a9c");
    }

    @Test
    void replaces_an_invalid_header_value_with_a_new_id() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "bad value\n<script>");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationId.HEADER))
                .isNotEqualTo("bad value\n<script>")
                .matches(CorrelationId::isValid);
    }

    @Test
    void clears_the_mdc_after_the_request_even_if_it_fails() {
        FilterChain failingChain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failingChain))
                .hasMessage("boom");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void run_with_restores_the_previous_value() {
        MDC.put(CorrelationId.MDC_KEY, "outer");

        CorrelationId.runWith("inner", () ->
                assertThat(CorrelationId.current()).contains("inner"));

        assertThat(CorrelationId.current()).contains("outer");
    }

    @Test
    void run_with_null_generates_an_id() {
        CorrelationId.runWith(null, () -> assertThat(CorrelationId.current()).isPresent());

        assertThat(CorrelationId.current()).isEmpty();
    }
}
