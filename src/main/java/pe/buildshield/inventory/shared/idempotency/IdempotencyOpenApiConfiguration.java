package pe.buildshield.inventory.shared.idempotency;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import pe.buildshield.inventory.shared.config.BuildshieldProperties;


/** Documenta en OpenAPI la cabecera Idempotency-Key de las rutas que la exigen. */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(OpenApiCustomizer.class)
public class IdempotencyOpenApiConfiguration {
    @Bean OpenApiCustomizer idempotencyContract(BuildshieldProperties properties) {
        return api -> api.getPaths().forEach((path, item) -> {
            if (properties.getIdempotency().getRequiredPaths().stream().noneMatch(pattern -> new AntPathMatcher().match(pattern, path))) return;
            if (item.getPost() == null) return;
            var operation = item.getPost();
            operation.addParametersItem(new Parameter().name("Idempotency-Key").in("header").required(true)
                    .description("UUID nuevo por operación; conservarlo y conservar los datos en cada reintento")
                    .schema(new Schema<String>().type("string").format("uuid"))
                    .example("c0bbafac-9db2-4f9e-9c8b-dcc9a98756b9"));
            for (String code : new String[]{"400", "403", "409", "413", "503"}) {
                operation.getResponses().addApiResponse(code, new ApiResponse()
                        .description(switch (code) {
                            case "400" -> "Datos inválidos, falta la clave, UUID inválido o clave reutilizada con datos distintos";
                            case "403" -> "Identidad o permisos actuales no permiten la operación ni su reproducción";
                            case "409" -> "Conflicto del negocio, operación en curso, respuesta vencida o registro histórico no verificable";
                            case "413" -> "La solicitud supera 1 MiB";
                            default -> "Fallo temporal; reintentar con la misma clave y datos";
                        }).content(new Content().addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ErrorResponse")))));
            }
        });
    }
}
