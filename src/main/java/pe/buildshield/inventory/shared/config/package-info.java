/**
 * Configuración del kernel compartido (reloj, multiempresa, auditoría JPA, web, JWT, idempotencia y
 * mensajería). Se registra como autoconfiguración del propio servicio (META-INF/spring) para que sus
 * condiciones se evalúen después de las clases del servicio y las pruebas por capas puedan importarla.
 */
package pe.buildshield.inventory.shared.config;
