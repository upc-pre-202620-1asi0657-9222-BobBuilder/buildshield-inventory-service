package pe.buildshield.inventory.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@AnalyzeClasses(packages = ModuleBoundariesTest.SERVICE_PACKAGE, importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    static final String SERVICE_PACKAGE = "pe.buildshield.inventory";

    @ArchTest
    static final ArchRule layers_are_respected = ArchitectureRules.layersAreRespected(SERVICE_PACKAGE);

    @ArchTest
    static final ArchRule domain_does_not_depend_on_spring_or_jpa =
            ArchitectureRules.domainIsFrameworkFree(SERVICE_PACKAGE);

    @ArchTest
    static final ArchRule shared_kernel_does_not_depend_on_the_service =
            ArchitectureRules.sharedKernelIsIndependent(SERVICE_PACKAGE);

    /**
     * Comprueba que las reglas detectan violaciones reales usando clases de ejemplo,
     * para no confiar en reglas que pasan solo porque todavía no hay código.
     */
    @Nested
    class RulesDetectViolations {

        private static final String FIXTURES = "pe.buildshield.inventory.architecture.fixtures";

        private final JavaClasses fixtures = new ClassFileImporter().importPackages(FIXTURES);

        @Test
        void detects_application_using_infrastructure() {
            EvaluationResult result = ArchitectureRules.layersAreRespected(FIXTURES).evaluate(fixtures);

            assertThat(result.hasViolation()).isTrue();
            assertThat(result.getFailureReport().getDetails())
                    .anyMatch(line -> line.contains("BetaService") && line.contains("AlphaRepository"));
        }

        @Test
        void detects_spring_inside_the_domain() {
            EvaluationResult result = ArchitectureRules.domainIsFrameworkFree(FIXTURES).evaluate(fixtures);

            assertThat(result.hasViolation()).isTrue();
            assertThat(result.getFailureReport().getDetails())
                    .anyMatch(line -> line.contains("SpringAwareEntity"));
        }
    }
}
