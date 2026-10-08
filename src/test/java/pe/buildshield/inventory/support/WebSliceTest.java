package pe.buildshield.inventory.support;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import pe.buildshield.inventory.shared.config.BuildshieldClockAutoConfiguration;
import pe.buildshield.inventory.shared.config.BuildshieldWebAutoConfiguration;
import pe.buildshield.inventory.shared.security.BuildshieldSecurityConfiguration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Para usar junto a {@code @WebMvcTest}: agrega la seguridad real del servicio (filtro JWT del kernel compartido,
 * claves de prueba, manejo de errores) sin base de datos. La idempotencia HTTP se apaga porque
 * necesita JDBC.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(BuildshieldSecurityConfiguration.class)
@ImportAutoConfiguration({BuildshieldClockAutoConfiguration.class, BuildshieldWebAutoConfiguration.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "buildshield.idempotency.enabled=false")
public @interface WebSliceTest {
}
