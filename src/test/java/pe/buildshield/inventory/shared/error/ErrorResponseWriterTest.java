package pe.buildshield.inventory.shared.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseWriterTest {

    @Test
    void writes_the_error_as_utf8_json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ErrorResponseWriter(new ObjectMapper()).write(response, 409,
                new ErrorResponse("IDEMPOTENCY_IN_PROGRESS", "La operación está en curso", null));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"IDEMPOTENCY_IN_PROGRESS\",\"message\":\"La operación está en curso\",\"details\":[]}");
    }

    @Test
    void error_response_copies_details_defensively() {
        List<ErrorDetail> details = new java.util.ArrayList<>(List.of(ErrorDetail.of("uno")));
        ErrorResponse response = new ErrorResponse("X", "y", details);
        details.clear();

        assertThat(response.details()).containsExactly(new ErrorDetail(null, "uno"));
    }
}
