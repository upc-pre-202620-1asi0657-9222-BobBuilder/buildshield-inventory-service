package pe.buildshield.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Inventario de BuildShield (microservicio, puerto 8083, base inventory_db). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BuildshieldInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildshieldInventoryApplication.class, args);
    }
}
