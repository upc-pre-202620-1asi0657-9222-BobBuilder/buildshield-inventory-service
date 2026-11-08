package pe.buildshield.inventory.architecture;

import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Reglas de arquitectura del microservicio, parametrizadas por paquete raíz para poder probarlas
 * también contra clases de ejemplo.
 */
final class ArchitectureRules {

    private ArchitectureRules() {
    }

    /**
     * Capas DDD del servicio: interfaces (REST) y infrastructure (persistencia, Kafka, adaptadores) usan
     * application y domain; application solo usa domain; domain no usa ninguna otra capa.
     */
    static ArchRule layersAreRespected(String rootPackage) {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true)
                .layer("Interfaces").definedBy(rootPackage + "..interfaces..")
                .layer("Application").definedBy(rootPackage + "..application..")
                .layer("Domain").definedBy(rootPackage + "..domain..")
                .layer("Infrastructure").definedBy(rootPackage + "..infrastructure..")
                .whereLayer("Interfaces").mayNotBeAccessedByAnyLayer()
                .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Interfaces", "Infrastructure")
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Interfaces", "Infrastructure")
                .because("las capas internas no dependen de las externas");
    }

    /** El kernel compartido es igual en todos los servicios: no depende de las capas del servicio. */
    static ArchRule sharedKernelIsIndependent(String rootPackage) {
        return noClasses()
                .that().resideInAPackage(rootPackage + ".shared..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        rootPackage + ".interfaces..",
                        rootPackage + ".application..",
                        rootPackage + ".domain..",
                        rootPackage + ".infrastructure..")
                .because("el kernel compartido se copia sin cambios entre servicios");
    }

    /** El dominio es Java puro: no depende de Spring, JPA ni Hibernate. */
    static ArchRule domainIsFrameworkFree(String rootPackage) {
        return noClasses()
                .that().resideInAPackage(rootPackage + "..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..")
                .allowEmptyShould(true)
                .because("el dominio no depende de Spring ni de JPA");
    }
}
